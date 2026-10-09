import { TurboModuleRegistry, type TurboModule } from 'react-native';

export type NativeStepBucket = {
  startMs: number;
  endMs: number;
  steps: number;
};

export interface Spec extends TurboModule {
  getStatus(): Promise<string>;
  hasStepSensor(): Promise<boolean>;
  subscribe(): Promise<string>;
  unsubscribe(): Promise<void>;
  readSteps(
    startMs: number,
    endMs: number,
    bucketMinutes: number
  ): Promise<NativeStepBucket[]>;
}

// `get`, not `getEnforcing`: the module only exists on Android, and a missing
// module is reported by the public API as a typed error rather than at import.
export default TurboModuleRegistry.get<Spec>('RecordingApi');
