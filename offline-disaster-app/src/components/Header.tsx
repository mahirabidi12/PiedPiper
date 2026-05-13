import { Text, View } from 'react-native';

import { styles } from '../theme/styles';
import type { Account } from '../types';

export function Header({
  account,
  stats,
}: {
  account: Account;
  stats: { critical: number; queued: number; peerCount: number };
}) {
  return (
    <View style={styles.header}>
      <View>
        <Text style={styles.eyebrow}>{account.role === 'admin' ? 'Admin Command Node' : 'User Signal Node'}</Text>
        <Text style={styles.title}>{account.role === 'admin' ? 'Disaster Mesh Admin' : 'Send Help Offline'}</Text>
      </View>
      <View style={styles.headerPill}>
        <Text style={styles.headerPillNumber}>{stats.peerCount}</Text>
        <Text style={styles.headerPillText}>peers</Text>
      </View>
    </View>
  );
}
