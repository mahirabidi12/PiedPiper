import { ScrollView, Text, View } from 'react-native';

import { Metric } from '../components/Metric';
import { SignalCard } from '../components/SignalCard';
import { colors } from '../theme/colors';
import { styles } from '../theme/styles';
import type { Signal, SignalStatus } from '../types';
import { statusRank } from '../services/triage';

export function AdminHome({
  signals,
  onChangeStatus,
}: {
  signals: Signal[];
  onChangeStatus: (signal: Signal, status: SignalStatus) => void;
}) {
  const active = [...signals]
    .filter((signal) => signal.status !== 'resolved')
    .sort((a, b) => statusRank(a.priority) - statusRank(b.priority) || b.createdAt - a.createdAt);
  const critical = active.filter((signal) => signal.priority === 'critical');

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <View style={styles.commandGrid}>
        <Metric label="Critical" value={critical.length} color={colors.critical} />
        <Metric label="Open" value={active.length} color={colors.blue} />
        <Metric label="Queued" value={signals.filter((signal) => signal.status === 'queued').length} color={colors.medium} />
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Priority Command Center</Text>
        <Text style={styles.cardCopy}>Incoming user signals are ranked locally and shown as action cards.</Text>
        {active.length === 0 ? (
          <Text style={styles.emptyText}>No active signals. This admin node is ready.</Text>
        ) : (
          active.map((signal) => (
            <SignalCard key={signal.id} signal={signal} onChangeStatus={onChangeStatus} />
          ))
        )}
      </View>
    </ScrollView>
  );
}
