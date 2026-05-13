import NetInfo from '@react-native-community/netinfo';
import TcpSocket from 'react-native-tcp-socket';
import type Socket from 'react-native-tcp-socket/lib/types/Socket';
import type Server from 'react-native-tcp-socket/lib/types/Server';

import type { Account, MeshPeer, MeshRuntimeState, Signal } from '../types';
import { logError, logStep } from '../utils/logger';
import { addSyncLog, loadSignals, mergeIncomingSignal } from './database';
import { notifyIncomingSignal, notifyStatusUpdate } from './notifications';

type WirePacket =
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
      role?: Account['role'];
      name?: string;
      signals: Signal[];
      sentAt: number;
    };

type Listener = (state: MeshRuntimeState) => void;
type RefreshSignals = () => void;

const DEFAULT_PORT = 45454;

class LanTransport {
  private server: Server | null = null;
  private sockets = new Map<string, Socket>();
  private buffers = new Map<string, string>();
  private nodeId = '';
  private account: Account | null = null;
  private refreshSignals: RefreshSignals | null = null;
  private listeners = new Set<Listener>();
  private state: MeshRuntimeState = {
    serverRunning: false,
    nearbyRunning: false,
    port: DEFAULT_PORT,
    connectedPeers: [],
    nearbyPeers: [],
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
    return this.state.connectedPeers.filter((peer) => peer.role === 'admin');
  }

  async startServer(port = DEFAULT_PORT) {
    if (this.server) return;
    logStep('lan', 'auto fallback server start requested', { port });

    const netInfo = await NetInfo.fetch();
    const ipAddress =
      netInfo.details && 'ipAddress' in netInfo.details && typeof netInfo.details.ipAddress === 'string'
        ? netInfo.details.ipAddress
        : undefined;

    this.server = TcpSocket.createServer((socket) => this.registerSocket(socket));
    this.server.on('error', (error) => {
      logError('lan', 'server error', error);
      this.patchState({ lastError: error.message });
      addSyncLog(`Mesh server error: ${error.message}`);
    });
    this.server.on('close', () => {
      logStep('lan', 'server closed');
      this.patchState({ serverRunning: false });
      addSyncLog('Mesh server stopped');
    });

    this.server.listen({ port, host: '0.0.0.0', reuseAddress: true }, () => {
      this.patchState({ serverRunning: true, localIp: ipAddress, port, lastError: undefined });
      logStep('lan', 'server listening', { ipAddress, port });
      addSyncLog(`Mesh server listening on ${ipAddress ?? 'device IP'}:${port}`);
    });
  }

  stopServer() {
    this.server?.close();
    this.server = null;
    this.sockets.forEach((socket) => socket.destroy());
    this.sockets.clear();
    this.buffers.clear();
    this.updatePeerState([]);
    this.patchState({ serverRunning: false, connectedPeers: [] });
  }

  connectToPeer(host: string, port = DEFAULT_PORT) {
    const cleanHost = host.trim();
    logStep('lan', 'manual peer connect requested', { host: cleanHost, port });
    if (!cleanHost) {
      this.patchState({ lastError: 'Enter the other device LAN IP first.' });
      return;
    }

    const socket = TcpSocket.createConnection({ host: cleanHost, port, interface: 'wifi', connectTimeout: 7000 }, () => {
      this.registerSocket(socket, `${cleanHost}:${port}`);
      this.sendHello(socket);
      this.sendSignals(socket);
      addSyncLog(`Connected to peer ${cleanHost}:${port}`);
    });

    socket.on('error', (error) => {
      logError('lan', 'peer connection failed', error);
      this.patchState({ lastError: error.message });
      addSyncLog(`Peer connection failed ${cleanHost}:${port}: ${error.message}`);
    });
  }

  syncAllPeers() {
    logStep('lan', 'syncing all peers', { peers: this.sockets.size });
    this.sockets.forEach((socket) => this.sendSignals(socket));
    addSyncLog(`Sync pushed to ${this.sockets.size} peer(s)`);
  }

  private registerSocket(socket: Socket, explicitKey?: string) {
    socket.setEncoding('utf8');
    socket.setNoDelay(true);

    const key = explicitKey ?? `${socket.remoteAddress ?? 'peer'}:${socket.remotePort ?? Date.now()}`;
    this.sockets.set(key, socket);
    logStep('lan', 'socket registered', { key });
    this.upsertPeer({
      key,
      nodeId: key,
      role: 'user',
      name: 'Unknown peer',
      host: socket.remoteAddress,
      port: socket.remotePort,
      lastSeenAt: Date.now(),
    });
    this.patchState({ lastError: undefined });

    socket.on('data', (data) => this.handleData(key, socket, String(data)));
    socket.on('close', () => {
      this.sockets.delete(key);
      this.buffers.delete(key);
      this.updatePeerState(this.state.connectedPeers.filter((peer) => peer.key !== key));
      addSyncLog(`Peer disconnected ${key}`);
      logStep('lan', 'peer disconnected', { key });
    });
    socket.on('error', (error) => {
      this.patchState({ lastError: error.message });
      logError('lan', 'socket error', error);
      addSyncLog(`Peer socket error ${key}: ${error.message}`);
    });

    this.sendHello(socket);
    this.sendSignals(socket);
  }

  private handleData(key: string, socket: Socket, chunk: string) {
    const nextBuffer = `${this.buffers.get(key) ?? ''}${chunk}`;
    const lines = nextBuffer.split('\n');
    this.buffers.set(key, lines.pop() ?? '');

    lines.filter(Boolean).forEach((line) => {
      try {
        const packet = JSON.parse(line) as WirePacket;
        this.handlePacket(packet, socket);
      } catch {
        logError('lan', 'malformed packet ignored');
        addSyncLog(`Ignored malformed packet from ${key}`);
      }
    });
  }

  private handlePacket(packet: WirePacket, socket: Socket) {
    if (packet.kind === 'hello') {
      logStep('lan', 'hello received', { nodeId: packet.nodeId, role: packet.role });
      const key = this.findSocketKey(socket);
      this.upsertPeer({
        key,
        nodeId: packet.nodeId,
        role: packet.role,
        name: packet.name,
        host: socket.remoteAddress,
        port: socket.remotePort,
        lastSeenAt: Date.now(),
      });
      addSyncLog(`Peer hello from ${packet.name} (${packet.role})`);
      this.sendSignals(socket);
      return;
    }

    let merged = 0;
    logStep('lan', 'signals packet received', { count: packet.signals.length, nodeId: packet.nodeId });
    packet.signals.forEach((signal) => {
      const accepted = mergeIncomingSignal({
        ...signal,
        hopCount: Math.min(signal.hopCount + 1, signal.ttl),
        status: signal.status === 'sent' ? 'received' : signal.status,
      });
      if (accepted) {
        merged += 1;
        if (this.account?.role === 'admin' && signal.senderNodeId !== this.nodeId) {
          notifyIncomingSignal(this.account, signal, packet.name ?? packet.nodeId);
        }
        if (this.account?.role === 'user' && signal.senderNodeId === this.nodeId) {
          notifyStatusUpdate(this.account, signal);
        }
      }
    });

    if (merged > 0) {
      addSyncLog(`Merged ${merged} signal(s) from ${packet.nodeId.slice(0, 8)}`);
      logStep('lan', 'signals merged', { merged });
      this.refreshSignals?.();
      this.syncAllPeers();
    }
  }

  private sendHello(socket: Socket) {
    this.sendPacket(socket, {
      kind: 'hello',
      nodeId: this.nodeId,
      role: this.account?.role ?? 'user',
      name: this.account?.name ?? 'Unknown node',
      sentAt: Date.now(),
    });
  }

  private sendSignals(socket: Socket) {
    this.sendPacket(socket, {
      kind: 'signals',
      nodeId: this.nodeId,
      role: this.account?.role ?? 'user',
      name: this.account?.name ?? 'Unknown node',
      signals: loadSignals().filter((signal) => signal.hopCount < signal.ttl),
      sentAt: Date.now(),
    });
  }

  private sendPacket(socket: Socket, packet: WirePacket) {
    socket.write(`${JSON.stringify(packet)}\n`, 'utf8');
  }

  private findSocketKey(socket: Socket) {
    for (const [key, value] of this.sockets.entries()) {
      if (value === socket) return key;
    }
    return `${socket.remoteAddress ?? 'peer'}:${socket.remotePort ?? Date.now()}`;
  }

  private upsertPeer(peer: MeshPeer) {
    const peers = this.state.connectedPeers.filter((existing) => existing.key !== peer.key && existing.nodeId !== peer.nodeId);
    peers.push(peer);
    this.updatePeerState(peers);
  }

  private updatePeerState(connectedPeers: MeshPeer[]) {
    this.patchState({ connectedPeers });
  }

  private patchState(patch: Partial<MeshRuntimeState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener(this.state));
  }
}

export const lanTransport = new LanTransport();
export const meshPort = DEFAULT_PORT;
