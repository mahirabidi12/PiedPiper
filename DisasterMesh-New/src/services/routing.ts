import type { MeshPeer } from '../types';
import { logStep } from '../utils/logger';

export function routeToResponders(responders: MeshPeer[]) {
  logStep('routing', 'selecting responder peers automatically', { responderPeers: responders.length });
  if (!responders.length) {
    return { targetResponderIds: [], routeReason: 'No connected responder peer yet. Signal is queued for store-and-forward.' };
  }

  return {
    targetResponderIds: responders.map((responder) => responder.nodeId),
    routeReason:
      responders.length > 1
        ? `Automatically routed to ${responders.length} connected responder peers. Civilian did not choose.`
        : `Automatically routed to connected responder ${responders[0].name}.`,
  };
}
