import { PermissionsAndroid, Platform } from 'react-native';
import NativeRecordingApi from './module';
import type { Spec } from './NativeRecordingApi';

/**
 * Whether steps can be recorded and read right now.
 *
 * - `ready`: permission granted, step sensor present, Play services new enough.
 * - `needs_permission`: `ACTIVITY_RECOGNITION` not granted (Android 10+).
 * - `no_sensor`: the device has no hardware step counter.
 * - `play_services_update_required`: Play services is older than the Recording API needs.
 * - `play_services_missing`: Play services is absent, disabled or invalid.
 * - `unsupported`: not Android (iOS, web).
 */
export type RecordingStatus =
  | 'ready'
  | 'needs_permission'
  | 'no_sensor'
  | 'play_services_update_required'
  | 'play_services_missing'
  | 'unsupported';

/** A status, or `subscription_failed` when Play services rejected a subscribe call. */
export type SubscribeResult = RecordingStatus | 'subscription_failed';

export interface StepBucket {
  /** ISO-8601 UTC instant; inclusive. */
  startTime: string;
  /** ISO-8601 UTC instant; exclusive. */
  endTime: string;
  steps: number;
}

/** A `Date`, an ISO-8601 string, or epoch milliseconds. */
export type TimeInput = Date | string | number;

export interface ReadStepsOptions {
  startTime: TimeInput;
  /** Defaults to now. Clamped to now. */
  endTime?: TimeInput;
  /**
   * Bucket width in minutes. Must divide a day evenly (1, 5, 15, 30, 60, 1440, ...).
   * Buckets sit on a fixed UTC epoch grid. Defaults to 15.
   */
  bucketMinutes?: number;
}

export type RecordingApiErrorCode =
  | 'ERR_INVALID_RANGE'
  | 'ERR_INVALID_BUCKET'
  | 'ERR_RECORDING_UNAVAILABLE'
  | 'ERR_READ_FAILED'
  | 'ERR_UNSUBSCRIBE_FAILED'
  | 'ERR_UNSUPPORTED'
  | 'ERR_NOT_LINKED';

const ERROR_CODES: ReadonlySet<string> = new Set<RecordingApiErrorCode>([
  'ERR_INVALID_RANGE',
  'ERR_INVALID_BUCKET',
  'ERR_RECORDING_UNAVAILABLE',
  'ERR_READ_FAILED',
  'ERR_UNSUBSCRIBE_FAILED',
  'ERR_UNSUPPORTED',
  'ERR_NOT_LINKED',
]);

export class RecordingApiError extends Error {
  readonly code: RecordingApiErrorCode;
  /** Set on `ERR_RECORDING_UNAVAILABLE`: the status that blocked the read. */
  readonly status?: RecordingStatus;

  constructor(
    code: RecordingApiErrorCode,
    message: string,
    status?: RecordingStatus
  ) {
    super(message);
    this.name = 'RecordingApiError';
    this.code = code;
    this.status = status;
  }
}

/** Play services keeps at most this many days of recorded data. */
export const RETENTION_DAYS = 10;
export const DEFAULT_BUCKET_MINUTES = 15;
const MINUTES_PER_DAY = 1440;

export function isSupported(): boolean {
  return Platform.OS === 'android';
}

/** True when `minutes` is a whole number of minutes that divides a day evenly. */
export function isValidBucketMinutes(minutes: number): boolean {
  return (
    Number.isInteger(minutes) &&
    minutes >= 1 &&
    minutes <= MINUTES_PER_DAY &&
    MINUTES_PER_DAY % minutes === 0
  );
}

function native(): Spec {
  if (NativeRecordingApi == null) {
    throw new RecordingApiError(
      'ERR_NOT_LINKED',
      'react-native-recording-api: native module not found. Rebuild the app after ' +
        'installing; Expo Go is not supported, use a development build.'
    );
  }
  return NativeRecordingApi;
}

/** Re-throw native rejections as `RecordingApiError` when they carry one of our codes. */
async function call<T>(run: (module: Spec) => Promise<T>): Promise<T> {
  const module = native();
  try {
    return await run(module);
  } catch (error) {
    const code = (error as { code?: unknown } | null)?.code;
    if (typeof code === 'string' && ERROR_CODES.has(code)) {
      const message = (error as Error).message;
      throw new RecordingApiError(
        code as RecordingApiErrorCode,
        message,
        code === 'ERR_RECORDING_UNAVAILABLE'
          ? (message as RecordingStatus)
          : undefined
      );
    }
    throw error;
  }
}

function toEpochMs(input: TimeInput): number | null {
  const ms =
    input instanceof Date
      ? input.getTime()
      : typeof input === 'string'
        ? Date.parse(input)
        : input;
  return typeof ms === 'number' && Number.isFinite(ms) ? ms : null;
}

export async function getStatus(): Promise<RecordingStatus> {
  if (!isSupported()) return 'unsupported';
  return (await call((m) => m.getStatus())) as RecordingStatus;
}

export async function hasStepSensor(): Promise<boolean> {
  if (!isSupported()) return false;
  return call((m) => m.hasStepSensor());
}

/**
 * Ask for `ACTIVITY_RECOGNITION` (Android 10+; earlier versions grant it at
 * install) and return the resulting status.
 */
export async function requestPermission(): Promise<RecordingStatus> {
  if (!isSupported()) return 'unsupported';
  if (Number(Platform.Version) >= 29) {
    await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION
    );
  }
  return getStatus();
}

/**
 * Start (or renew) background step recording in Play services. Idempotent and
 * safe to call on every app start; concurrent calls share one subscription task.
 * Resolves `ready` on success, otherwise the blocking status or `subscription_failed`.
 */
export async function subscribe(): Promise<SubscribeResult> {
  if (!isSupported()) return 'unsupported';
  return (await call((m) => m.subscribe())) as SubscribeResult;
}

/**
 * Stop recording. Play services then discards access to everything recorded
 * under the subscription; a later `subscribe()` starts from empty.
 */
export async function unsubscribe(): Promise<void> {
  if (!isSupported()) return;
  await call((m) => m.unsubscribe());
}

/**
 * Read recorded steps as UTC-grid buckets. Requires `subscribe()` first.
 *
 * - The start is rounded up to the next bucket boundary (and to the retention
 *   limit), so a partially covered leading bucket is omitted rather than
 *   under-counted. Pass a boundary-aligned start to keep it.
 * - The end is clamped to now; the trailing bucket may be partial.
 * - Buckets with zero steps are omitted.
 */
export async function readSteps(
  options: ReadStepsOptions
): Promise<StepBucket[]> {
  if (!isSupported()) {
    throw new RecordingApiError(
      'ERR_UNSUPPORTED',
      'The Recording API is only available on Android'
    );
  }
  const bucketMinutes = options.bucketMinutes ?? DEFAULT_BUCKET_MINUTES;
  if (!isValidBucketMinutes(bucketMinutes)) {
    throw new RecordingApiError(
      'ERR_INVALID_BUCKET',
      `bucketMinutes must be a whole number that divides ${MINUTES_PER_DAY}; got ${bucketMinutes}`
    );
  }
  const start = toEpochMs(options.startTime);
  const end =
    options.endTime === undefined ? Date.now() : toEpochMs(options.endTime);
  if (start === null || end === null || end <= start) {
    throw new RecordingApiError(
      'ERR_INVALID_RANGE',
      'readSteps requires valid times with endTime after startTime'
    );
  }
  const buckets = await call((m) => m.readSteps(start, end, bucketMinutes));
  return buckets.map((bucket) => ({
    startTime: new Date(bucket.startMs).toISOString(),
    endTime: new Date(bucket.endMs).toISOString(),
    steps: bucket.steps,
  }));
}
