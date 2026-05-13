import type { Category, Priority } from '../types';
import { logStep } from '../utils/logger';

export function triageSignal(input: string, selectedCategory?: Category) {
  logStep('triage', 'classifying signal locally', { selectedCategory, length: input.length });
  const text = input.toLowerCase();
  const has = (words: string[]) => words.some((word) => text.includes(word));
  let category: Category = selectedCategory ?? 'other';

  if (category === 'other') {
    if (has(['trapped', 'stuck', 'stranded', 'buried'])) category = 'trapped';
    else if (has(['oxygen', 'insulin', 'blood', 'injured', 'doctor', 'medical', 'medicine'])) category = 'medical';
    else if (has(['bridge', 'road', 'collapsed', 'blocked', 'power'])) category = 'infrastructure';
    else if (has(['water', 'food', 'shelter', 'blanket', 'milk'])) category = 'resource';
    else if (has(['safe', 'evacuate', 'fire', 'flood', 'danger'])) category = 'safety';
    else if (has(['sos', 'help', 'rescue'])) category = 'sos';
  }

  const needs = [
    ['oxygen', 'oxygen'],
    ['insulin', 'insulin'],
    ['water', 'water'],
    ['food', 'food'],
    ['evacuation', 'evacuation'],
    ['shelter', 'shelter'],
    ['medicine', 'medicine'],
    ['rescue', 'rescue'],
  ]
    .filter(([needle]) => text.includes(needle))
    .map(([, label]) => label);

  const peopleMatch = text.match(/(\d+)\s*(people|person|children|elderly|trapped|stuck)/);
  const peopleCount = peopleMatch ? Number(peopleMatch[1]) : undefined;
  const criticalWords = ['trapped', 'oxygen', 'bleeding', 'buried', 'fire', 'drowning', 'collapsed'];
  const highWords = ['medical', 'injured', 'insulin', 'evacuation', 'stranded', 'flood'];
  const priority: Priority =
    category === 'sos' || has(criticalWords) || (peopleCount ?? 0) >= 3
      ? 'critical'
      : has(highWords) || category === 'medical'
        ? 'high'
        : category === 'infrastructure' || category === 'resource'
          ? 'medium'
          : 'low';

  const language = /[ಅ-ಹ]/.test(input) ? 'Kannada' : /[अ-ह]/.test(input) ? 'Hindi' : 'English';
  const clean = input.trim() || 'Immediate help requested';
  const summary = `${category.toUpperCase()} ${priority.toUpperCase()}: ${clean.slice(0, 96)}${clean.length > 96 ? '...' : ''}`;

  const result = { category, priority, needs: Array.from(new Set(needs)), peopleCount, language, summary };
  logStep('triage', 'classification complete', result);
  return result;
}

export function statusRank(priority: Priority) {
  return { critical: 0, high: 1, medium: 2, low: 3 }[priority];
}
