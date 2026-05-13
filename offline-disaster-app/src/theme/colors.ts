import type { Priority } from '../types';

export const colors = {
  black: '#000000',
  panel: '#090909',
  panel2: '#111111',
  panel3: '#171717',
  text: '#f7f7f7',
  muted: '#a3a3a3',
  faint: '#555555',
  border: '#242424',
  critical: '#ff2d2d',
  high: '#ff7a18',
  medium: '#ffd166',
  low: '#3ddc97',
  blue: '#6ea8ff',
  purple: '#b892ff',
};

export function priorityColor(priority: Priority) {
  return colors[priority];
}
