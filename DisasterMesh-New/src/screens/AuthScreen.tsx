import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import { colors } from '../theme/colors';
import { styles } from '../theme/styles';
import type { Role } from '../types';

const roleOptions: { role: Role; title: string; subtitle: string; color: string; surface: string }[] = [
  { role: 'civilian', title: 'Civilian', subtitle: 'Request rescue and track response', color: colors.critical, surface: colors.criticalSoft },
  { role: 'volunteer', title: 'Volunteer', subtitle: 'Accept incidents from command', color: colors.responderGreen, surface: colors.responderSoft },
  { role: 'authority', title: 'Authority', subtitle: 'Assign teams and coordinate', color: colors.authorityAmber, surface: colors.authoritySoft },
];

export function AuthScreen({
  mode,
  setMode,
  email,
  setEmail,
  password,
  setPassword,
  name,
  setName,
  onContinue,
}: {
  mode: Role;
  setMode: (role: Role) => void;
  email: string;
  setEmail: (value: string) => void;
  password: string;
  setPassword: (value: string) => void;
  name: string;
  setName: (value: string) => void;
  onContinue: () => Promise<void>;
}) {
  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.keyboard}>
      <ScrollView contentContainerStyle={styles.authScreen}>
        <View style={styles.authHero}>
          <View style={styles.meshMark}>
            <View style={styles.meshLine} />
            <View style={styles.meshNodeSmallRow}>
              <View style={styles.meshNodeSmall} />
              <View style={styles.meshNodeSmall} />
              <View style={styles.meshNodeSmall} />
            </View>
            <View style={styles.meshNodeLarge}>
              <Text style={styles.meshNodeText}>DM</Text>
            </View>
          </View>
          <Text style={styles.eyebrow}>Offline Emergency Network</Text>
          <Text style={styles.heroTitle}>Disaster Mesh</Text>
        </View>

        <View style={styles.roleRow}>
          {roleOptions.map((option) => (
            <Pressable
              key={option.role}
              onPress={() => setMode(option.role)}
              style={[
                styles.roleButton,
                mode === option.role && styles.roleButtonActive,
                mode === option.role && { borderColor: option.color, backgroundColor: option.surface },
              ]}
            >
              <View style={[styles.roleAccent, { backgroundColor: option.color }]} />
              <Text style={[styles.roleText, mode === option.role && styles.roleTextActive]}>{option.title}</Text>
              <Text style={styles.roleSubtext}>{option.subtitle}</Text>
            </Pressable>
          ))}
        </View>

        <View style={styles.authCard}>
          <Text style={styles.sectionTitle}>Node Access</Text>
          <TextInput
            autoCapitalize="none"
            keyboardType="email-address"
            placeholder="node email"
            placeholderTextColor={colors.faint}
            value={email}
            onChangeText={setEmail}
            style={styles.input}
          />
          <TextInput
            placeholder="offline password"
            placeholderTextColor={colors.faint}
            value={password}
            onChangeText={setPassword}
            secureTextEntry
            style={styles.input}
          />
          <TextInput
            placeholder="display name"
            placeholderTextColor={colors.faint}
            value={name}
            onChangeText={setName}
            style={styles.input}
          />
          <Pressable style={styles.primaryButton} onPress={onContinue}>
            <Text style={styles.primaryButtonText}>Enter Offline Mesh</Text>
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
