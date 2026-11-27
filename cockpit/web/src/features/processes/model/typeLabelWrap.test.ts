import { describe, expect, it } from 'vitest';
import { layoutGraph, wrapWords, TASK_WIDTH, TYPE_INDENT } from './layout';

describe('type label wrap (#609)', () => {
  it('breaks a hyphenated type after the hyphen, never mid-word', () => {
    expect(wrapWords('Sub-process', 74, 6.8)).toEqual(['Sub-', 'process']);
    expect(wrapWords('Ad-hoc sub-process', 74, 6.8)).toEqual(['Ad-hoc', 'sub-', 'process']);
  });

  it('keeps a word wider than the line whole', () => {
    expect(wrapWords('Compensation', 20, 6.8)).toEqual(['Compensation']);
  });

  it('lays a sub-process box out with whole words on every type line', () => {
    const layout = layoutGraph({ nodes: [{ id: 's', name: 'Review', type: 'subProcess' }], edges: [] }, () => 'Sub-process');
    const node = layout.nodes.find((n) => n.id === 's');
    expect(node?.typeLines).toEqual(['Sub-', 'process']);
    expect(TASK_WIDTH - TYPE_INDENT).toBeGreaterThan(0);
  });
});
