# Changelog

## 0.1.0 (unreleased)

Initial release.

- `getStatus`, `requestPermission`, `subscribe`, `unsubscribe`, `readSteps`, `hasStepSensor`
- Readiness states: `ready`, `needs_permission`, `no_sensor`, `play_services_update_required`, `play_services_missing`, `unsupported`
- General-purpose reads using exact caller bounds clamped to 10-day retention and now,
  with any positive whole provider-representable bucket width
- Inclusive provider query bounds and request-dependent returned intervals, without
  fixed-grid, exclusive-end, finality or immutable bucket-identity guarantees
- Present empty/zero datasets retained, missing datasets omitted, and positive counts
  rejected unless aggregate intervals establish query-contained coverage
- Ordered subscribe/unsubscribe provider mutations, unsubscribe failure reporting,
  complete step-counter sensor discovery and React Native 0.76 constructor compatibility
- Generated Android, Expo and web-export output excluded from maintained-source linting
- TurboModule (New Architecture), Android only; iOS and web are typed no-ops
