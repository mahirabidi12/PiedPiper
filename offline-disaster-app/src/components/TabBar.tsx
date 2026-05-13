import { Pressable, Text, View } from 'react-native';

import { styles } from '../theme/styles';
import type { Role, TabId } from '../types';

export function TabBar({
  activeTab,
  setActiveTab,
  role,
}: {
  activeTab: TabId;
  setActiveTab: (tab: TabId) => void;
  role: Role;
}) {
  const tabs = [
    { id: 'home' as const, label: role === 'admin' ? 'Command' : 'Signal' },
    { id: 'map' as const, label: 'Map' },
    { id: 'mesh' as const, label: 'Mesh' },
    { id: 'settings' as const, label: 'Node' },
  ];

  return (
    <View style={styles.tabBar}>
      {tabs.map((tab) => (
        <Pressable
          key={tab.id}
          style={[styles.tab, activeTab === tab.id && styles.tabActive]}
          onPress={() => setActiveTab(tab.id)}
        >
          <Text style={[styles.tabText, activeTab === tab.id && styles.tabTextActive]}>{tab.label}</Text>
        </Pressable>
      ))}
    </View>
  );
}
