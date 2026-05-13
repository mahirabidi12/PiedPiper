import * as SQLite from 'expo-sqlite';

import type { Account, Signal, SignalStatus, SyncLog } from '../types';
import { statusLabel } from '../utils/format';
import { logStep } from '../utils/logger';
import { createId } from './identity';

const db = SQLite.openDatabaseSync('disaster_mesh.db');
const activeAccountKey = 'active_account_id';

function parseJSON<T>(value: string | null | undefined, fallback: T): T {
  if (!value) return fallback;
  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}

export function initDatabase() {
  logStep('database', 'initializing sqlite');
  db.execSync(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS accounts (
      id TEXT PRIMARY KEY NOT NULL,
      email TEXT UNIQUE NOT NULL,
      name TEXT NOT NULL,
      role TEXT NOT NULL,
      password_hash TEXT NOT NULL,
      salt TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS app_state (
      key TEXT PRIMARY KEY NOT NULL,
      value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS signals (
      id TEXT PRIMARY KEY NOT NULL,
      sender_node_id TEXT NOT NULL,
      sender_user_id TEXT NOT NULL,
      sender_name TEXT NOT NULL,
      sender_contact TEXT,
      message TEXT NOT NULL,
      category TEXT NOT NULL,
      priority TEXT NOT NULL,
      summary TEXT NOT NULL,
      needs TEXT NOT NULL,
      people_count INTEGER,
      manual_location TEXT,
      latitude REAL,
      longitude REAL,
      accuracy REAL,
      language TEXT NOT NULL,
      status TEXT NOT NULL,
      ttl INTEGER NOT NULL,
      hop_count INTEGER NOT NULL,
      target_admin_ids TEXT NOT NULL,
      route_reason TEXT NOT NULL,
      assigned_volunteer_node_id TEXT,
      assigned_volunteer_name TEXT,
      assigned_by_authority_node_id TEXT,
      assigned_at INTEGER,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS seen_packets (
      id TEXT PRIMARY KEY NOT NULL,
      packet_type TEXT NOT NULL,
      seen_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sync_log (
      id TEXT PRIMARY KEY NOT NULL,
      message TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
  `);

  const nodeId = db.getFirstSync<{ value: string }>('SELECT value FROM app_state WHERE key = ?', ['node_id']);
  if (!nodeId) {
    db.runSync('INSERT INTO app_state (key, value) VALUES (?, ?)', ['node_id', createId('node')]);
  }

  migrateRoleNames();
  addColumnIfMissing('signals', 'assigned_volunteer_node_id', 'TEXT');
  addColumnIfMissing('signals', 'assigned_volunteer_name', 'TEXT');
  addColumnIfMissing('signals', 'assigned_by_authority_node_id', 'TEXT');
  addColumnIfMissing('signals', 'assigned_at', 'INTEGER');
}

function addColumnIfMissing(table: string, column: string, type: string) {
  const columns = db.getAllSync<{ name: string }>(`PRAGMA table_info(${table})`);
  if (!columns.some((row) => row.name === column)) {
    db.execSync(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`);
  }
}

function normalizeRole(role: string): Account['role'] {
  if (role === 'admin') return 'authority';
  if (role === 'user') return 'civilian';
  if (role === 'volunteer' || role === 'authority' || role === 'civilian') return role;
  return 'civilian';
}

function migrateRoleNames() {
  db.runSync('UPDATE accounts SET role = ? WHERE role = ?', ['civilian', 'user']);
  db.runSync('UPDATE accounts SET role = ? WHERE role = ?', ['authority', 'admin']);
}

export function getNodeId() {
  const row = db.getFirstSync<{ value: string }>('SELECT value FROM app_state WHERE key = ?', ['node_id']);
  return row?.value ?? createId('node');
}

export function findAccountByEmail(email: string) {
  const row = db.getFirstSync<any>('SELECT * FROM accounts WHERE email = ?', [email]);
  return row ? { ...row, role: normalizeRole(row.role) } : null;
}

export function createAccount(account: Account, passwordHash: string, salt: string) {
  logStep('auth', 'creating offline account', { role: account.role, email: account.email });
  db.runSync(
    'INSERT INTO accounts (id, email, name, role, password_hash, salt, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    [account.id, account.email, account.name, account.role, passwordHash, salt, Date.now()],
  );
}

function rowToSignal(row: any): Signal {
  return {
    id: row.id,
    senderNodeId: row.sender_node_id,
    senderUserId: row.sender_user_id,
    senderName: row.sender_name,
    senderContact: row.sender_contact ?? undefined,
    message: row.message,
    category: row.category,
    priority: row.priority,
    summary: row.summary,
    needs: parseJSON<string[]>(row.needs, []),
    peopleCount: row.people_count ?? undefined,
    manualLocation: row.manual_location ?? undefined,
    location:
      row.latitude && row.longitude
        ? { latitude: row.latitude, longitude: row.longitude, accuracy: row.accuracy }
        : undefined,
    language: row.language,
    status: row.status,
    ttl: row.ttl,
    hopCount: row.hop_count,
    targetResponderIds: parseJSON<string[]>(row.target_admin_ids, []),
    routeReason: row.route_reason,
    assignedVolunteerNodeId: row.assigned_volunteer_node_id ?? undefined,
    assignedVolunteerName: row.assigned_volunteer_name ?? undefined,
    assignedByAuthorityNodeId: row.assigned_by_authority_node_id ?? undefined,
    assignedAt: row.assigned_at ?? undefined,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export function saveSignal(signal: Signal) {
  logStep('storage', 'saving signal', { id: signal.id, priority: signal.priority, status: signal.status });
  db.runSync(
    `INSERT OR REPLACE INTO signals (
      id, sender_node_id, sender_user_id, sender_name, sender_contact, message,
      category, priority, summary, needs, people_count, manual_location,
      latitude, longitude, accuracy, language, status, ttl, hop_count,
      target_admin_ids, route_reason, assigned_volunteer_node_id, assigned_volunteer_name,
      assigned_by_authority_node_id, assigned_at, created_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      signal.id,
      signal.senderNodeId,
      signal.senderUserId,
      signal.senderName,
      signal.senderContact ?? null,
      signal.message,
      signal.category,
      signal.priority,
      signal.summary,
      JSON.stringify(signal.needs),
      signal.peopleCount ?? null,
      signal.manualLocation ?? null,
      signal.location?.latitude ?? null,
      signal.location?.longitude ?? null,
      signal.location?.accuracy ?? null,
      signal.language,
      signal.status,
      signal.ttl,
      signal.hopCount,
      JSON.stringify(signal.targetResponderIds),
      signal.routeReason,
      signal.assignedVolunteerNodeId ?? null,
      signal.assignedVolunteerName ?? null,
      signal.assignedByAuthorityNodeId ?? null,
      signal.assignedAt ?? null,
      signal.createdAt,
      signal.updatedAt,
    ],
  );
}

export function mergeIncomingSignal(signal: Signal) {
  logStep('sync', 'merge incoming signal requested', { id: signal.id, status: signal.status });
  const existing = db.getFirstSync<{ id: string; updated_at: number }>('SELECT id, updated_at FROM signals WHERE id = ?', [
    signal.id,
  ]);

  if (existing && existing.updated_at >= signal.updatedAt) {
    logStep('sync', 'incoming signal ignored by dedupe', { id: signal.id });
    return false;
  }

  saveSignal(signal);
  logStep('sync', 'incoming signal merged', { id: signal.id });
  return true;
}

export function loadSignals() {
  return db
    .getAllSync('SELECT * FROM signals ORDER BY priority = "critical" DESC, created_at DESC')
    .map(rowToSignal);
}

export function updateSignalStatus(id: string, status: SignalStatus) {
  logStep('responder', 'updating signal status', { id, status });
  db.runSync('UPDATE signals SET status = ?, updated_at = ? WHERE id = ?', [status, Date.now(), id]);
  addSyncLog(`Signal ${id.slice(0, 8)} marked ${statusLabel(status)}`);
}

export function assignSignalToVolunteer(
  signalId: string,
  volunteer: { nodeId: string; name: string },
  authorityNodeId: string,
) {
  const assignedAt = Date.now();
  logStep('authority', 'assigning signal to volunteer', { signalId, volunteerNodeId: volunteer.nodeId });
  db.runSync(
    `UPDATE signals
     SET assigned_volunteer_node_id = ?, assigned_volunteer_name = ?,
         assigned_by_authority_node_id = ?, assigned_at = ?, updated_at = ?
     WHERE id = ?`,
    [volunteer.nodeId, volunteer.name, authorityNodeId, assignedAt, assignedAt, signalId],
  );
  addSyncLog(`Assigned ${signalId.slice(0, 8)} to ${volunteer.name}`);
}

export function loadActiveAccount() {
  const row = db.getFirstSync<{ value: string }>('SELECT value FROM app_state WHERE key = ?', [activeAccountKey]);
  if (!row?.value) return null;

  const account = db.getFirstSync<any>('SELECT id, email, name, role FROM accounts WHERE id = ?', [row.value]);
  return account ? ({ ...account, role: normalizeRole(account.role) } as Account) : null;
}

export function saveActiveAccount(account: Account) {
  db.runSync('INSERT OR REPLACE INTO app_state (key, value) VALUES (?, ?)', [activeAccountKey, account.id]);
}

export function clearActiveAccount() {
  db.runSync('DELETE FROM app_state WHERE key = ?', [activeAccountKey]);
}

export function addSyncLog(message: string) {
  db.runSync('INSERT INTO sync_log (id, message, created_at) VALUES (?, ?, ?)', [createId('log'), message, Date.now()]);
}

export function loadSyncLogs() {
  return db.getAllSync<SyncLog>('SELECT message, created_at FROM sync_log ORDER BY created_at DESC LIMIT 12');
}

export function hasSeenPacket(id: string) {
  return !!db.getFirstSync<{ id: string }>('SELECT id FROM seen_packets WHERE id = ?', [id]);
}

export function markPacketSeen(id: string, packetType: string) {
  db.runSync('INSERT INTO seen_packets (id, packet_type, seen_at) VALUES (?, ?, ?)', [id, packetType, Date.now()]);
}
