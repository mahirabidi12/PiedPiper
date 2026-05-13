import { NativeEventEmitter, NativeModules, PermissionsAndroid, Platform } from 'react-native';

import type { Account, MeshPeer, Signal } from '../types';
import { logError, logStep } from '../utils/logger';
import { addSyncLog, loadSignals, mergeIncomingSignal } from './database';
import { notifyIncomingSignal, notifyStatusUpdate } from './notifications';

type NearbyNativeModule = {
  start: (nodeName: string) => Promise<boolean>;
  stop: () => Promise<boolean>;
  send: (message: string) => Promise<boolean>;
};

type NearbyWirePacket =
  | {
      kind: 'hello';
      nodeId: string;
      role: Account['role'];
      name: string;
      sentAt: number;
    }
  | {
      kind: 'signals';
      nodeId: string;
      role: Account['role'];
      name: string;
      signals: Signal[];
      sentAt: number;
    };

type NearbyState = {
  running: boolean;
  peers: MeshPeer[];
  lastError?: string;
};

type Listener = (state: NearbyState) => void;
type RefreshSignals = () => void;

const nativeNearby = NativeModules.NearbyMesh as NearbyNativeModule | undefined;
const emitter = nativeNearby ? new NativeEventEmitter(NativeModules.NearbyMesh) : null;

class NearbyTransport {
  private nodeId = '';
  private account: Account | null = null;
  private refreshSignals: RefreshSignals | null = null;
  private listeners = new Set<Listener>();
  private subscriptions: { remove: () => void }[] = [];
  private broadcastTimer: ReturnType<typeof setTimeout> | null = null;
  private lastBroadcastAt = 0;
  private state: NearbyState = {
    running: false,
    peers: [],
  };

  subscribe(listener: Listener) {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  configure(nodeId: string, account: Account | null, refreshSignals: RefreshSignals) {
    this.nodeId = nodeId;
    this.account = account;
    this.refreshSignals = refreshSignals;
  }

  getAdminPeers() {
    return this.state.peers.filter((peer) => peer.role === 'admin' && peer.connected);
  }

  async start() {
    logStep('nearby', 'auto start requested');
    if (Platform.OS !== 'android' || !nativeNearby || !emitter) {
      logError('nearby', 'native module unavailable');
      this.patchState({ lastError: 'Nearby Connections is only available in the Android native build.' });
      return false;
    }

    const hasPermissions = await requestNearbyPermissions();
    if (!hasPermissions) {
      this.patchState({ lastError: 'Bluetooth, nearby devices, and location permissions are required for offline discovery.' });
      logError('nearby', 'runtime permissions denied');
      return false;
    }

    this.ensureSubscriptions();
    const nodeName = `${this.account?.role ?? 'user'}:${this.account?.name ?? 'Unknown'}:${this.nodeId}`;
    try {
      await nativeNearby.start(nodeName);
      this.patchState({ running: true, lastError: undefined });
      logStep('nearby', 'advertising and discovery active', { nodeName });
      await this.broadcast();
      return true;
    } catch (error) {
      logError('nearby', 'start failed', error);
      this.patchState({ running: false, lastError: error instanceof Error ? error.message : 'Nearby start failed' });
      return false;
    }
  }

  async stop() {
    await nativeNearby?.stop();
    this.patchState({ running: false, peers: [] });
  }

  async broadcast() {
    if (!nativeNearby) return false;
    logStep('nearby', 'broadcasting hello and signal bundle', { signals: loadSignals().length });
    await this.sendPacket({
      kind: 'hello',
      nodeId: this.nodeId,
      role: this.account?.role ?? 'user',
      name: this.account?.name ?? 'Unknown node',
      sentAt: Date.now(),
    });
    return this.sendPacket({
      kind: 'signals',
      nodeId: this.nodeId,
      role: this.account?.role ?? 'user',
      name: this.account?.name ?? 'Unknown node',
      signals: loadSignals().filter((signal) => signal.hopCount < signal.ttl),
      sentAt: Date.now(),
    });
  }

  private ensureSubscriptions() {
    if (!emitter || this.subscriptions.length > 0) return;

    this.subscriptions = [
      emitter.addListener('NearbyMeshPeer', (event) => {
        logStep('nearby', 'peer event', event);
        const peer = parsePeer(event.endpointId, event.name, event.connected);
        this.upsertPeer(peer);
        if (peer.connected) {
          this.scheduleBroadcast();
        }
      }),
      emitter.addListener('NearbyMeshDisconnected', (event) => {
        logStep('nearby', 'peer disconnected', event);
        this.patchState({ peers: this.state.peers.filter((peer) => peer.key !== event.endpointId) });
      }),
      emitter.addListener('NearbyMeshStatus', (event) => {
        logStep('nearby', 'native status', event);
        addSyncLog(`Nearby: ${event.message}`);
      }),
      emitter.addListener('NearbyMeshPayload', (event) => {
        logStep('nearby', 'payload received', { bytes: event.message?.length ?? 0 });
        this.handleMessage(event.message);
      }),
    ];
  }

  private async sendPacket(packet: NearbyWirePacket) {
    const sent = await nativeNearby?.send(JSON.stringify(packet));
    logStep('nearby', 'send packet result', { kind: packet.kind, sent });
    addSyncLog(sent ? `Nearby sent ${packet.kind}` : `Nearby queued ${packet.kind}; no peer connected yet`);
    return !!sent;
  }

  private handleMessage(message: string) {
    try {
      const packet = JSON.parse(message) as NearbyWirePacket;
      logStep('nearby', 'handling packet', { kind: packet.kind, nodeId: packet.nodeId, role: packet.role });
      this.upsertPeer({
        key: packet.nodeId,
        nodeId: packet.nodeId,
        role: packet.role,
        name: packet.name,
        connected: true,
        lastSeenAt: Date.now(),
      });

      if (packet.kind === 'hello') {
        return;
      }

      let merged = 0;
      packet.signals.forEach((signal) => {
        const accepted = mergeIncomingSignal({
          ...signal,
          hopCount: Math.min(signal.hopCount + 1, signal.ttl),
          status: signal.status === 'sent' ? 'received' : signal.status,
        });
        if (accepted) {
          merged += 1;
          if (this.account?.role === 'admin' && signal.senderNodeId !== this.nodeId) {
            notifyIncomingSignal(this.account, signal, packet.name);
          }
          if (this.account?.role === 'user' && signal.senderNodeId === this.nodeId) {
            notifyStatusUpdate(this.account, signal);
          }
        }
      });

      if (merged > 0) {
        addSyncLog(`Nearby merged ${merged} signal(s) from ${packet.name}`);
        this.refreshSignals?.();
        this.scheduleBroadcast();
      }
    } catch {
      logError('nearby', 'malformed payload');
      addSyncLog('Nearby ignored malformed payload');
    }
  }

  private upsertPeer(peer: MeshPeer) {
    const peers = this.state.peers.filter((existing) => existing.key !== peer.key && existing.nodeId !== peer.nodeId);
    peers.push(peer);
    this.patchState({ peers });
  }

  private patchState(patch: Partial<NearbyState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener(this.state));
  }

  private scheduleBroadcast() {
    if (this.broadcastTimer) return;

    const waitMs = Math.max(0, 1500 - (Date.now() - this.lastBroadcastAt));
    this.broadcastTimer = setTimeout(() => {
      this.broadcastTimer = null;
      this.lastBroadcastAt = Date.now();
      void this.broadcast();
    }, waitMs);
  }
}

async function requestNearbyPermissions() {
  if (Platform.OS !== 'android') return false;

  const permissions = [
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
    PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
  ];

  if (Platform.Version >= 31) {
    permissions.push(
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
    );
  }

  if (Platform.Version >= 33 && PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES) {
    permissions.push(PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES);
  }

  const result = await PermissionsAndroid.requestMultiple(permissions);
  const granted = Object.values(result).every((value) => value === PermissionsAndroid.RESULTS.GRANTED);
  logStep('nearby', 'runtime permission result', result);
  return granted;
}

function parsePeer(endpointId: string, endpointName?: string, connected?: boolean): MeshPeer {
  const parts = (endpointName ?? '').split(':');
  const role = parts[0] === 'admin' ? 'admin' : 'user';
  const name = parts[1] || endpointName || 'Nearby peer';
  const nodeId = parts[2] || endpointId;

  return {
    key: endpointId,
    nodeId,
    role,
    name,
    lastSeenAt: Date.now(),
    host: connected ? 'Nearby Connections' : 'Nearby discovered',
    connected: !!connected,
  };
}

export const nearbyTransport = new NearbyTransport();
