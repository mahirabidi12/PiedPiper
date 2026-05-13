import { Pressable, ScrollView, Text, View } from 'react-native';

import { Metric } from '../components/Metric';
import { colors } from '../theme/colors';
import { styles } from '../theme/styles';
import type { MeshRuntimeState, Signal, SyncLog } from '../types';
import { formatTime } from '../utils/format';

export function MeshStatus({
  logs,
  signals,
  meshState,
  onSyncPeers,
}: {
  logs: SyncLog[];
  signals: Signal[];
  meshState: MeshRuntimeState;
  onSyncPeers: () => void;
}) {
  const packets = signals.filter((signal) => signal.hopCount < signal.ttl);

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Offline Radio Mesh</Text>
        <Text style={styles.cardCopy}>
          Discovery and sync run automatically in the background. The app tries Nearby Connections first, then keeps
          LAN store-and-forward available internally as a fallback. No one needs to choose a transport or admin.
        </Text>
        <View style={styles.commandGrid}>
          <Metric label="Nearby" value={meshState.nearbyPeers.length} color={colors.blue} />
          <Metric label="LAN" value={meshState.connectedPeers.length} color={colors.purple} />
          <Metric label="Packets" value={packets.length} color={colors.low} />
        </View>
        <View style={styles.actionRowWrap}>
          <Pressable style={styles.secondaryButton} onPress={onSyncPeers}>
            <Text style={styles.secondaryButtonText}>Retry Sync Now</Text>
          </Pressable>
        </View>
        {!!meshState.nearbyError && <Text style={styles.locationText}>Nearby error: {meshState.nearbyError}</Text>}
        <Text style={styles.settingLine}>
          Nearby: {meshState.nearbyRunning ? 'active' : 'starting'} • fallback:{' '}
          {meshState.serverRunning ? 'ready' : 'starting'}
        </Text>
        {!!meshState.lastError && <Text style={styles.locationText}>Fallback error: {meshState.lastError}</Text>}
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Discovered Peers</Text>
        {meshState.nearbyPeers.length === 0 ? (
          <Text style={styles.emptyText}>Searching automatically. Keep Bluetooth, WiFi, and Location enabled.</Text>
        ) : (
          meshState.nearbyPeers.map((peer) => (
            <View key={peer.key} style={styles.peerRow}>
              <View>
                <Text style={styles.peerName}>{peer.name}</Text>
                <Text style={styles.signalMeta}>
                  {peer.role.toUpperCase()} • {peer.host ?? 'Nearby'} • {formatTime(peer.lastSeenAt)}
                </Text>
              </View>
              <Text style={styles.statusPill}>{peer.role.toUpperCase()}</Text>
            </View>
          ))
        )}
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Fallback Peers</Text>
        {meshState.connectedPeers.length === 0 ? (
          <Text style={styles.emptyText}>No fallback peer connected. Nearby remains the primary path.</Text>
        ) : (
          meshState.connectedPeers.map((peer) => (
            <View key={peer.key} style={styles.peerRow}>
              <View>
                <Text style={styles.peerName}>{peer.name}</Text>
                <Text style={styles.signalMeta}>
                  {peer.role.toUpperCase()} • {peer.host ?? peer.key} • {formatTime(peer.lastSeenAt)}
                </Text>
              </View>
              <Text style={styles.statusPill}>{peer.role.toUpperCase()}</Text>
            </View>
          ))
        )}
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Sync Log</Text>
        {logs.length === 0 ? (
          <Text style={styles.emptyText}>No sync events yet.</Text>
        ) : (
          logs.map((log) => (
            <Text key={`${log.created_at}-${log.message}`} style={styles.logLine}>
              {formatTime(log.created_at)} • {log.message}
            </Text>
          ))
        )}
      </View>
    </ScrollView>
  );
}
