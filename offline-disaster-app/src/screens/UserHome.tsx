import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { SignalCard } from '../components/SignalCard';
import { emergencyChips } from '../constants/emergency';
import { colors } from '../theme/colors';
import { styles } from '../theme/styles';
import type { LocationPoint, Signal } from '../types';

type EmergencyChip = (typeof emergencyChips)[number];

export function UserHome({
  selectedChip,
  setSelectedChip,
  story,
  setStory,
  manualLocation,
  setManualLocation,
  location,
  onRefreshLocation,
  onSend,
  signals,
}: {
  selectedChip: EmergencyChip;
  setSelectedChip: (chip: EmergencyChip) => void;
  story: string;
  setStory: (value: string) => void;
  manualLocation: string;
  setManualLocation: (value: string) => void;
  location?: LocationPoint;
  onRefreshLocation: () => Promise<LocationPoint | undefined>;
  onSend: (isImmediate?: boolean) => Promise<void>;
  signals: Signal[];
}) {
  return (
    <SafeAreaView edges={['top', 'left', 'right']} style={styles.screenSafe}>
      <ScrollView contentContainerStyle={styles.screen}>
        <Pressable style={styles.sosButton} onPress={() => onSend(true)}>
          <Text style={styles.sosText}>SOS</Text>
          <Text style={styles.sosSubtext}>Send immediate rescue signal</Text>
        </Pressable>

        <View style={styles.card}>
          <Text style={styles.sectionTitle}>What do you need?</Text>
          <Text style={styles.cardCopy}>Tap one. No forms, no admin selection.</Text>
          <View style={styles.chipGrid}>
            {emergencyChips.map((chip) => (
              <Pressable
                key={chip.label}
                onPress={() => setSelectedChip(chip)}
                style={[styles.chip, selectedChip.label === chip.label && styles.chipActive]}
              >
                <Text style={[styles.chipText, selectedChip.label === chip.label && styles.chipTextActive]}>
                  {chip.label}
                </Text>
              </Pressable>
            ))}
          </View>
          <TextInput
            multiline
            placeholder="Say it naturally: 3 people trapped near school, need oxygen..."
            placeholderTextColor={colors.faint}
            value={story}
            onChangeText={setStory}
            style={[styles.input, styles.storyInput]}
          />
          <TextInput
            placeholder="Optional place hint if GPS is weak"
            placeholderTextColor={colors.faint}
            value={manualLocation}
            onChangeText={setManualLocation}
            style={styles.input}
          />
          <View style={styles.actionRow}>
            <Pressable style={styles.secondaryButton} onPress={onRefreshLocation}>
              <Text style={styles.secondaryButtonText}>{location ? 'Location Ready' : 'Use My Location'}</Text>
            </Pressable>
            <Pressable style={styles.primaryButtonCompact} onPress={() => onSend(false)}>
              <Text style={styles.primaryButtonText}>Send Signal</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.card}>
          <Text style={styles.sectionTitle}>My Signals</Text>
          {signals.length === 0 ? (
            <Text style={styles.cardCopy}>No signals yet. SOS works even before a nearby admin is discovered.</Text>
          ) : (
            signals.map((signal) => <SignalCard key={signal.id} signal={signal} compact />)
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
