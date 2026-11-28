import { describe, expect, it } from 'vitest';
import { formatDuration, shareOfSlowest, windowStart } from './timing';

const words = { under: 'under 1 s', unit: (unit: string, n: number) => `${String(n)} ${{ day: 'd', hour: 'h', minute: 'min', second: 's' }[unit] ?? unit}` };

describe('step timing figures', () => {
  it('a duration shows its two largest units in the reader language', () => {
    expect(formatDuration(130_000, words)).toBe('2 min 10 s');
    expect(formatDuration(9_900_000, words)).toBe('2 h 45 min');
    expect(formatDuration(500, words)).toBe('under 1 s');
  });

  it('a window starts on a whole hour so the query holds still', () => {
    const now = Date.parse('2026-11-21T10:52:31Z');
    expect(windowStart(now, '24h')).toBe('2026-11-20T10:00:00.000Z');
    expect(windowStart(now, '7d')).toBe('2026-11-14T10:00:00.000Z');
    expect(windowStart(now, '30d')).toBe('2026-10-22T10:00:00.000Z');
  });

  it('the bar share is a whole percent of the slowest step and never above 100', () => {
    expect(shareOfSlowest(4_950_000, 9_900_000)).toBe(50);
    expect(shareOfSlowest(null, 9_900_000)).toBe(0);
    expect(shareOfSlowest(10, 0)).toBe(0);
  });
});
