import { describe, expect, it } from 'vitest';
import { crowdedHeatLabels } from './heatLabels';
import type { PlacedEdge, PlacedNode } from './layout';

const node = (id: string, x: number, y: number): PlacedNode => ({ id, type: 'task', name: id, shape: 'task', x, y, width: 120, height: 60, lines: [id], typeLines: [] });
const figures = (...ids: string[]) => new Map(ids.map((id) => [id, '2 h 45 min']));

describe('TR_UI_688_heat_figures_keep_clear', () => {
  it('keeps a figure that has room', () => {
    expect(crowdedHeatLabels([node('a', 0, 0), node('b', 300, 0)], [], figures('a', 'b')).size).toBe(0);
  });
  it('holds back a figure that would sit on the step below', () => {
    expect([...crowdedHeatLabels([node('a', 0, 0), node('b', 0, 66)], [], figures('a'))]).toEqual(['a']);
  });
  it('holds back a figure on a flow line', () => {
    const edge = { id: 'e', source: 'a', target: 'b', points: [{ x: 60, y: 60 }, { x: 60, y: 160 }] } as unknown as PlacedEdge;
    expect([...crowdedHeatLabels([node('a', 0, 0)], [edge], figures('a'))]).toEqual(['a']);
  });
  it('holds back the second of two figures that touch', () => {
    expect([...crowdedHeatLabels([node('a', 0, 0), node('b', 60, 0)], [], figures('a', 'b'))]).toEqual(['b']);
  });
});
