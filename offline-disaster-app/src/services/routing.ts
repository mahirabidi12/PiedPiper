import type { MeshPeer } from '../types';
import { logStep } from '../utils/logger';

export function routeToNearestAdmins(admins: MeshPeer[]) {
  logStep('routing', 'selecting admin peers automatically', { adminPeers: admins.length });
  if (!admins.length) {
    return { targetAdminIds: [], routeReason: 'No connected admin peer yet. Signal is queued for store-and-forward.' };
  }

  return {
    targetAdminIds: admins.map((admin) => admin.nodeId),
    routeReason:
      admins.length > 1
        ? `Automatically routed to ${admins.length} connected admin peers. User did not choose.`
        : `Automatically routed to connected admin ${admins[0].name}.`,
  };
}
