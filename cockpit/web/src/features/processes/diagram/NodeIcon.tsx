import type { KnownNodeType } from '../model/bpmnGraph';

/** Line icons for the node types, drawn in a 16 x 16 box at (x, y). Stroke follows the node's text colour. */
export function NodeIcon({ type, x, y }: { type: KnownNodeType | 'generic'; x: number; y: number }) {
  const common = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.5, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  const body = (() => {
    switch (type) {
      case 'userTask':
        return <><circle cx="8" cy="5" r="3" {...common} /><path d="M2.5 14.5c.8-3 3-4.5 5.5-4.5s4.7 1.5 5.5 4.5" {...common} /></>;
      case 'serviceTask':
        return <><circle cx="8" cy="8" r="2.5" {...common} /><path d="M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4" {...common} /></>;
      case 'serviceTask:agentTask':
        return <path d="M8 1.5l1.6 4.9 4.9 1.6-4.9 1.6L8 14.5l-1.6-4.9L1.5 8l4.9-1.6z" {...common} />;
      case 'businessRuleTask':
        return <><rect x="1.5" y="2.5" width="13" height="11" rx="1.5" {...common} /><path d="M1.5 6h13M6 6v7.5" {...common} /></>;
      case 'callActivity':
        return <><rect x="1.5" y="2.5" width="13" height="11" rx="2" {...common} /><path d="M8 5.5v5M5.5 8h5" {...common} /></>;
      case 'transaction':
        return <path d="M5 2.5H2.5v11H5M11 2.5h2.5v11H11" {...common} />;
      case 'intermediateCatchEvent:timer':
        return <><circle cx="8" cy="8" r="6" {...common} /><path d="M8 4.5V8l2.5 1.5" {...common} /></>;
      case 'intermediateThrowEvent:compensate':
        return <path d="M8 4l-5 4 5 4zM14 4l-5 4 5 4z" {...common} />;
      case 'generic':
        return <><rect x="2" y="2" width="12" height="12" rx="2" {...common} strokeDasharray="2 2" /><path d="M6.5 6.2a1.6 1.6 0 113 .8c-.6.4-1.5.8-1.5 1.8M8 11.3v.1" {...common} /></>;
      default:
        return null;
    }
  })();
  if (body === null) return null;
  return <g transform={`translate(${String(x)} ${String(y)})`} aria-hidden="true">{body}</g>;
}
