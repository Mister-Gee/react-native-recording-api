# react-native-recording-api

On-device Android step counting with Google's [Recording API on mobile](https://developer.android.com/health-and-fitness/recording-api)
(`play-services-fitness` `LocalRecordingClient`). No Google account, no Health Connect,
no foreground service: Play services records steps in the background and you read them
back in fixed UTC buckets.

Works in **Expo** (development builds / prebuild) and **React Native CLI** apps. It is a
plain TurboModule with no Expo dependency and no config plugin to add.

- Explicit readiness states, so you know *why* steps are unavailable
- Concurrency-safe, idempotent `subscribe()`
- Bucketed reads on a fixed UTC grid, so overlapping reads agree on closed buckets
- Validates the buckets Play services returns instead of passing through malformed data
- iOS and web: a typed no-op (`unsupported`), never a crash

## Requirements

|  | Minimum |
|---|---|
| React Native | 0.76, New Architecture |
| Expo | SDK 52, development build (Expo Go is not supported) |
| Android | API 24, a hardware step counter, Google Play services at or above `LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE` |

## Installation

```sh
npm install react-native-recording-api
```

Expo: rebuild your development build (`npx expo prebuild` / `npx expo run:android` / EAS
Build). React Native CLI: rebuild the Android app. Autolinking does the rest.

The library's manifest adds `android.permission.ACTIVITY_RECOGNITION`, and its Gradle file adds
`com.google.android.gms:play-services-fitness`. To use a different fitness version, set
`ext.playServicesFitnessVersion` in your root `build.gradle`.

## Usage

```ts
import {
  getStatus,
  readSteps,
  requestPermission,
  subscribe,
} from 'react-native-recording-api';

// Once, e.g. at app start or during onboarding:
let status = await getStatus();
if (status === 'needs_permission') status = await requestPermission();
if (status === 'ready') status = await subscribe(); // idempotent; safe on every launch

// Later: today's steps in 15-minute buckets.
const midnight = new Date();
midnight.setHours(0, 0, 0, 0);
const buckets = await readSteps({ startTime: midnight });
const today = buckets.reduce((sum, b) => sum + b.steps, 0);
```

## API

### `getStatus(): Promise<RecordingStatus>`

| Status | Meaning |
|---|---|
| `ready` | Permission granted, step sensor present, Play services new enough |
| `needs_permission` | `ACTIVITY_RECOGNITION` not granted (Android 10+) |
| `no_sensor` | No hardware step counter |
| `play_services_update_required` | Play services is too old for the Recording API |
| `play_services_missing` | Play services is absent, disabled or invalid |
| `unsupported` | Not Android |

### `requestPermission(): Promise<RecordingStatus>`

Requests `ACTIVITY_RECOGNITION` through `PermissionsAndroid` on Android 10+ (older versions
grant it at install), then returns `getStatus()`. You can use your own permission flow
instead.

### `subscribe(): Promise<SubscribeResult>`

Starts, or renews, background recording in Play services. Resolves `ready` on success,
otherwise the blocking status or `subscription_failed`. Concurrent calls share one
subscription task. The subscription belongs to Play services, not your process, so it
survives app restarts. Calling `subscribe()` on every launch is cheap and recommended.

### `unsubscribe(): Promise<void>`

Stops recording. **Play services then drops access to everything recorded under the
subscription**, and a later `subscribe()` starts from empty.

### `readSteps(options): Promise<StepBucket[]>`

```ts
readSteps({
  startTime: Date | string | number, // Date, ISO-8601, or epoch ms
  endTime?: Date | string | number,  // default: now; clamped to now
  bucketMinutes?: number,            // default 15; must divide 1440
});
// => [{ startTime: '2026-10-08T12:00:00.000Z', endTime: '2026-10-08T12:15:00.000Z', steps: 42 }, ...]
```

Read semantics:

- **Buckets sit on a fixed UTC epoch grid** of `bucketMinutes` width. Two reads with
  different start times return identical values for every bucket they share.
- **The start is rounded up** to the next grid boundary, so a partially covered leading
  bucket is left out rather than under-counted. Pass a boundary-aligned start if you need
  that bucket.
- **Retention:** Play services keeps up to 10 days (`RETENTION_DAYS`). Earlier starts are
  clamped to the first whole bucket inside that window.
- **The trailing bucket may be partial.** It ends at `endTime`, or now for the bucket in
  progress.
- **Buckets with zero steps are omitted.**
- The first bucket of a fresh subscription may start mid-grid, at Play's first recorded
  data point. It still never spans two grid cells.
- `readSteps` does not subscribe for you. Call `subscribe()` first.

**Local-day totals and time zones.** `bucketMinutes: 1440` gives UTC days, not local days.
For local days, read 15-minute buckets from local midnight and sum them, as in the example
above. Every real UTC offset is a multiple of 15 minutes, so local midnight always falls
on a 15-minute boundary. Hourly buckets work only in whole-hour offsets.

### Errors

Rejections are `RecordingApiError` with a `code`:

| Code | When |
|---|---|
| `ERR_INVALID_RANGE` | Unparseable times, or `endTime <= startTime` |
| `ERR_INVALID_BUCKET` | `bucketMinutes` is not a whole divisor of 1440 |
| `ERR_RECORDING_UNAVAILABLE` | Status is not `ready`; see `error.status` |
| `ERR_READ_FAILED` | Play services read failed, or returned malformed buckets |
| `ERR_UNSUBSCRIBE_FAILED` | Play services rejected `unsubscribe()` |
| `ERR_UNSUPPORTED` | `readSteps` called off Android |
| `ERR_NOT_LINKED` | Native module missing (not rebuilt, or Expo Go) |

### Helpers

`isSupported()`, `hasStepSensor()`, `isValidBucketMinutes(n)`, `RETENTION_DAYS`,
`DEFAULT_BUCKET_MINUTES`.

## Background collection

Play services records without your app running, but it only keeps 10 days. If you need
longer history, read periodically (for example from a background task or on each app
open) and store the buckets yourself. Because closed buckets on the UTC grid are stable,
overlapping reads can be upserted by `startTime`. The exception is the first bucket of a
fresh subscription, which may start off-grid.

## Compared with alternatives

- **Expo `Pedometer` (expo-sensors):** on Android it only gives live counts while the app
  runs. Recording API support was proposed in [expo/expo#35652](https://github.com/expo/expo/pull/35652),
  which was still open when this README was written.
- **Google Fit libraries** (`react-native-google-fit`, ...): the Google Fit APIs are
  deprecated and supported only until the end of 2026.
- **Health Connect:** reads many data types from many sources and suits that use case.
  It needs the Health Connect app and per-type permissions. This library covers the
  narrower, simpler case of on-device steps.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
