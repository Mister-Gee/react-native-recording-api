import { useCallback, useEffect, useState } from 'react';
import { Button, FlatList, StyleSheet, Text, View } from 'react-native';
import {
  getStatus,
  readSteps,
  requestPermission,
  subscribe,
  unsubscribe,
  type RecordingStatus,
  type StepBucket,
  type SubscribeResult,
} from 'react-native-recording-api';

function startOfLocalDay(): Date {
  const date = new Date();
  date.setHours(0, 0, 0, 0);
  return date;
}

export default function App() {
  const [status, setStatus] = useState<RecordingStatus | SubscribeResult>();
  const [buckets, setBuckets] = useState<StepBucket[]>([]);
  const [error, setError] = useState<string>();

  const run = useCallback(async (task: () => Promise<void>) => {
    setError(undefined);
    try {
      await task();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    run(async () => setStatus(await getStatus()));
  }, [run]);

  // Read the caller's local calendar day starting at local midnight.
  const readToday = () =>
    run(async () =>
      setBuckets(await readSteps({ startTime: startOfLocalDay() }))
    );

  const total = buckets.reduce((sum, bucket) => sum + bucket.steps, 0);

  return (
    <View style={styles.container}>
      <Text style={styles.label}>Status: {status ?? '...'}</Text>
      {error ? <Text style={styles.error}>{error}</Text> : null}
      <View style={styles.actions}>
        <Button
          title="Request permission"
          onPress={() => run(async () => setStatus(await requestPermission()))}
        />
        <Button
          title="Subscribe"
          onPress={() => run(async () => setStatus(await subscribe()))}
        />
        <Button title="Read today" onPress={readToday} />
        <Button
          title="Unsubscribe"
          onPress={() =>
            run(async () => {
              await unsubscribe();
              setBuckets([]);
              setStatus(await getStatus());
            })
          }
        />
      </View>
      <Text style={styles.label}>Today: {total} steps</Text>
      <FlatList
        data={buckets}
        keyExtractor={(bucket) => bucket.startTime}
        renderItem={({ item }) => (
          <Text style={styles.row}>
            {new Date(item.startTime).toLocaleTimeString()} to{' '}
            {new Date(item.endTime).toLocaleTimeString()}: {item.steps}
          </Text>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, paddingTop: 64, gap: 12 },
  actions: { gap: 8 },
  label: { fontSize: 16, fontWeight: '600' },
  error: { color: 'firebrick' },
  row: { fontVariant: ['tabular-nums'], paddingVertical: 2 },
});
