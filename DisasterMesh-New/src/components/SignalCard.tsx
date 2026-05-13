import { Pressable, Text, View } from 'react-native';

import { priorityColor } from '../theme/colors';
import { styles } from '../theme/styles';
import type { Signal, SignalStatus } from '../types';
import { formatTime, statusLabel } from '../utils/format';

export function SignalCard({
  signal,
  compact,
  onChangeStatus,
  children,
}: {
  signal: Signal;
  compact?: boolean;
  onChangeStatus?: (signal: Signal, status: SignalStatus) => void;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.signalCard}>
      <View style={[styles.signalPriorityRail, { backgroundColor: priorityColor(signal.priority) }]} />
      <View style={styles.signalTop}>
        <View style={[styles.priorityDot, { backgroundColor: priorityColor(signal.priority) }]} />
        <Text style={styles.signalTitle}>{signal.summary}</Text>
      </View>
      <Text style={styles.signalMeta}>
        Civilian: {signal.senderName} • {formatTime(signal.createdAt)} • {signal.language}
      </Text>
      <Text style={styles.signalBody}>{signal.message}</Text>
      <View style={styles.statusRow}>
        <Text style={styles.statusPill}>{statusLabel(signal.status)}</Text>
        <Text style={styles.routeText}>{signal.routeReason}</Text>
      </View>
      {!!signal.assignedVolunteerName && (
        <Text style={styles.assignmentPill}>ASSIGNED: {signal.assignedVolunteerName}</Text>
      )}
      {(!!signal.manualLocation || !!signal.location) && (
        <View style={styles.locationPanel}>
          {!!signal.manualLocation && <Text style={styles.locationText}>Place hint: {signal.manualLocation}</Text>}
          {!!signal.location && (
            <Text style={styles.locationText}>
              GPS: {signal.location.latitude.toFixed(4)}, {signal.location.longitude.toFixed(4)}
            </Text>
          )}
        </View>
      )}
      {!compact && onChangeStatus && (
        <View style={styles.actionRowWrap}>
          {(['acknowledged', 'in_progress', 'resolved'] as SignalStatus[]).map((status) => (
            <Pressable key={status} style={styles.miniAction} onPress={() => onChangeStatus(signal, status)}>
              <Text style={styles.miniActionText}>{statusLabel(status)}</Text>
            </Pressable>
          ))}
        </View>
      )}
      {children}
    </View>
  );
}
