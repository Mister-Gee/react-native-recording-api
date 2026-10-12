import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { PermissionsAndroid, Platform } from 'react-native';
import {
  RecordingApiError,
  getStatus,
  hasStepSensor,
  isSupported,
  isValidBucketMinutes,
  readSteps,
  requestPermission,
  subscribe,
  unsubscribe,
} from '../index';

const mockNative = {
  getStatus: jest.fn<() => Promise<string>>(),
  hasStepSensor: jest.fn<() => Promise<boolean>>(),
  subscribe: jest.fn<() => Promise<string>>(),
  unsubscribe: jest.fn<() => Promise<void>>(),
  readSteps:
    jest.fn<
      (
        startMs: number,
        endMs: number,
        bucketMinutes: number
      ) => Promise<{ startMs: number; endMs: number; steps: number }[]>
    >(),
};

let mockModule: typeof mockNative | null = mockNative;

// A getter, because the hoisted factory runs before `mockNative` is initialised,
// and so a test can swap the module out (imports are live bindings).
jest.mock('../module', () => ({
  __esModule: true,
  get default() {
    return mockModule;
  },
}));

let os = 'android';
let version = 34;

beforeEach(() => {
  os = 'android';
  version = 34;
  mockModule = mockNative;
  Object.defineProperty(Platform, 'OS', { configurable: true, get: () => os });
  Object.defineProperty(Platform, 'Version', {
    configurable: true,
    get: () => version,
  });
  Object.values(mockNative).forEach((fn) => fn.mockReset());
});

afterEach(() => {
  jest.restoreAllMocks();
});

const nativeError = (code: string, message: string) =>
  Object.assign(new Error(message), { code });

describe('non-Android platforms', () => {
  it.each(['ios', 'web'])(
    'reports unsupported on %s without touching native',
    async (platform) => {
      os = platform;
      expect(isSupported()).toBe(false);
      await expect(getStatus()).resolves.toBe('unsupported');
      await expect(hasStepSensor()).resolves.toBe(false);
      await expect(subscribe()).resolves.toBe('unsupported');
      await expect(requestPermission()).resolves.toBe('unsupported');
      await expect(unsubscribe()).resolves.toBeUndefined();
      await expect(readSteps({ startTime: 0 })).rejects.toMatchObject({
        code: 'ERR_UNSUPPORTED',
      });
      Object.values(mockNative).forEach((fn) =>
        expect(fn).not.toHaveBeenCalled()
      );
    }
  );
});

describe('status and subscription', () => {
  it('passes native status and subscribe results through', async () => {
    mockNative.getStatus.mockResolvedValue('play_services_update_required');
    mockNative.subscribe.mockResolvedValue('subscription_failed');
    mockNative.hasStepSensor.mockResolvedValue(true);
    await expect(getStatus()).resolves.toBe('play_services_update_required');
    await expect(subscribe()).resolves.toBe('subscription_failed');
    await expect(hasStepSensor()).resolves.toBe(true);
  });

  it('requests ACTIVITY_RECOGNITION on Android 10+ only', async () => {
    const request = jest
      .spyOn(PermissionsAndroid, 'request')
      .mockResolvedValue(PermissionsAndroid.RESULTS.GRANTED);
    mockNative.getStatus.mockResolvedValue('ready');

    await expect(requestPermission()).resolves.toBe('ready');
    expect(request).toHaveBeenCalledWith(
      PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION
    );

    request.mockClear();
    version = 28;
    await expect(requestPermission()).resolves.toBe('ready');
    expect(request).not.toHaveBeenCalled();
  });

  it('maps unsubscribe failures to RecordingApiError', async () => {
    mockNative.unsubscribe.mockRejectedValue(
      nativeError('ERR_UNSUBSCRIBE_FAILED', 'boom')
    );
    const error = await unsubscribe().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(RecordingApiError);
    expect(error).toMatchObject({
      code: 'ERR_UNSUBSCRIBE_FAILED',
      message: 'boom',
    });
  });
});

describe('readSteps', () => {
  it.each([7, 100, 1441, 2_147_483_647])(
    'preserves a short unaligned range and explicit zero with width %p',
    async (bucketMinutes) => {
      const start = Date.UTC(2026, 9, 8, 12, 7);
      const end = Date.UTC(2026, 9, 8, 12, 12);
      mockNative.readSteps.mockResolvedValue([
        { startMs: start, endMs: end, steps: 0 },
      ]);

      await expect(
        readSteps({ startTime: start, endTime: end, bucketMinutes })
      ).resolves.toEqual([
        {
          startTime: '2026-10-08T12:07:00.000Z',
          endTime: '2026-10-08T12:12:00.000Z',
          steps: 0,
        },
      ]);
      expect(mockNative.readSteps).toHaveBeenCalledWith(
        start,
        end,
        bucketMinutes
      );
    }
  );

  it('accepts Date, ISO and epoch inputs and returns ISO buckets', async () => {
    mockNative.readSteps.mockResolvedValue([
      {
        startMs: Date.UTC(2026, 9, 8, 12),
        endMs: Date.UTC(2026, 9, 8, 12, 15),
        steps: 42,
      },
    ]);
    const start = new Date(Date.UTC(2026, 9, 8));
    const result = await readSteps({
      startTime: start,
      endTime: '2026-10-08T13:00:00.000Z',
      bucketMinutes: 60,
    });
    expect(mockNative.readSteps).toHaveBeenCalledWith(
      start.getTime(),
      Date.UTC(2026, 9, 8, 13),
      60
    );
    expect(result).toEqual([
      {
        startTime: '2026-10-08T12:00:00.000Z',
        endTime: '2026-10-08T12:15:00.000Z',
        steps: 42,
      },
    ]);
  });

  it('defaults to 15-minute buckets ending now', async () => {
    jest.spyOn(Date, 'now').mockReturnValue(5_000_000);
    mockNative.readSteps.mockResolvedValue([]);
    await expect(readSteps({ startTime: 1_000 })).resolves.toEqual([]);
    expect(mockNative.readSteps).toHaveBeenCalledWith(1_000, 5_000_000, 15);
  });

  it.each([0, 15.5, -15, 2_147_483_648, Number.NaN, Number.POSITIVE_INFINITY])(
    'rejects bucketMinutes=%p before calling native',
    async (bucketMinutes) => {
      await expect(
        readSteps({ startTime: 0, endTime: 1, bucketMinutes })
      ).rejects.toMatchObject({
        code: 'ERR_INVALID_BUCKET',
      });
      expect(mockNative.readSteps).not.toHaveBeenCalled();
    }
  );

  it.each([
    { startTime: 10, endTime: 10 },
    { startTime: 10, endTime: 5 },
    { startTime: 'not a date', endTime: 5 },
    { startTime: 0, endTime: Number.POSITIVE_INFINITY },
    { startTime: new Date(Number.NaN), endTime: 5 },
  ])('rejects invalid range %p before calling native', async (range) => {
    await expect(readSteps(range)).rejects.toMatchObject({
      code: 'ERR_INVALID_RANGE',
    });
    expect(mockNative.readSteps).not.toHaveBeenCalled();
  });

  it('exposes the blocking status on ERR_RECORDING_UNAVAILABLE', async () => {
    mockNative.readSteps.mockRejectedValue(
      nativeError('ERR_RECORDING_UNAVAILABLE', 'needs_permission')
    );
    const error = await readSteps({ startTime: 0, endTime: 1 }).catch(
      (e: unknown) => e
    );
    expect(error).toBeInstanceOf(RecordingApiError);
    expect(error).toMatchObject({
      code: 'ERR_RECORDING_UNAVAILABLE',
      status: 'needs_permission',
    });
  });

  it('rethrows unknown native errors untouched', async () => {
    const original = nativeError('E_SOMETHING_ELSE', 'other');
    mockNative.readSteps.mockRejectedValue(original);
    await expect(readSteps({ startTime: 0, endTime: 1 })).rejects.toBe(
      original
    );
  });
});

describe('isValidBucketMinutes', () => {
  it('accepts every positive whole provider-representable width', () => {
    expect(
      [1, 7, 100, 1440, 1441, 2880, 2_147_483_647].every(isValidBucketMinutes)
    ).toBe(true);
    expect(
      [0, -1, 2.5, 2_147_483_648, Number.NaN, Number.POSITIVE_INFINITY].some(
        isValidBucketMinutes
      )
    ).toBe(false);
  });
});

describe('missing native module', () => {
  it('throws ERR_NOT_LINKED on Android', async () => {
    mockModule = null;
    await expect(getStatus()).rejects.toMatchObject({ code: 'ERR_NOT_LINKED' });
    await expect(
      readSteps({ startTime: 0, endTime: 1 })
    ).rejects.toBeInstanceOf(RecordingApiError);
  });
});
