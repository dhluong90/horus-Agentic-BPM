import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import { isKnownNodeType, type DiagramGraph } from '../model/bpmnGraph';
import { EVENT_RADIUS, GATEWAY_HALF, layoutGraph, symbolCentre, type PlacedEdge, type PlacedNode, type Point } from '../model/layout';
import { nodeTypeLabel } from '../model/nodeLabels';
import { NodeIcon } from './NodeIcon';

/*
 * The read-only definition diagram (CKP-069, CKP-070), in its own chunk with dagre so the first screen does not load
 * them (CKP-028). Drawn as SVG with the diagram tokens of the styleguide; agent steps carry the violet that means
 * "agent step" and nothing else. A node type this cockpit does not know is a dashed generic box naming its type.
 */

type Zoom = 'fit' | 'actual';

function path(points: readonly Point[]): string {
  return points.map((p, index) => `${index === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
}

/** A short stroke across a default flow near its source, the BPMN mark for "default". */
function defaultMark(points: readonly Point[]): string | undefined {
  const [a, b] = points;
  if (!a || !b) return undefined;
  const length = Math.hypot(b.x - a.x, b.y - a.y) || 1;
  const ux = (b.x - a.x) / length;
  const uy = (b.y - a.y) / length;
  const cx = a.x + ux * 12;
  const cy = a.y + uy * 12;
  return `M${(cx - uy * 6 - ux * 3).toFixed(1)} ${(cy + ux * 6 - uy * 3).toFixed(1)} L${(cx + uy * 6 + ux * 3).toFixed(1)} ${(cy - ux * 6 + uy * 3).toFixed(1)}`;
}

function midpoint(points: readonly Point[]): Point | undefined {
  if (points.length === 0) return undefined;
  if (points.length % 2 === 1) return points[(points.length - 1) / 2];
  const a = points[points.length / 2 - 1];
  const b = points[points.length / 2];
  return a && b ? { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } : undefined;
}

function Edge({ edge, marker, defaultLabel }: { edge: PlacedEdge; marker: string; defaultLabel: string }) {
  const mid = midpoint(edge.points);
  const label = edge.label;
  const mark = edge.isDefault ? defaultMark(edge.points) : undefined;
  return (
    <g className="dg-edge" data-edge={edge.id}>
      <path d={path(edge.points)} markerEnd={`url(#${marker})`} />
      {mark !== undefined ? <path className="dg-edge__default" d={mark}><title>{defaultLabel}</title></path> : null}
      {label !== undefined && mid ? (
        <foreignObject x={mid.x - 60} y={mid.y - 12} width={120} height={24} className="dg-edge__label-box">
          <span className="dg-edge__label" title={label}>{label}</span>
        </foreignObject>
      ) : null}
    </g>
  );
}

function Node({ node, typeLabel }: { node: PlacedNode; typeLabel: string }) {
  const known = isKnownNodeType(node.type);
  const name = node.name ?? node.id;
  const kind = node.type === 'serviceTask:agentTask' ? 'agent' : known ? node.shape : 'generic';
  const label = `${name}, ${typeLabel}`;
  if (node.shape === 'task' || !known) {
    // Unknown events and gateways are laid out and drawn as the generic box: their symbol is not known here.
    const { width, height, y } = node;
    return (
      <g className={`dg-node dg-node--${kind}`} data-node={node.id} data-type={node.type} tabIndex={0} role="img" aria-label={label}>
        <rect x={node.x} y={y} width={width} height={height} rx={10} />
        <NodeIcon type={known && isKnownNodeType(node.type) ? node.type : 'generic'} x={node.x + 12} y={y + 12} />
        <foreignObject x={node.x + 34} y={y + 6} width={width - 42} height={height - 12}>
          <span className="dg-node__text">
            <span className="dg-node__name" title={name}>{name}</span>
            <span className={known ? 'dg-node__type' : 'dg-node__type dg-node__type--raw'}>
              {known ? typeLabel : typeLabel.split(':').map((part, index) => <span key={index}>{index > 0 ? <>:<wbr /></> : null}{part}</span>)}
            </span>
          </span>
        </foreignObject>
      </g>
    );
  }
  const centre = symbolCentre(node);
  const glyph = node.type === 'exclusiveGateway' ? '×' : node.type === 'parallelGateway' ? '+' : node.type === 'inclusiveGateway' ? '○' : undefined;
  return (
    <g className={`dg-node dg-node--${node.shape} dg-node--${node.type.replace(':', '-')}`} data-node={node.id} data-type={node.type} tabIndex={0} role="img" aria-label={label}>
      {node.shape === 'event' ? (
        <>
          <circle cx={centre.x} cy={centre.y} r={EVENT_RADIUS} />
          {node.type.startsWith('intermediate') ? <circle cx={centre.x} cy={centre.y} r={EVENT_RADIUS - 4} /> : null}
          {node.type.startsWith('intermediate') ? <NodeIcon type={node.type as 'intermediateCatchEvent:timer'} x={centre.x - 8} y={centre.y - 8} /> : null}
        </>
      ) : (
        <>
          <path d={`M${String(centre.x)} ${String(centre.y - GATEWAY_HALF)} L${String(centre.x + GATEWAY_HALF)} ${String(centre.y)} L${String(centre.x)} ${String(centre.y + GATEWAY_HALF)} L${String(centre.x - GATEWAY_HALF)} ${String(centre.y)} Z`} />
          {glyph !== undefined ? <text x={centre.x} y={centre.y + 6} textAnchor="middle" className="dg-node__glyph" aria-hidden="true">{glyph}</text> : null}
        </>
      )}
      <foreignObject x={node.x} y={centre.y + GATEWAY_HALF + 4} width={node.width} height={40}>
        <span className="dg-node__caption" title={name}>{name}</span>
      </foreignObject>
    </g>
  );
}

export default function DefinitionDiagram({ graph, label }: { graph: DiagramGraph; label: string }) {
  const { t } = useI18n();
  const marker = useId().replace(/:/g, '');
  const layout = useMemo(() => layoutGraph(graph), [graph]);
  const [zoom, setZoom] = useState<Zoom>('fit');
  const frame = useRef<HTMLDivElement>(null);
  const [available, setAvailable] = useState<number | undefined>(undefined);

  useEffect(() => {
    const element = frame.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => { if (entry) setAvailable(entry.contentRect.width); });
    observer.observe(element);
    return () => { observer.disconnect(); };
  }, []);

  const fitScale = available !== undefined && layout.width > 0 ? Math.min(1, available / layout.width) : 1;
  const scale = zoom === 'fit' ? fitScale : 1;
  return (
    <div className="dg">
      <div className="dg__tools seg seg--small" role="group" aria-label={t('diagram.zoom')}>
        <button type="button" className="hit" aria-pressed={zoom === 'fit'} onClick={() => { setZoom('fit'); }}>{t('diagram.fit')}</button>
        <button type="button" className="hit" aria-pressed={zoom === 'actual'} onClick={() => { setZoom('actual'); }}>{t('diagram.actual')}</button>
      </div>
      <div ref={frame} className="dg__frame">
        <svg className="dg__svg" role="group" aria-label={label} width={Math.ceil(layout.width * scale)} height={Math.ceil(layout.height * scale)}
          viewBox={`0 0 ${String(layout.width)} ${String(layout.height)}`} data-layout="auto">
          <defs>
            <marker id={marker} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M0 0 L10 5 L0 10 z" className="dg-arrow" />
            </marker>
          </defs>
          <g>{layout.edges.map((edge) => <Edge key={edge.id} edge={edge} marker={marker} defaultLabel={t('diagram.defaultFlow')} />)}</g>
          <g>{layout.nodes.map((node) => <Node key={node.id} node={node} typeLabel={nodeTypeLabel(node.type, t)} />)}</g>
        </svg>
      </div>
    </div>
  );
}
