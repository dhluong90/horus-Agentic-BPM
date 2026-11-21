import type { MouseEvent } from 'react';
import { SYMBOL_SIZE, symbolCentre, type Layout } from '../model/layout';

/** The part of the drawing in view, in drawing units. */
export interface View { x: number; y: number; width: number; height: number }

const MAP_WIDTH = 180;
const MAP_MAX_HEIGHT = 96;

/**
 * A small map of the whole drawing with the part in view outlined, shown while the diagram is larger than its
 * canvas. Clicking a point centres the view there; the same panning is available by keyboard on the canvas, so the
 * map itself is decoration for assistive technology.
 */
export function Minimap({ layout, view, selected, onCentre }: { layout: Layout; view: View; selected: string | undefined; onCentre: (x: number, y: number) => void }) {
  const ratio = Math.min(MAP_WIDTH / layout.width, MAP_MAX_HEIGHT / layout.height);
  const width = Math.round(layout.width * ratio);
  const height = Math.round(layout.height * ratio);
  const centre = (event: MouseEvent<SVGSVGElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    onCentre((event.clientX - box.left) / ratio, (event.clientY - box.top) / ratio);
  };
  return (
    <div className="dg__minimap" aria-hidden="true" data-minimap="true">
      <svg width={width} height={height} viewBox={`0 0 ${String(layout.width)} ${String(layout.height)}`} onClick={centre}>
        {layout.edges.map((edge) => (
          <polyline key={edge.id} className="dg__minimap-edge" points={edge.points.map((p) => `${p.x.toFixed(0)},${p.y.toFixed(0)}`).join(' ')} />
        ))}
        {layout.nodes.map((node) => {
          const className = node.id === selected ? 'dg__minimap-node dg__minimap-node--selected' : 'dg__minimap-node';
          if (node.shape === 'task') return <rect key={node.id} className={className} x={node.x} y={node.y} width={node.width} height={node.height} rx={6} />;
          const centre = symbolCentre(node);
          return <circle key={node.id} className={className} cx={centre.x} cy={centre.y} r={SYMBOL_SIZE / 2} />;
        })}
        <rect className="dg__minimap-view" data-view="true" x={view.x} y={view.y} width={view.width} height={view.height} />
      </svg>
    </div>
  );
}
