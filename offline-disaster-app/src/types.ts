export type Role = 'user' | 'admin';

export type Priority = 'critical' | 'high' | 'medium' | 'low';

export type SignalStatus = 'queued' | 'sent' | 'received' | 'acknowledged' | 'in_progress' | 'resolved';

export type Category = 'sos' | 'medical' | 'trapped' | 'infrastructure' | 'resource' | 'safety' | 'other';

export type TabId = 'home' | 'map' | 'mesh' | 'settings';

export type Account = {
  id: string;
  email: string;
  name: string;
  role: Role;
};

export type LocationPoint = {
  latitude: number;
  longitude: number;
  accuracy?: number | null;
};

export type Signal = {
  id: string;
  senderNodeId: string;
  senderUserId: string;
  senderName: string;
  senderContact?: string;
  message: string;
  category: Category;
  priority: Priority;
  summary: string;
  needs: string[];
  peopleCount?: number;
  manualLocation?: string;
  location?: LocationPoint;
  language: string;
  status: SignalStatus;
  ttl: number;
  hopCount: number;
  targetAdminIds: string[];
  routeReason: string;
  createdAt: number;
  updatedAt: number;
};

export type AdminNode = {
  id: string;
  name: string;
  distanceMeters: number;
  location: LocationPoint;
};

export type MeshPacket = {
  id: string;
  type: 'signal' | 'status' | 'admin_broadcast';
  payload: Signal;
  seenAt: number;
};

export type SyncLog = {
  message: string;
  created_at: number;
};

export type MeshPeer = {
  key: string;
  nodeId: string;
  role: Role;
  name: string;
  connected?: boolean;
  host?: string;
  port?: number;
  lastSeenAt: number;
};

export type MeshRuntimeState = {
  serverRunning: boolean;
  nearbyRunning: boolean;
  localIp?: string;
  port: number;
  connectedPeers: MeshPeer[];
  nearbyPeers: MeshPeer[];
  lastError?: string;
  nearbyError?: string;
};
