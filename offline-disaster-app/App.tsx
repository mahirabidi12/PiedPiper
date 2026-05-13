import * as Location from 'expo-location';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Alert, PermissionsAndroid, Platform, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import { Header } from './src/components/Header';
import { TabBar } from './src/components/TabBar';
import { emergencyChips } from './src/constants/emergency';
import { AdminHome } from './src/screens/AdminHome';
import { AuthScreen } from './src/screens/AuthScreen';
import { MeshStatus } from './src/screens/MeshStatus';
import { OfflineMap } from './src/screens/OfflineMap';
import { Settings } from './src/screens/Settings';
import { UserHome } from './src/screens/UserHome';
import {
  addSyncLog,
  createAccount,
  findAccountByEmail,
  getNodeId,
  initDatabase,
  loadSignals,
  loadSyncLogs,
  saveSignal,
  updateSignalStatus,
} from './src/services/database';
import { createId, hashPassword } from './src/services/identity';
import { gossipPacket } from './src/services/mesh';
import { nearbyTransport } from './src/services/nearbyTransport';
import { configureLocalNotifications } from './src/services/notifications';
import { routeToNearestAdmins } from './src/services/routing';
import { triageSignal } from './src/services/triage';
import { colors } from './src/theme/colors';
import { styles } from './src/theme/styles';
import type { Account, LocationPoint, MeshRuntimeState, Role, Signal, SignalStatus, SyncLog, TabId } from './src/types';
import { logError, logStep } from './src/utils/logger';

export default function App() {
  const [ready, setReady] = useState(false);
  const [account, setAccount] = useState<Account | null>(null);
  const [mode, setMode] = useState<Role>('user');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [signals, setSignals] = useState<Signal[]>([]);
  const [activeTab, setActiveTab] = useState<TabId>('home');
  const [selectedChip, setSelectedChip] = useState(emergencyChips[0]);
  const [story, setStory] = useState('');
  const [manualLocation, setManualLocation] = useState('');
  const [location, setLocation] = useState<LocationPoint | undefined>();
  const [syncLogs, setSyncLogs] = useState<SyncLog[]>([]);
  const [meshState, setMeshState] = useState<MeshRuntimeState>({
    serverRunning: false,
    nearbyRunning: false,
    port: 0,
    connectedPeers: [],
    nearbyPeers: [],
  });

  useEffect(() => {
    initDatabase();
    setSignals(loadSignals());
    setSyncLogs(loadSyncLogs());
    nearbyTransport.configure(getNodeId(), account, () => {
      setSignals(loadSignals());
      setSyncLogs(loadSyncLogs());
    });
    if (account) {
      logStep('mesh', 'auto transport startup after login', { role: account.role, nodeId: getNodeId() });
      void configureLocalNotifications();
      void requestMeshPermissions().then((permissionsReady) => {
        logStep('mesh', 'runtime permission result', { permissionsReady });
        if (!permissionsReady) {
          return false;
        }
        return nearbyTransport.start();
      }).then((started) => {
        logStep('mesh', 'nearby auto-start result', { started });
      });
    }
    const unsubscribeNearby = nearbyTransport.subscribe((nearbyState) =>
      setMeshState((current) => ({
        ...current,
        nearbyRunning: nearbyState.running,
        nearbyPeers: nearbyState.peers,
        nearbyError: nearbyState.lastError,
      })),
    );
    setReady(true);
    return () => {
      unsubscribeNearby();
    };
  }, [account]);

  async function requestMeshPermissions() {
    if (Platform.OS !== 'android') return true;

    const permissionSet = new Set<string>([
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
    ]);

    if (Number(Platform.Version) >= 31) {
      permissionSet.add(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN);
      permissionSet.add(PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE);
      permissionSet.add(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT);
    }

    if (Number(Platform.Version) >= 33) {
      permissionSet.add('android.permission.NEARBY_WIFI_DEVICES');
    }

    const results = await PermissionsAndroid.requestMultiple([...permissionSet] as Parameters<typeof PermissionsAndroid.requestMultiple>[0]);
    const denied = Object.entries(results).filter(([, result]) => result !== PermissionsAndroid.RESULTS.GRANTED);

    if (denied.length > 0) {
      logError('mesh', 'runtime mesh permissions denied', denied.map(([permission]) => permission));
      Alert.alert(
        'Mesh permission needed',
        'Bluetooth, Nearby WiFi, and location permissions are needed for offline phone-to-phone discovery.',
      );
      return false;
    }

    return true;
  }

  const stats = useMemo(() => {
    const critical = signals.filter((signal) => signal.priority === 'critical' && signal.status !== 'resolved').length;
    const queued = signals.filter((signal) => signal.status === 'queued' || signal.status === 'sent').length;
    return { critical, queued, peerCount: meshState.connectedPeers.length + meshState.nearbyPeers.length };
  }, [meshState.connectedPeers.length, meshState.nearbyPeers.length, signals]);

  async function refreshLocation() {
    const permission = await Location.requestForegroundPermissionsAsync();
    if (permission.status !== 'granted') {
      logError('location', 'location permission denied');
      Alert.alert('Location offline fallback', 'Location permission is off. You can still describe the place in one line.');
      return undefined;
    }

    const current = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    const point = {
      latitude: current.coords.latitude,
      longitude: current.coords.longitude,
      accuracy: current.coords.accuracy,
    };
    setLocation(point);
    logStep('location', 'captured device location', point);
    return point;
  }

  async function signInOrCreate() {
    const normalizedEmail = email.trim().toLowerCase();
    if (!normalizedEmail || password.length < 4) {
      Alert.alert('Keep it simple', 'Use an email and at least 4 characters for the offline password.');
      return;
    }

    const existing = findAccountByEmail(normalizedEmail);
    if (existing) {
      logStep('auth', 'offline login attempt', { email: normalizedEmail, role: mode });
      const candidate = await hashPassword(password, existing.salt);
      if (candidate !== existing.password_hash || existing.role !== mode) {
        logError('auth', 'offline login failed');
        Alert.alert('Offline login failed', 'Check the password or selected role for this device.');
        return;
      }
      setAccount({ id: existing.id, email: existing.email, name: existing.name, role: existing.role });
      logStep('auth', 'offline login successful', { role: existing.role });
      addSyncLog(`${existing.role.toUpperCase()} ${existing.email} signed in offline`);
      setSyncLogs(loadSyncLogs());
      return;
    }

    const salt = createId('salt');
    const id = createId(mode);
    const displayName = name.trim() || (mode === 'admin' ? 'Responder Admin' : 'Local User');
    const passwordHash = await hashPassword(password, salt);
    const nextAccount = { id, email: normalizedEmail, name: displayName, role: mode };
    createAccount(nextAccount, passwordHash, salt);
    setAccount(nextAccount);
    logStep('auth', 'offline account created', { role: mode, id });
    addSyncLog(`${mode.toUpperCase()} ${normalizedEmail} created offline account`);
    setSyncLogs(loadSyncLogs());
  }

  async function sendSignal(isImmediate = false) {
    if (!account) return;
    const point = location ?? (await refreshLocation());
    const phrase = isImmediate ? 'SOS. Immediate rescue needed.' : `${selectedChip.phrase}. ${story}`.trim();
    const triage = triageSignal(phrase, isImmediate ? 'sos' : selectedChip.category);
    const route = routeToNearestAdmins(nearbyTransport.getAdminPeers());
    logStep('signal', 'creating signal packet', { isImmediate, route: route.routeReason });
    const signal: Signal = {
      id: createId('signal'),
      senderNodeId: getNodeId(),
      senderUserId: account.id,
      senderName: account.name,
      message: phrase,
      category: triage.category,
      priority: triage.priority,
      summary: triage.summary,
      needs: triage.needs,
      peopleCount: triage.peopleCount,
      manualLocation: manualLocation.trim() || undefined,
      location: point,
      language: triage.language,
      status: route.targetAdminIds.length ? 'sent' : 'queued',
      ttl: 8,
      hopCount: 0,
      targetAdminIds: route.targetAdminIds,
      routeReason: route.routeReason,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    saveSignal(signal);
    gossipPacket(signal);
    logStep('signal', 'signal saved and gossip queued', { id: signal.id });
    void nearbyTransport.broadcast();
    setSignals(loadSignals());
    setSyncLogs(loadSyncLogs());
    setStory('');
    setManualLocation('');
    Alert.alert('Signal stored offline', route.routeReason);
  }

  function changeStatus(signal: Signal, status: SignalStatus) {
    updateSignalStatus(signal.id, status);
    logStep('admin', 'status changed by admin', { signalId: signal.id, status });
    setSignals(loadSignals());
    setSyncLogs(loadSyncLogs());
    void nearbyTransport.broadcast();
  }

  function syncPeers() {
    logStep('mesh', 'manual retry sync requested');
    void nearbyTransport.broadcast();
    setSyncLogs(loadSyncLogs());
  }

  if (!ready) {
    return (
      <SafeAreaProvider>
      <SafeAreaView edges={['top', 'right', 'bottom', 'left']} style={styles.safe}>
        <StatusBar style="light" backgroundColor={colors.black} />
        <View style={styles.centered}>
          <ActivityIndicator color={colors.critical} />
          <Text style={styles.muted}>Starting offline node...</Text>
        </View>
      </SafeAreaView>
      </SafeAreaProvider>
    );
  }

  if (!account) {
    return (
      <SafeAreaProvider>
      <SafeAreaView edges={['top', 'right', 'bottom', 'left']} style={styles.safe}>
        <StatusBar style="light" backgroundColor={colors.black} />
        <AuthScreen
          mode={mode}
          setMode={setMode}
          email={email}
          setEmail={setEmail}
          password={password}
          setPassword={setPassword}
          name={name}
          setName={setName}
          onContinue={signInOrCreate}
        />
      </SafeAreaView>
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
    <SafeAreaView edges={['top', 'right', 'bottom', 'left']} style={styles.safe}>
      <StatusBar style="light" backgroundColor={colors.black} />
      <View style={styles.appShell}>
        <Header account={account} stats={stats} />
        <View style={styles.content}>
          {activeTab === 'home' &&
            (account.role === 'user' ? (
              <UserHome
                selectedChip={selectedChip}
                setSelectedChip={setSelectedChip}
                story={story}
                setStory={setStory}
                manualLocation={manualLocation}
                setManualLocation={setManualLocation}
                location={location}
                onRefreshLocation={refreshLocation}
                onSend={sendSignal}
                signals={signals.filter((signal) => signal.senderUserId === account.id)}
              />
            ) : (
              <AdminHome signals={signals} onChangeStatus={changeStatus} />
            ))}
          {activeTab === 'map' && (
            <OfflineMap role={account.role} signals={signals} userId={account.id} onChangeStatus={changeStatus} />
          )}
          {activeTab === 'mesh' && (
            <MeshStatus
              logs={syncLogs}
              signals={signals}
              meshState={meshState}
              onSyncPeers={syncPeers}
            />
          )}
          {activeTab === 'settings' && (
            <Settings account={account} nodeId={getNodeId()} onSignOut={() => setAccount(null)} />
          )}
        </View>
        <TabBar activeTab={activeTab} setActiveTab={setActiveTab} role={account.role} />
      </View>
    </SafeAreaView>
    </SafeAreaProvider>
  );
}
