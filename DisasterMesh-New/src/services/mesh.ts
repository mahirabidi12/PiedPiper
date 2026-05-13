import type { MeshPacket, Signal } from '../types';
import { addSyncLog, hasSeenPacket, markPacketSeen } from './database';

export function gossipPacket(signal: Signal): MeshPacket | null {
  if (hasSeenPacket(signal.id) || signal.hopCount >= signal.ttl) return null;

  markPacketSeen(signal.id, 'signal');
  addSyncLog(`Gossip queued ${signal.id.slice(0, 8)} to ${signal.targetResponderIds.length || 'nearest'} responder route`);
  return {
    id: signal.id,
    type: 'signal',
    payload: { ...signal, hopCount: signal.hopCount + 1 },
    seenAt: Date.now(),
  };
}
