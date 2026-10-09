import type { Spec } from './NativeRecordingApi';

// Web and other non-native targets: TurboModuleRegistry is unavailable there.
const NativeRecordingApi: Spec | null = null;

export default NativeRecordingApi;
