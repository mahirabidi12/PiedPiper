import { ScrollView, Text, View } from 'react-native';
import Svg, { Circle, Line, Rect, Text as SvgText } from 'react-native-svg';

import { SignalCard } from '../components/SignalCard';
import { safeZones } from '../constants/emergency';
import { colors, priorityColor } from '../theme/colors';
import { styles } from '../theme/styles';
import type { Role, Signal, SignalStatus } from '../types';

export function OfflineMap({
  role,
  signals,
  userId,
  onChangeStatus,
}: {
  role: Role;
  signals: Signal[];
  userId: string;
  onChangeStatus: (signal: Signal, status: SignalStatus) => void;
}) {
  const visibleSignals = role === 'admin' ? signals : signals.filter((signal) => signal.senderUserId === userId);
  const mapSignals = visibleSignals.filter((signal) => signal.location || signal.manualLocation);

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>{role === 'admin' ? 'Offline Admin Map' : 'My Offline Map'}</Text>
        <Text style={styles.cardCopy}>
          {role === 'admin'
            ? 'All received user signals appear as local pins. Tiles are adapter-ready for bundled offline packs.'
            : 'Users see their own signals and safety notices, not every civilian report.'}
        </Text>
        <View style={styles.mapFrame}>
          <Svg width="100%" height="280" viewBox="0 0 340 280">
            <Rect x="0" y="0" width="340" height="280" rx="24" fill="#050505" />
            <Line x1="28" y1="58" x2="312" y2="58" stroke="#202020" strokeWidth="4" />
            <Line x1="48" y1="218" x2="292" y2="36" stroke="#202020" strokeWidth="5" />
            <Line x1="168" y1="18" x2="168" y2="262" stroke="#202020" strokeWidth="3" />
            {safeZones.map((zone) => (
              <Circle
                key={zone.id}
                cx={zone.x}
                cy={zone.y}
                r={zone.type === 'Hazard' ? 15 : 11}
                fill={zone.type === 'Hazard' ? colors.high : zone.type === 'Shelter' ? colors.low : colors.blue}
                opacity={0.78}
              />
            ))}
            {mapSignals.map((signal, index) => (
              <Circle
                key={signal.id}
                cx={64 + ((index * 57) % 220)}
                cy={82 + ((index * 41) % 146)}
                r={signal.priority === 'critical' ? 15 : 11}
                fill={priorityColor(signal.priority)}
              />
            ))}
            <SvgText x="22" y="258" fill="#777" fontSize="11">
              OFFLINE REGION PACK: LOCAL DEMO GRID
            </SvgText>
          </Svg>
        </View>
        {mapSignals.length === 0 ? (
          <Text style={styles.emptyText}>No mapped signals yet.</Text>
        ) : (
          mapSignals.map((signal) => (
            <SignalCard key={signal.id} signal={signal} compact={role !== 'admin'} onChangeStatus={onChangeStatus} />
          ))
        )}
      </View>
    </ScrollView>
  );
}
