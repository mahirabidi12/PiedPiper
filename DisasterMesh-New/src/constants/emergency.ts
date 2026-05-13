import type { Category } from '../types';

export const emergencyChips: { label: string; category: Category; phrase: string }[] = [
  { label: 'SOS', category: 'sos', phrase: 'Immediate rescue needed' },
  { label: 'Medical', category: 'medical', phrase: 'Medical help needed' },
  { label: 'Trapped', category: 'trapped', phrase: 'People are trapped' },
  { label: 'Water', category: 'resource', phrase: 'Need drinking water' },
  { label: 'Evacuation', category: 'safety', phrase: 'Need evacuation support' },
  { label: 'Road blocked', category: 'infrastructure', phrase: 'Route is blocked' },
];

export const safeZones = [
  { id: 'shelter-1', label: 'School Shelter', x: 76, y: 88, type: 'Shelter' },
  { id: 'water-1', label: 'Water Point', x: 268, y: 112, type: 'Resource' },
  { id: 'hazard-1', label: 'Flooded Road', x: 212, y: 212, type: 'Hazard' },
];
