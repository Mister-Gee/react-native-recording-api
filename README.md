# react-native-recording-api

On-device Android step counting with Google's [Recording API on mobile](https://developer.android.com/health-and-fitness/recording-api)
(`play-services-fitness` `LocalRecordingClient`). No Google account, no Health Connect,
no foreground service: Play services records steps in the background and you read them
back in provider-created time buckets.

Works in **Expo** (development builds / prebuild) and **React Native CLI** apps. It is a
plain TurboModule with no Expo dependency and no config plugin to add.

- Explicit readiness states, so you know *why* steps are unavailable
- Ordered, concurrency-safe `subscribe()` and `unsubscribe()` mutations
- Caller-selected ranges and positive whole-minute provider bucket widths
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

`ready` describes device prerequisites, not an active subscription. Call `subscribe()`
before reading.

### `requestPermission(): Promise<RecordingStatus>`

Requests `ACTIVITY_RECOGNITION` through `PermissionsAndroid` on Android 10+ (older versions
grant it at install), then returns `getStatus()`. You can use your own permission flow
instead.

### `subscribe(): Promise<SubscribeResult>`

Starts, or renews, background recording in Play services. Resolves `ready` on success,
otherwise the blocking status or `subscription_failed`. Concurrent calls share one
subscription task when no unsubscribe call is between them. Recording-state mutations
run in invocation order. The subscription belongs to Play services, not your process, so it
survives app restarts. Calling `subscribe()` on every launch is cheap and recommended.

### `unsubscribe(): Promise<void>`

Stops recording. **Play services then drops access to everything recorded under the
subscription**, and a later `subscribe()` starts from empty.

Resolves only after Play services confirms removal. If the service cannot be reached,
the operation is cancelled, or removal fails, it rejects with `ERR_UNSUBSCRIBE_FAILED`;
the recording state is then unconfirmed. Start and stop requests run in invocation order.

### `readSteps(options): Promise<StepBucket[]>`

```ts
readSteps({
  startTime: Date | string | number, // Date, ISO-8601, or epoch ms
  endTime?: Date | string | number,  // default: now; clamped to now
  bucketMinutes?: number,            // default 15; whole number, 1..2,147,483,647
});
// => [{ startTime: '2026-10-08T12:00:00.000Z', endTime: '2026-10-08T12:15:00.000Z', steps: 42 }, ...]
```

Read semantics:

- **Exact effective range:** the requested start is clamped to exactly `now - 10 days`
  (`RETENTION_DAYS`) and the requested end is clamped to exactly now. Neither boundary is
  rounded to a grid. If those clamps leave no increasing range, the result is empty.
- **Inclusive provider query:** Google documents both bounds passed to the local read as
  inclusive. The library sends the effective millisecond bounds unchanged and performs no
  `+/- 1 ms` conversion.
- **Provider intervals:** `bucketMinutes` is any positive whole number accepted by the
  provider's integer argument, including widths longer than a day or the query. Returned
  start/end values are provider interval metadata clipped to the effective query when the
  positive aggregates prove that clipping is safe. They are not promised to be half-open,
  exclusive-ended, fixed to a UTC grid or identical across separate reads.
- **Zeros and gaps:** a present empty dataset or a dataset summing to zero is returned as
  an explicit zero bucket. A missing step dataset is omitted, and the library does not fill
  gaps. A zero bucket does not prove continuous sensor observation over the interval.
- **Count integrity:** every aggregate count and total must fit the non-negative native
  return range. Positive aggregate intervals must fit both their original provider bucket
  and the effective query. Ambiguous overhang is rejected rather than proportionally split.
- `readSteps` does not subscribe for you. Call `subscribe()` first.

**Local-day totals and time zones.** For a local-day request, pass local midnight as the
start and sum the returned provider buckets, as in the example above. Bucket metadata can
depend on that request range and on what Play services returns.

### Errors

Rejections are `RecordingApiError` with a `code`:

| Code | When |
|---|---|
| `ERR_INVALID_RANGE` | Unparseable times, or `endTime <= startTime` |
| `ERR_INVALID_BUCKET` | `bucketMinutes` is outside the whole-number range 1..2,147,483,647 |
| `ERR_RECORDING_UNAVAILABLE` | Status is not `ready`; see `error.status` |
| `ERR_READ_FAILED` | Play services read failed, or returned malformed buckets |
| `ERR_UNSUBSCRIBE_FAILED` | Removal failed, was cancelled, or Play services was unavailable |
| `ERR_UNSUPPORTED` | `readSteps` called off Android |
| `ERR_NOT_LINKED` | Native module missing (not rebuilt, or Expo Go) |

### Helpers

`isSupported()`, `hasStepSensor()`, `isValidBucketMinutes(n)`, `RETENTION_DAYS`,
`DEFAULT_BUCKET_MINUTES`.

## Background collection

Play services records without your app running, but it only keeps 10 days. If you need
longer history, read periodically (for example from a background task or on each app
open) and store the results yourself. Provider delivery timing and bucket identity are not
finality guarantees. Consumers should reread a bounded overlap and reconcile it using an
application-specific policy instead of blindly upserting by `startTime`.

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
