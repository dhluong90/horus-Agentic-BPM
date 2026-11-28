import { describe, expect, it } from 'vitest';
import type { DefinitionLayout } from '../../../api/processes/schemas';
import { readBpmnGraph } from './bpmnGraph';
import { footprint, labelSize } from './edgeLabels';
import { layoutGraph, overlaps, symbolCentre } from './layout';

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
  <bpmn:process id="p" isExecutable="true">
    <bpmn:startEvent id="s" name="Start"/>
    <bpmn:userTask id="review" name="Review"/>
    <bpmn:boundaryEvent id="late" attachedToRef="review"><bpmn:timerEventDefinition><bpmn:timeDuration>P1D</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:boundaryEvent>
    <bpmn:exclusiveGateway id="ok" name="Fine?"/>
    <bpmn:endEvent id="yes" name="Yes"/>
    <bpmn:endEvent id="no" name="No"/>
    <bpmn:sequenceFlow id="a" sourceRef="s" targetRef="review"/>
    <bpmn:sequenceFlow id="b" sourceRef="review" targetRef="ok"/>
    <bpmn:sequenceFlow id="c" sourceRef="ok" targetRef="yes" name="Yes"/>
    <bpmn:sequenceFlow id="d" sourceRef="ok" targetRef="no"/>
  </bpmn:process>
</bpmn:definitions>`;

/** Top to bottom, which dagre's left to right layout never produces: the diagram's own coordinates must win. */
const DI: DefinitionLayout = {
  schemaVersion: 1,
  nodes: {
    s: { x: 282, y: 40, width: 36, height: 36 },
    review: { x: 242, y: 140, width: 116, height: 80 },
    ok: { x: 275, y: 300, width: 50, height: 50 },
    yes: { x: 182, y: 440, width: 36, height: 36 },
    no: { x: 382, y: 440, width: 36, height: 36 },
  },
  edges: { 'ok->yes': [{ x: 275, y: 325 }, { x: 200, y: 325 }, { x: 200, y: 440 }] },
};

describe('BPMN diagram coordinates', () => {
  it('CKP_209_a_step_the_diagram_places_is_drawn_at_its_coordinates', () => {
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const at = (id: string) => layout.nodes.find((node) => node.id === id);
    const review = at('review');
    const gateway = at('ok');
    const start = at('s');
    if (!review || !gateway || !start) throw new Error('a step is not placed');
    // Same column, one below the other: top to bottom.
    expect(symbolCentre(start).x).toBeCloseTo(review.x + review.width / 2, 5);
    expect(gateway.y).toBeGreaterThan(review.y + review.height);
    expect(review.width).toBe(116);
    expect(review.height).toBe(80);
    // The offsets between steps are the diagram's, whatever margin the drawing is shifted by.
    expect(symbolCentre(gateway).y - symbolCentre(start).y).toBeCloseTo(325 - 58, 5);
    expect(at('no')?.x).toBeGreaterThan(at('yes')?.x ?? Infinity);
  });

  it('CKP_209_a_flow_the_diagram_routes_follows_its_waypoints', () => {
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const routed = layout.edges.find((edge) => edge.source === 'ok' && edge.target === 'yes');
    expect(routed?.points).toHaveLength(3);
    const [first, second, third] = routed?.points ?? [];
    expect(second?.y).toBe(first?.y);
    expect(third?.x).toBe(second?.x);
    expect(third?.y).toBeGreaterThan(second?.y ?? Infinity);
    expect(routed?.labelAt).toBeDefined();
  });

  it('CKP_209_a_flow_the_diagram_leaves_out_is_drawn_between_the_steps_where_they_now_are', () => {
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const byId = new Map(layout.nodes.map((node) => [node.id, node]));
    const edge = layout.edges.find((candidate) => candidate.source === 's' && candidate.target === 'review');
    const start = byId.get('s');
    const review = byId.get('review');
    if (!edge || !start || !review) throw new Error('missing');
    // One above the other: a straight vertical flow, never a slanted one.
    expect(edge.points).toHaveLength(2);
    for (let i = 1; i < edge.points.length; i += 1) {
      const a = edge.points[i - 1];
      const b = edge.points[i];
      expect(Math.min(Math.abs((a?.x ?? 0) - (b?.x ?? 0)), Math.abs((a?.y ?? 0) - (b?.y ?? 0)))).toBeLessThan(1e-6);
    }
    expect(edge.points[edge.points.length - 1]?.y).toBeCloseTo(review.y, 5);
  });

  it('CKP_209_an_unrouted_flow_between_steps_in_different_rows_bends_once_and_never_slants', () => {
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const edge = layout.edges.find((candidate) => candidate.source === 'ok' && candidate.target === 'no');
    expect(edge?.points.length).toBeGreaterThanOrEqual(3);
    const points = edge?.points ?? [];
    for (let i = 1; i < points.length; i += 1) {
      expect(Math.min(Math.abs((points[i - 1]?.x ?? 0) - (points[i]?.x ?? 0)), Math.abs((points[i - 1]?.y ?? 0) - (points[i]?.y ?? 0)))).toBeLessThan(1e-6);
    }
  });

  it('CKP_209_a_boundary_event_the_diagram_leaves_out_travels_with_its_step', () => {
    const auto = layoutGraph(readBpmnGraph(XML));
    const placed = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const gap = (layout: typeof auto) => {
      const host = layout.nodes.find((node) => node.id === 'review');
      const late = layout.nodes.find((node) => node.id === 'late');
      if (!host || !late) throw new Error('missing');
      return { x: late.x - host.x, y: late.y - host.y };
    };
    expect(gap(placed)).toEqual(gap(auto));
  });

  it('CKP_209_a_diagram_that_lists_some_steps_keeps_the_rest_in_automatic_places', () => {
    const partial: DefinitionLayout = { schemaVersion: 1, nodes: { review: DI.nodes['review'] ?? { x: 0, y: 0 } } };
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: partial });
    expect(layout.nodes).toHaveLength(6);
    expect(layout.nodes.every((node) => Number.isFinite(node.x) && Number.isFinite(node.y) && node.x >= 0 && node.y >= 0)).toBe(true);
    expect(layout.edges.every((edge) => edge.points.length >= 2)).toBe(true);
  });

  it('CKP_209_the_drawing_is_sized_to_the_diagram_and_no_steps_overlap', () => {
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    expect(layout.height).toBeGreaterThan(layout.width);
    for (const [index, a] of layout.nodes.entries()) {
      for (const b of layout.nodes.slice(index + 1)) {
        if (a.attachedTo === b.id || b.attachedTo === a.id) continue;
        expect(overlaps(a, b), `${a.id} overlaps ${b.id}`).toBe(false);
      }
    }
  });

  it('CKP_209_a_definition_with_no_diagram_is_laid_out_automatically_as_before', () => {
    const graph = readBpmnGraph(XML);
    expect(graph.stored).toBeUndefined();
    const layout = layoutGraph(graph);
    const byId = new Map(layout.nodes.map((node) => [node.id, node]));
    // dagre reads left to right: the end events are to the right of the start.
    expect(byId.get('yes')?.x).toBeGreaterThan(byId.get('s')?.x ?? Infinity);
    expect(layout.width).toBeGreaterThan(layout.height);
    expect(JSON.stringify(layoutGraph(graph))).toBe(JSON.stringify(layout));
  });

  const clear = (layout: ReturnType<typeof layoutGraph>) => {
    const named = layout.edges.filter((edge) => edge.label !== undefined && edge.labelAt !== undefined);
    const rects = named.map((edge) => {
      const size = labelSize(edge.label ?? '');
      const at = edge.labelAt ?? { x: 0, y: 0 };
      return { id: edge.id, x: at.x - size.width / 2, y: at.y - size.height / 2, ...size };
    });
    const hits = (a: { x: number; y: number; width: number; height: number }, b: typeof a) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
    for (const [index, rect] of rects.entries()) {
      for (const node of layout.nodes) expect(hits(rect, footprint(node)), `${rect.id} meets ${node.id}`).toBe(false);
      for (const other of rects.slice(index + 1)) expect(hits(rect, other), `${rect.id} meets ${other.id}`).toBe(false);
    }
    return named.length;
  };

  it('CKP_209_a_flow_name_is_clear_of_every_step_and_of_other_names', () => {
    expect(clear(layoutGraph({ ...readBpmnGraph(XML), stored: DI }))).toBe(1);
  });

  it('CKP_209_a_flow_name_sits_at_the_label_bounds_the_diagram_keeps', () => {
    const labelled: DefinitionLayout = { ...DI, labels: { 'ok->yes': { x: 120, y: 330, width: 30, height: 18 } } };
    const plain = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: labelled });
    const here = layout.edges.find((edge) => edge.id === 'c')?.labelAt;
    const start = layout.nodes.find((node) => node.id === 's');
    const startPlain = plain.nodes.find((node) => node.id === 's');
    if (!here || !start || !startPlain) throw new Error('missing');
    // The bounds are absolute: the same offset from the start event as in the diagram (start at 282, 40; bounds centre 135, 339).
    expect(here.x - symbolCentre(start).x).toBeCloseTo(135 - 300, 5);
    expect(here.y - symbolCentre(start).y).toBeCloseTo(339 - 58, 5);
  });

  it('CKP_209_flows_leaving_one_step_share_no_line_when_one_is_not_routed', () => {
    // Two flows leave `ok`: `yes` is routed by the diagram, `no` is not. Neither may start along the other.
    const layout = layoutGraph({ ...readBpmnGraph(XML), stored: DI });
    const first = (target: string) => {
      const points = layout.edges.find((edge) => edge.source === 'ok' && edge.target === target)?.points ?? [];
      return [points[0], points[1]] as const;
    };
    const [a0, a1] = first('yes');
    const [b0, b1] = first('no');
    if (!a0 || !a1 || !b0 || !b1) throw new Error('missing');
    const along = (p: { x: number; y: number }, q: { x: number; y: number }) => (p.y === q.y ? 'row' : 'column');
    const sameLine = along(a0, a1) === along(b0, b1) && (along(a0, a1) === 'row' ? Math.abs(a0.y - b0.y) < 1 : Math.abs(a0.x - b0.x) < 1);
    expect(sameLine).toBe(false);
  });
});
