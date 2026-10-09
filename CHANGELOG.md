# Changelog

## 0.1.0 (unreleased)

Initial release.

- `getStatus`, `requestPermission`, `subscribe`, `unsubscribe`, `readSteps`, `hasStepSensor`
- Readiness states: `ready`, `needs_permission`, `no_sensor`, `play_services_update_required`, `play_services_missing`, `unsupported`
- Configurable bucket width (divisors of 1440 minutes) on a fixed UTC grid, with 10-day retention clamping
- Validation of the buckets Play services returns
- TurboModule (New Architecture), Android only; iOS and web are typed no-ops
