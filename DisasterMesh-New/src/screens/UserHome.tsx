import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';

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
  onSend: (isImmediate?: boolean) => Promise<void>;
  signals: Signal[];
}) {
  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <View style={styles.homeHero}>
        <Text style={styles.eyebrow}>Home</Text>
      </View>

      <View style={styles.sosOrbit}>
        <View style={styles.orbitTopRow}>
          {emergencyChips.slice(1, 3).map((chip) => (
            <Pressable
              key={chip.label}
              onPress={() => setSelectedChip(chip)}
              style={[styles.orbitChip, selectedChip.label === chip.label && styles.orbitChipActive]}
            >
              <Text style={[styles.orbitChipText, selectedChip.label === chip.label && styles.orbitChipTextActive]}>
                {chip.label}
              </Text>
            </Pressable>
          ))}
        </View>
        <View style={styles.orbitMiddleRow}>
          <Pressable
            onPress={() => setSelectedChip(emergencyChips[3])}
            style={[styles.orbitChip, selectedChip.label === emergencyChips[3].label && styles.orbitChipActive]}
          >
            <Text style={[styles.orbitChipText, selectedChip.label === emergencyChips[3].label && styles.orbitChipTextActive]}>
              {emergencyChips[3].label}
            </Text>
          </Pressable>
          <Pressable style={styles.sosRoundButton} onPress={() => onSend(false)}>
            <Text style={styles.sosRoundText}>SOS</Text>
            <Text style={styles.sosRoundSubtext}>{selectedChip.label}</Text>
          </Pressable>
          <Pressable
            onPress={() => setSelectedChip(emergencyChips[4])}
            style={[styles.orbitChip, selectedChip.label === emergencyChips[4].label && styles.orbitChipActive]}
          >
            <Text style={[styles.orbitChipText, selectedChip.label === emergencyChips[4].label && styles.orbitChipTextActive]}>
              {emergencyChips[4].label}
            </Text>
          </Pressable>
        </View>
        <View style={styles.orbitBottomRow}>
          <Pressable
            onPress={() => setSelectedChip(emergencyChips[0])}
            style={[styles.orbitChip, selectedChip.label === emergencyChips[0].label && styles.orbitChipActive]}
          >
            <Text style={[styles.orbitChipText, selectedChip.label === emergencyChips[0].label && styles.orbitChipTextActive]}>
              {emergencyChips[0].label}
            </Text>
          </Pressable>
          <Pressable
            onPress={() => setSelectedChip(emergencyChips[5])}
            style={[styles.orbitChip, selectedChip.label === emergencyChips[5].label && styles.orbitChipActive]}
          >
            <Text style={[styles.orbitChipText, selectedChip.label === emergencyChips[5].label && styles.orbitChipTextActive]}>
              {emergencyChips[5].label}
            </Text>
          </Pressable>
        </View>
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Details</Text>
        <View style={styles.selectedReasonBar}>
          <Text style={styles.selectedReasonLabel}>Selected reason</Text>
          <Text style={styles.selectedReasonValue}>{selectedChip.label}</Text>
        </View>
        <TextInput
          multiline
          placeholder="Describe what happened: 3 people trapped near school, need oxygen..."
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
        <Text style={styles.locationText}>
          {location ? 'GPS cached for next signal. It will refresh during send.' : 'GPS will be requested automatically during send.'}
        </Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>My Signals</Text>
        {signals.length === 0 ? (
          <Text style={styles.cardCopy}>No signals yet. SOS works even before a nearby responder is discovered.</Text>
        ) : (
          signals.map((signal) => <SignalCard key={signal.id} signal={signal} compact />)
        )}
      </View>
    </ScrollView>
  );
}
