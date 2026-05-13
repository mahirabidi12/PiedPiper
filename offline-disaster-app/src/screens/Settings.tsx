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
          Credentials, signals, deduplication state, and sync logs stay in this device SQLite database.
        </Text>
        <Pressable style={styles.secondaryButton} onPress={onSignOut}>
          <Text style={styles.secondaryButtonText}>Sign Out Locally</Text>
        </Pressable>
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Future Native Adapters</Text>
        <Text style={styles.settingLine}>Gemma GGUF: llama.cpp adapter boundary</Text>
        <Text style={styles.settingLine}>Discovery: mDNS / Nearby / WiFi Direct boundary</Text>
        <Text style={styles.settingLine}>Maps: bundled MBTiles / vector tile boundary</Text>
        <Text style={styles.settingLine}>Radio: ESP32 LoRa relay boundary</Text>
      </View>
    </ScrollView>
  );
}
