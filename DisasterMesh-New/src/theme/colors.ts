import type { Priority } from '../types';

export const colors = {
  black: '#000000',
  surface: '#070707',
  surfaceSoft: '#101010',
  surfaceLift: '#181818',
  panel: '#090909',
  panel2: '#111111',
  panel3: '#171717',
  text: '#f7f7f7',
  muted: '#a3a3a3',
  faint: '#555555',
  border: '#242424',
  critical: '#ff2d2d',
  criticalSoft: '#260707',
  responderGreen: '#3ddc97',
  responderSoft: '#092417',
  authorityAmber: '#ffd166',
  authoritySoft: '#2b2108',
  meshBlue: '#6ea8ff',
  meshSoft: '#0b1829',
  high: '#ff7a18',
  medium: '#ffd166',
  low: '#3ddc97',
  blue: '#6ea8ff',
  purple: '#b892ff',
};

export function priorityColor(priority: Priority) {
  return colors[priority];
}
