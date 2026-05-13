import { Pressable, ScrollView, Text, View } from 'react-native';

import { styles } from '../theme/styles';
import type { Account } from '../types';

export function Settings({ account, nodeId, onSignOut }: { account: Account; nodeId: string; onSignOut: () => void }) {
  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Offline Identity</Text>
        <Text style={styles.settingLine}>Name: {account.name}</Text>
        <Text style={styles.settingLine}>Email: {account.email}</Text>
        <Text style={styles.settingLine}>Role: {account.role.toUpperCase()}</Text>
        <Text style={styles.settingLine}>Node: {nodeId}</Text>
        <Text style={styles.cardCopy}>
          Credentials, active session, signals, deduplication state, and sync logs stay in this device SQLite database.
        </Text>
        <Pressable style={styles.secondaryButton} onPress={onSignOut}>
          <Text style={styles.secondaryButtonText}>Logout And Clear Saved Session</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}
