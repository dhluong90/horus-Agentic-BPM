/*
 * The graph of a definition version, read from the engine's BPMN export (exportDefinition, format=bpmn). Nodes are
 * keyed by BPMN element type plus the HORUS subtype (cockpit DESIGN section 10, ADR-0017 DN-11): `serviceTask` with
 * `horus:type="agentTask"` is `serviceTask:agentTask`, an event with a timer definition is
 * `intermediateCatchEvent:timer`. A type this cockpit does not draw stays in the graph with its key and is drawn as a
 * generic node, so a newer host can be viewed by an older cockpit (CKP-070). The export's generated BPMNDI is not a
 * stored layout and is not read: the cockpit lays the graph out itself (CKP-069).
 */

const BPMN = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const HORUS = 'urn:horus-bpm:bpmn:extensions:v1';

/** The node types this cockpit draws with their own shape and icon. Anything else is a generic node. */
export const KNOWN_NODE_TYPES = [
  'startEvent',
  'endEvent',
  'userTask',
  'serviceTask',
  'serviceTask:agentTask',
  'businessRuleTask',
  'task',
  'manualTask',
  'scriptTask',
  'exclusiveGateway',
  'parallelGateway',
  'inclusiveGateway',
  'intermediateCatchEvent:timer',
  'callActivity',
  'transaction',
  'subProcess',
  'intermediateThrowEvent:compensate',
] as const;
export type KnownNodeType = (typeof KNOWN_NODE_TYPES)[number];

export function isKnownNodeType(type: string): type is KnownNodeType {
  return (KNOWN_NODE_TYPES as readonly string[]).includes(type);
}

export interface DiagramNode {
  id: string;
  name: string | undefined;
  /** The BPMN element type with the HORUS subtype, for example `serviceTask:agentTask` or `boundaryEvent:timer`. */
  type: string;
  /** The variable a script task stores its result in, shown beside the step in the timeline. */
  resultVariable?: string;
  /** The id of the sub-process or saga block this step stands in; absent for a step of the process itself (#486). */
  parentId?: string;
  /**
   * How the step repeats (#496): `loop` for a standard loop, `parallel` or `sequential` for a multi-instance step.
   * Absent for a step that runs once. Read from the BPMN export; the cockpit draws the marker and never edits it.
   */
  loop?: LoopKind;
}

export type LoopKind = 'loop' | 'parallel' | 'sequential';

export interface DiagramEdge {
  id: string;
  source: string;
  target: string;
  label: string | undefined;
  /** The default flow of a gateway. */
  isDefault: boolean;
}

export interface DiagramGraph {
  name: string | undefined;
  nodes: DiagramNode[];
  edges: DiagramEdge[];
}

/** Children of a process that are not steps: flows, artefacts, data and lanes. */
const NOT_A_STEP = new Set([
  'sequenceFlow', 'laneSet', 'textAnnotation', 'association', 'group', 'dataObject', 'dataObjectReference',
  'dataStoreReference', 'documentation', 'extensionElements', 'property', 'ioSpecification', 'category',
  'standardLoopCharacteristics', 'multiInstanceLoopCharacteristics',
]);

export class DiagramReadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DiagramReadError';
  }
}

function elementChildren(parent: Element): Element[] {
  return Array.from(parent.children);
}

function nonEmpty(value: string | null): string | undefined {
  const text = value?.trim();
  return text === undefined || text === '' ? undefined : text;
}

/** `timer` for a `timerEventDefinition` child, and so on; undefined when the event has no definition. */
function eventKind(element: Element): string | undefined {
  for (const child of elementChildren(element)) {
    if (child.namespaceURI === BPMN && child.localName.endsWith('EventDefinition')) {
      return child.localName.slice(0, -'EventDefinition'.length);
    }
  }
  return undefined;
}

/** The node type key of a flow element. */
export function nodeType(element: Element): string {
  const local = element.localName;
  if (local === 'serviceTask') {
    const subtype = nonEmpty(element.getAttributeNS(HORUS, 'type'));
    return subtype === undefined ? local : `${local}:${subtype}`;
  }
  if (local.endsWith('Event')) {
    const kind = eventKind(element);
    return kind === undefined ? local : `${local}:${kind}`;
  }
  if (local === 'subProcess' && element.getAttribute('triggeredByEvent') === 'true') return 'subProcess:event';
  return local;
}

/** The loop marker of an activity: its own `standardLoopCharacteristics` or `multiInstanceLoopCharacteristics` child. */
export function loopKind(element: Element): LoopKind | undefined {
  for (const child of elementChildren(element)) {
    if (child.namespaceURI !== BPMN) continue;
    if (child.localName === 'standardLoopCharacteristics') return 'loop';
    if (child.localName === 'multiInstanceLoopCharacteristics') return child.getAttribute('isSequential') === 'true' ? 'sequential' : 'parallel';
  }
  return undefined;
}

/** Elements that hold steps of their own: a sub-process (not an event sub-process) and a saga block. */
function isContainer(element: Element): boolean {
  return (element.localName === 'subProcess' && element.getAttribute('triggeredByEvent') !== 'true') || element.localName === 'transaction';
}

/** Nested sub-processes the cockpit follows; a deeper one is drawn as a plain box, never skipped. */
const MAX_NESTING = 8;

/** Reads the steps and flows of one container into the flat lists; a step inside names its container in `parentId`. */
function readContainer(container: Element, parentId: string | undefined, nodes: DiagramNode[], edges: DiagramEdge[], defaults: Set<string>, depth: number): void {
  for (const child of elementChildren(container)) {
    if (child.namespaceURI !== BPMN) continue;
    const id = nonEmpty(child.getAttribute('id'));
    if (id === undefined) continue;
    if (child.localName === 'sequenceFlow') {
      const source = nonEmpty(child.getAttribute('sourceRef'));
      const target = nonEmpty(child.getAttribute('targetRef'));
      if (source !== undefined && target !== undefined) edges.push({ id, source, target, label: nonEmpty(child.getAttribute('name')), isDefault: false });
      continue;
    }
    if (NOT_A_STEP.has(child.localName)) continue;
    const defaultFlow = nonEmpty(child.getAttribute('default'));
    if (defaultFlow !== undefined) defaults.add(defaultFlow);
    const resultVariable = child.localName === 'scriptTask' ? nonEmpty(child.getAttributeNS(HORUS, 'resultVariable')) : undefined;
    const loop = loopKind(child);
    nodes.push({
      id,
      name: nonEmpty(child.getAttribute('name')),
      type: nodeType(child),
      ...(resultVariable !== undefined ? { resultVariable } : {}),
      ...(loop !== undefined ? { loop } : {}),
      ...(parentId !== undefined ? { parentId } : {}),
    });
    if (isContainer(child) && depth < MAX_NESTING) readContainer(child, id, nodes, edges, defaults, depth + 1);
  }
}

/**
 * Reads the first executable process of a BPMN document. A document with a DOCTYPE, one that does not parse, or one
 * without a process is refused; the engine never exports such a document, so it is shown as an error, not guessed.
 */
export function readBpmnGraph(xml: string): DiagramGraph {
  if (/<!DOCTYPE/i.test(xml)) throw new DiagramReadError('The document declares a DOCTYPE');
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  if (document.getElementsByTagName('parsererror').length > 0) throw new DiagramReadError('The document is not well-formed XML');
  const processes = Array.from(document.getElementsByTagNameNS(BPMN, 'process'));
  const process = processes.find((p) => p.getAttribute('isExecutable') === 'true') ?? processes[0];
  if (process === undefined) throw new DiagramReadError('The document has no process');

  const nodes: DiagramNode[] = [];
  const edges: DiagramEdge[] = [];
  const defaults = new Set<string>();
  readContainer(process, undefined, nodes, edges, defaults, 0);
  const ids = new Set(nodes.map((node) => node.id));
  return {
    name: nonEmpty(process.getAttribute('name')),
    nodes,
    // A flow to an element that is not drawn (an artefact, a lane) is left out rather than drawn to nowhere.
    edges: edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target)).map((edge) => ({ ...edge, isDefault: defaults.has(edge.id) })),
  };
}

/** Steps in flow order: breadth first from the start events, then any step no flow reaches, in document order. A sub-process's own steps follow it, in their own flow order. */
export function flowOrder(graph: DiagramGraph): DiagramNode[] {
  const order = (scope: string | undefined): DiagramNode[] => {
    const nodes = graph.nodes.filter((node) => node.parentId === scope);
    const ids = new Set(nodes.map((node) => node.id));
    const byId = new Map(nodes.map((node) => [node.id, node]));
    const next = new Map<string, string[]>();
    const incoming = new Set<string>();
    for (const edge of graph.edges) {
      if (!ids.has(edge.source) || !ids.has(edge.target)) continue;
      next.set(edge.source, [...(next.get(edge.source) ?? []), edge.target]);
      incoming.add(edge.target);
    }
    const queue = nodes.filter((node) => node.type.startsWith('startEvent') || !incoming.has(node.id)).map((node) => node.id);
    const seen = new Set<string>();
    const ordered: DiagramNode[] = [];
    while (queue.length > 0) {
      const id = queue.shift();
      if (id === undefined || seen.has(id)) continue;
      seen.add(id);
      const node = byId.get(id);
      if (node) ordered.push(node);
      queue.push(...(next.get(id) ?? []));
    }
    return [...ordered, ...nodes.filter((node) => !seen.has(node.id))].flatMap((node) => [node, ...order(node.id)]);
  };
  return order(undefined);
}

/** The container ids around a step, outermost first; empty for a step of the process itself. */
export function ancestorsOf(graph: DiagramGraph, id: string): DiagramNode[] {
  const byId = new Map(graph.nodes.map((node) => [node.id, node]));
  const out: DiagramNode[] = [];
  let parent = byId.get(id)?.parentId;
  while (parent !== undefined && out.length <= MAX_NESTING) {
    const node = byId.get(parent);
    if (node === undefined) break;
    out.unshift(node);
    parent = node.parentId;
  }
  return out;
}

/** True for a node that holds steps of its own in this graph. */
export function isContainerNode(graph: DiagramGraph, id: string): boolean {
  return graph.nodes.some((node) => node.parentId === id);
}
