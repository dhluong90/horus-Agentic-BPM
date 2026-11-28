import type { DefinitionLayout } from '../../../api/processes/schemas';
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
  'serviceTask:compensation',
  'businessRuleTask',
  'task',
  'manualTask',
  'scriptTask',
  'exclusiveGateway',
  'parallelGateway',
  'inclusiveGateway',
  'complexGateway',
  'adHocSubProcess',
  'intermediateCatchEvent:multiple',
  'intermediateCatchEvent:parallelMultiple',
  'startEvent:multiple',
  'startEvent:parallelMultiple',
  'boundaryEvent:multiple',
  'boundaryEvent:parallelMultiple',
  'intermediateCatchEvent:timer',
  'callActivity',
  'transaction',
  'subProcess',
  'subProcess:event',
  'startEvent:timer',
  'startEvent:error',
  'startEvent:escalation',
  'startEvent:compensate',
  'intermediateThrowEvent:compensate',
  'boundaryEvent:timer',
  'boundaryEvent:error',
  'boundaryEvent:escalation',
  'boundaryEvent:compensate',
  'boundaryEvent:cancel',
  'endEvent:compensate',
  'endEvent:cancel',
  'boundaryEvent:message',
  'boundaryEvent:signal',
  'boundaryEvent:conditional',
  'eventBasedGateway',
  'endEvent:error',
  'endEvent:escalation',
  'endEvent:terminate',
  'intermediateThrowEvent:escalation',
  'intermediateThrowEvent:link',
  'intermediateCatchEvent:link',
  'startEvent:signal',
  'startEvent:conditional',
  'endEvent:signal',
  'intermediateCatchEvent:signal',
  'intermediateThrowEvent:signal',
  'intermediateCatchEvent:conditional',
  'startEvent:message',
  'endEvent:message',
  'intermediateCatchEvent:message',
  'intermediateThrowEvent:message',
  'receiveTask',
  'sendTask',
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
  /** The id of the sub-process or saga block this step stands in; absent for a step of the process itself (#486). */
  parentId?: string;
  /** For a boundary event (#489): the id of the task, call activity or sub-process it sits on. */
  attachedTo?: string;
  /**
   * For a boundary event: whether it cancels what it sits on when it fires. For the start event of an event sub-process
   * (#487) and the event sub-process itself: whether it cancels the rest of its scope. False is drawn with a dashed ring.
   */
  interrupting?: boolean;
  /** For an event sub-process (#487): what starts it, the name of its start event's definition: `timer`, `message`, `signal`, `conditional`, `error`, `escalation` or `compensate`. */
  trigger?: string;
  /** For a link throw or catch (#493): the name that pairs the two. */
  linkName?: string;
  /**
   * How the step repeats (#496): `loop` for a standard loop, `parallel` or `sequential` for a multi-instance step.
   * Absent for a step that runs once. Read from the BPMN export; the cockpit draws the marker and never edits it.
   */
  loop?: LoopKind;
  /** For an ad-hoc sub-process (#500): whether its activities may run side by side or one at a time. */
  ordering?: 'parallel' | 'sequential';
}

export type LoopKind = 'loop' | 'parallel' | 'sequential';

export interface DiagramEdge {
  id: string;
  source: string;
  target: string;
  label: string | undefined;
  /** The default flow of a gateway. */
  isDefault: boolean;
  /** A link between a throw and its catch of the same name (#493), drawn dashed; not a sequence flow. */
  isLink?: boolean;
  /** An association from a compensation boundary event to its compensation activity (#488), drawn dotted; not a sequence flow. */
  isAssociation?: boolean;
}

/** A pool of the collaboration (#497). `self` is the pool of the process this graph shows; the others are black boxes. */
export interface DiagramPool { id: string; name: string | undefined; self: boolean }
/** A lane of the process: a name and the steps it holds. Display only. */
export interface DiagramLane { id: string; name: string | undefined; nodes: string[] }
/** A message flow. Each end is a step of this process or a pool; `correlationKey` is the variable the target step reads. */
export interface DiagramMessageFlow { id: string; label: string | undefined; source: string; target: string; correlationKey: string | undefined }
export interface DiagramCollaboration { pools: DiagramPool[]; lanes: DiagramLane[]; messageFlows: DiagramMessageFlow[] }

/**
 * A data symbol of the process (#499): a data object (its reference, or the object itself when nothing refers to it)
 * or a data store reference. Read only; the cockpit draws them and never edits them.
 */
export interface DiagramDataItem {
  id: string;
  kind: 'dataObject' | 'dataStore';
  name: string | undefined;
  /** The sub-process or saga block it stands in; absent for the process itself. */
  parentId?: string;
}
/** A data association between a step and a symbol: `read` is a data input association, `write` a data output association. */
export interface DiagramDataLink { id: string; activity: string; item: string; direction: 'read' | 'write' }
export interface DiagramData { items: DiagramDataItem[]; links: DiagramDataLink[] }

export interface DiagramGraph {
  name: string | undefined;
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  /** Pools, lanes and message flows, read only (#497); absent for a process with none. */
  collaboration?: DiagramCollaboration;
  /** Data objects, data stores and their associations, read only (#499); absent for a process with none. */
  data?: DiagramData;
  /** The layout the model's own diagram stored (BPMNDI, layout revision 1); absent when the version has none, and the cockpit lays the graph out itself. */
  stored?: DefinitionLayout;
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

/** The name of the link event definition of an event, if it has one (#493). */
function linkNameOf(element: Element): string | undefined {
  for (const child of elementChildren(element)) {
    if (child.namespaceURI === BPMN && child.localName === 'linkEventDefinition') return nonEmpty(child.getAttribute('name'));
  }
  return undefined;
}

/** The node type key of a flow element. */
export function nodeType(element: Element): string {
  const local = element.localName;
  if (local === 'serviceTask') {
    const subtype = nonEmpty(element.getAttributeNS(HORUS, 'type'));
    // #488: a task that is the undo of another step, run by compensation and never by a flow.
    if (subtype === undefined && element.getAttribute('isForCompensation') === 'true') return `${local}:compensation`;
    return subtype === undefined ? local : `${local}:${subtype}`;
  }
  if (local.endsWith('Event')) {
    // #500: several definitions make a multiple event, or a parallel multiple one that waits for all of them.
    const definitions = elementChildren(element).filter((child) => child.namespaceURI === BPMN && child.localName.endsWith('EventDefinition'));
    if (definitions.length > 1) return `${local}:${element.getAttribute('parallelMultiple') === 'true' ? 'parallelMultiple' : 'multiple'}`;
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

/** Elements that hold steps of their own: a sub-process, an event sub-process (#487) and a saga block. */
function isContainer(element: Element): boolean {
  return element.localName === 'subProcess' || element.localName === 'transaction' || element.localName === 'adHocSubProcess';
}

/** The start event of an event sub-process, which carries what starts it (#487). */
function triggerOf(element: Element): { trigger: string | undefined; interrupting: boolean } {
  const start = elementChildren(element).find((child) => child.namespaceURI === BPMN && child.localName === 'startEvent');
  return { trigger: start === undefined ? undefined : eventKind(start), interrupting: start?.getAttribute('isInterrupting') !== 'false' };
}

/** Nested sub-processes the cockpit follows; a deeper one is drawn as a plain box, never skipped. */
const MAX_NESTING = 8;

/** The text of the `sourceRef`/`targetRef` children of a data association. */
function refsOf(association: Element, name: 'sourceRef' | 'targetRef'): string[] {
  return elementChildren(association).filter((c) => c.namespaceURI === BPMN && c.localName === name).map((c) => c.textContent.trim()).filter((r) => r !== '');
}

/**
 * Reads the data symbols of one container (#499). A data input association runs from a reference to the step's input,
 * a data output association from the step's output to a reference; the BPMN end that is a reference names the symbol.
 * Reference ids are kept raw here; `readData` joins them to the objects they refer to.
 */
function readDataOf(container: Element, parentId: string | undefined, raw: RawData): void {
  for (const child of elementChildren(container)) {
    if (child.namespaceURI !== BPMN) continue;
    const id = nonEmpty(child.getAttribute('id'));
    if (id === undefined) continue;
    if (child.localName === 'dataObject') raw.objects.set(id, { name: nonEmpty(child.getAttribute('name')), ...(parentId !== undefined ? { parentId } : {}) });
    if (child.localName === 'dataObjectReference') raw.references.push({ id, kind: 'dataObject', name: nonEmpty(child.getAttribute('name')), ref: nonEmpty(child.getAttribute('dataObjectRef')), parentId });
    if (child.localName === 'dataStoreReference') raw.references.push({ id, kind: 'dataStore', name: nonEmpty(child.getAttribute('name')), ref: nonEmpty(child.getAttribute('dataStoreRef')), parentId });
    for (const association of elementChildren(child)) {
      if (association.namespaceURI !== BPMN) continue;
      const aid = nonEmpty(association.getAttribute('id')) ?? `${id}:${String(raw.links.length)}`;
      if (association.localName === 'dataInputAssociation') for (const source of refsOf(association, 'sourceRef')) raw.links.push({ id: `${aid}:${source}`, activity: id, item: source, direction: 'read' });
      if (association.localName === 'dataOutputAssociation') for (const target of refsOf(association, 'targetRef')) raw.links.push({ id: `${aid}:${target}`, activity: id, item: target, direction: 'write' });
    }
    if (isContainer(child) && raw.depth < MAX_NESTING) {
      raw.depth += 1;
      readDataOf(child, id, raw);
      raw.depth -= 1;
    }
  }
}

interface RawData {
  depth: number;
  objects: Map<string, { name: string | undefined; parentId?: string }>;
  references: { id: string; kind: 'dataObject' | 'dataStore'; name: string | undefined; ref: string | undefined; parentId: string | undefined }[];
  links: DiagramDataLink[];
}

/** The data symbols of a process: every reference, and every data object nothing refers to; links to a symbol or step that is not there are left out. */
function readData(process: Element, nodeIds: ReadonlySet<string>): DiagramData | undefined {
  const raw: RawData = { depth: 0, objects: new Map(), references: [], links: [] };
  readDataOf(process, undefined, raw);
  const items: DiagramDataItem[] = raw.references.map((r) => ({
    id: r.id, kind: r.kind,
    name: r.name ?? (r.ref !== undefined ? raw.objects.get(r.ref)?.name : undefined),
    ...(r.parentId !== undefined ? { parentId: r.parentId } : {}),
  }));
  const referred = new Set(raw.references.map((r) => r.ref));
  for (const [id, object] of raw.objects) {
    if (!referred.has(id)) items.push({ id, kind: 'dataObject', name: object.name, ...(object.parentId !== undefined ? { parentId: object.parentId } : {}) });
  }
  const itemIds = new Set(items.map((i) => i.id));
  const links = raw.links.filter((l) => itemIds.has(l.item) && nodeIds.has(l.activity));
  return items.length === 0 ? undefined : { items, links };
}

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
    if (child.localName === 'association') {
      // #488: only the line from a compensation boundary event to its compensation activity is drawn; the ends decide below.
      const source = nonEmpty(child.getAttribute('sourceRef'));
      const target = nonEmpty(child.getAttribute('targetRef'));
      if (source !== undefined && target !== undefined) edges.push({ id, source, target, label: undefined, isDefault: false, isAssociation: true });
      continue;
    }
    if (NOT_A_STEP.has(child.localName)) continue;
    const defaultFlow = nonEmpty(child.getAttribute('default'));
    if (defaultFlow !== undefined) defaults.add(defaultFlow);
    const boundary = child.localName === 'boundaryEvent';
    const attachedTo = boundary ? nonEmpty(child.getAttribute('attachedToRef')) : undefined;
    const link = child.localName.startsWith('intermediate') ? linkNameOf(child) : undefined;
    const byEvent = child.localName === 'subProcess' && child.getAttribute('triggeredByEvent') === 'true' ? triggerOf(child) : undefined;
    const loop = loopKind(child);
    nodes.push({
      id,
      name: nonEmpty(child.getAttribute('name')),
      type: nodeType(child),
      ...(loop !== undefined ? { loop } : {}),
      ...(parentId !== undefined ? { parentId } : {}),
      ...(attachedTo !== undefined ? { attachedTo, interrupting: child.getAttribute('cancelActivity') !== 'false' } : {}),
      ...(child.localName === 'startEvent' && child.getAttribute('isInterrupting') === 'false' ? { interrupting: false } : {}),
      ...(byEvent !== undefined ? { interrupting: byEvent.interrupting, ...(byEvent.trigger !== undefined ? { trigger: byEvent.trigger } : {}) } : {}),
      ...(link !== undefined ? { linkName: link } : {}),
      ...(child.localName === 'adHocSubProcess' ? { ordering: child.getAttribute('ordering') === 'Sequential' ? ('sequential' as const) : ('parallel' as const) } : {}),
    });
    if (isContainer(child) && depth < MAX_NESTING) readContainer(child, id, nodes, edges, defaults, depth + 1);
  }
}

function readLanes(set: Element, out: DiagramLane[]): void {
  for (const lane of elementChildren(set)) {
    if (lane.namespaceURI !== BPMN || lane.localName !== 'lane') continue;
    const refs = elementChildren(lane).filter((c) => c.namespaceURI === BPMN && c.localName === 'flowNodeRef').map((c) => c.textContent.trim()).filter((r) => r !== '');
    out.push({ id: nonEmpty(lane.getAttribute('id')) ?? '', name: nonEmpty(lane.getAttribute('name')), nodes: refs });
    for (const child of elementChildren(lane)) if (child.namespaceURI === BPMN && child.localName === 'childLaneSet') readLanes(child, out);
  }
}

/** Pools, lanes and message flows of a document (#497), or undefined when it has none. */
function readCollaboration(document: Document, process: Element): DiagramCollaboration | undefined {
  const lanes: DiagramLane[] = [];
  for (const child of elementChildren(process)) if (child.namespaceURI === BPMN && child.localName === 'laneSet') readLanes(child, lanes);
  const collaboration = Array.from(document.getElementsByTagNameNS(BPMN, 'collaboration'))[0];
  const pools: DiagramPool[] = [];
  const messageFlows: DiagramMessageFlow[] = [];
  const processId = process.getAttribute('id');
  const messageNames = new Map(Array.from(document.getElementsByTagNameNS(BPMN, 'message')).map((m) => [m.getAttribute('id') ?? '', nonEmpty(m.getAttribute('name'))]));
  if (collaboration !== undefined) {
    for (const child of elementChildren(collaboration)) {
      if (child.namespaceURI !== BPMN) continue;
      const id = nonEmpty(child.getAttribute('id'));
      if (id === undefined) continue;
      if (child.localName === 'participant') pools.push({ id, name: nonEmpty(child.getAttribute('name')), self: child.getAttribute('processRef') === processId });
      if (child.localName === 'messageFlow') {
        const source = nonEmpty(child.getAttribute('sourceRef'));
        const target = nonEmpty(child.getAttribute('targetRef'));
        if (source === undefined || target === undefined) continue;
        messageFlows.push({
          id, source, target,
          label: nonEmpty(child.getAttribute('name')) ?? messageNames.get(child.getAttribute('messageRef') ?? ''),
          correlationKey: nonEmpty(child.getAttributeNS(HORUS, 'correlationKey')),
        });
      }
    }
  }
  return pools.length === 0 && lanes.length === 0 ? undefined : { pools, lanes, messageFlows };
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
  // A link throw is joined to the catch of the same name in the same container by a dashed line (#493).
  for (const thrown of nodes.filter((node) => node.type === 'intermediateThrowEvent:link' && node.linkName !== undefined)) {
    const caught = nodes.find((node) => node.type === 'intermediateCatchEvent:link' && node.linkName === thrown.linkName && node.parentId === thrown.parentId);
    if (caught !== undefined) edges.push({ id: `link:${thrown.id}`, source: thrown.id, target: caught.id, label: thrown.linkName, isDefault: false, isLink: true });
  }
  const collaboration = readCollaboration(document, process);
  const data = readData(process, ids);
  return {
    name: nonEmpty(process.getAttribute('name')),
    nodes,
    ...(collaboration !== undefined ? { collaboration } : {}),
    ...(data !== undefined ? { data } : {}),
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
