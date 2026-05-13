import { Pressable, ScrollView, Text, View } from 'react-native';

import { Metric } from '../components/Metric';
import { SignalCard } from '../components/SignalCard';
import { colors } from '../theme/colors';
import { styles } from '../theme/styles';
import type { Account, MeshPeer, Signal, SignalStatus } from '../types';
import { statusRank } from '../services/triage';

export function AdminHome({
  account,
  signals,
  volunteers,
  onChangeStatus,
  onAssignVolunteer,
}: {
  account: Account;
  signals: Signal[];
  volunteers?: MeshPeer[];
  onChangeStatus: (signal: Signal, status: SignalStatus) => void;
  onAssignVolunteer?: (signal: Signal, volunteer: MeshPeer) => void;
}) {
  const isAuthority = account.role === 'authority';
  const isVolunteer = account.role === 'volunteer';
  const active = [...signals]
    .filter((signal) => signal.status !== 'resolved')
    .sort((a, b) => statusRank(a.priority) - statusRank(b.priority) || b.createdAt - a.createdAt);
  const assignedToMe = active.filter((signal) => signal.assignedVolunteerNodeId === account.id || signal.assignedVolunteerName === account.name);
  const generalActive = isVolunteer ? active.filter((signal) => !assignedToMe.includes(signal)) : active;
  const critical = active.filter((signal) => signal.priority === 'critical');

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <View style={styles.dashboardHero}>
        <Text style={styles.eyebrow}>{isAuthority ? 'Authority Dashboard' : 'Volunteer Dashboard'}</Text>
        <Text style={styles.dashboardTitle}>{isAuthority ? 'Command active incidents.' : 'Respond to assigned and nearby incidents.'}</Text>
      </View>

      <View style={styles.commandGrid}>
        <Metric label="Critical" value={critical.length} color={colors.critical} />
        <Metric label="Open" value={active.length} color={colors.blue} />
        <Metric label={isAuthority ? 'Volunteers' : 'Assigned'} value={isAuthority ? volunteers?.length ?? 0 : assignedToMe.length} color={colors.medium} />
      </View>

      {isAuthority && (
        <View style={styles.card}>
        <Text style={styles.sectionTitle}>Online Volunteers</Text>
          {!volunteers?.length ? (
            <Text style={styles.emptyText}>No Volunteer online yet.</Text>
          ) : (
            volunteers.map((volunteer) => (
              <View key={volunteer.key} style={styles.peerRow}>
                <View>
                  <Text style={styles.peerName}>{volunteer.name}</Text>
                  <Text style={styles.signalMeta}>VOLUNTEER • {volunteer.host ?? 'Nearby'} </Text>
                </View>
                <Text style={styles.statusPill}>ONLINE</Text>
              </View>
            ))
          )}
        </View>
      )}

      {isVolunteer && (
        <View style={styles.card}>
          <Text style={styles.sectionTitle}>Assigned To Me</Text>
          {assignedToMe.length === 0 ? (
            <Text style={styles.emptyText}>No assigned incidents yet.</Text>
          ) : (
            assignedToMe.map((signal) => (
              <SignalCard key={signal.id} signal={signal} onChangeStatus={onChangeStatus} />
            ))
          )}
        </View>
      )}

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>{isAuthority ? 'Incident Command Queue' : 'Nearby Incidents'}</Text>
        {generalActive.length === 0 ? (
          <Text style={styles.emptyText}>{isAuthority ? 'No active signals. Authority node is ready.' : 'No nearby incidents yet.'}</Text>
        ) : (
          generalActive.map((signal) => (
            <SignalCard key={signal.id} signal={signal} onChangeStatus={onChangeStatus}>
              {isAuthority && !!volunteers?.length && onAssignVolunteer && (
                <View style={styles.actionRowWrap}>
                  {volunteers.map((volunteer) => (
                    <Pressable
                      key={volunteer.key}
                      style={[styles.miniAction, styles.assignAction]}
                      onPress={() => onAssignVolunteer(signal, volunteer)}
                    >
                      <Text style={styles.miniActionText}>Assign {volunteer.name}</Text>
                    </Pressable>
                  ))}
                </View>
              )}
            </SignalCard>
          ))
        )}
      </View>
    </ScrollView>
  );
}
