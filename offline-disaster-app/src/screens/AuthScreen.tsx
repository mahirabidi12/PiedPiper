import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import { colors } from '../theme/colors';
import { styles } from '../theme/styles';
import type { Role } from '../types';

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
        <Text style={styles.eyebrow}>Pure Offline Disaster Mesh</Text>
        <Text style={styles.heroTitle}>Help must move when the internet cannot.</Text>
        <Text style={styles.heroCopy}>Create or open a local SQLite account. User and Admin stay separated on-device.</Text>

        <View style={styles.roleRow}>
          {(['user', 'admin'] as Role[]).map((role) => (
            <Pressable
              key={role}
              onPress={() => setMode(role)}
              style={[styles.roleButton, mode === role && styles.roleButtonActive]}
            >
              <Text style={[styles.roleText, mode === role && styles.roleTextActive]}>
                {role === 'user' ? 'User: Send Signal' : 'Admin: Receive Signals'}
              </Text>
            </Pressable>
          ))}
        </View>

        <View style={styles.authCard}>
          <TextInput
            autoCapitalize="none"
            keyboardType="email-address"
            placeholder="email for this offline device"
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
            placeholder="name shown to responders"
            placeholderTextColor={colors.faint}
            value={name}
            onChangeText={setName}
            style={styles.input}
          />
          <Pressable style={styles.primaryButton} onPress={onContinue}>
            <Text style={styles.primaryButtonText}>Continue Offline</Text>
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
