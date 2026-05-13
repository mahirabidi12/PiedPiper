import { Text, View } from 'react-native';

import { styles } from '../theme/styles';
import type { Account } from '../types';

function roleTitle(role: Account['role']) {
  if (role === 'authority') return 'Authority Command Node';
  if (role === 'volunteer') return 'Volunteer Response Node';
  return 'Civilian Signal Node';
}

function screenTitle(role: Account['role']) {
  if (role === 'authority') return 'Disaster Mesh Authority';
  if (role === 'volunteer') return 'Field Response Mesh';
  return 'Send Help Offline';
}

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
        <Text style={styles.eyebrow}>{roleTitle(account.role)}</Text>
        <Text style={styles.title}>{screenTitle(account.role)}</Text>
      </View>
      <View style={styles.headerPill}>
        <Text style={styles.headerPillNumber}>{stats.peerCount}</Text>
        <Text style={styles.headerPillText}>peers</Text>
      </View>
    </View>
  );
}
