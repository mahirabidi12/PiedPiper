import type { SignalStatus } from '../types';

export function statusLabel(status: SignalStatus) {
  return status.replace('_', ' ').toUpperCase();
}

export function formatTime(timestamp: number) {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: 'short',
  }).format(new Date(timestamp));
}

export function parseJSON<T>(value: string | null | undefined, fallback: T): T {
  if (!value) return fallback;
  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}
