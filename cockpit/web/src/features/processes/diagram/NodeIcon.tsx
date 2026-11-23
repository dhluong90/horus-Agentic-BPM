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
      case 'serviceTask:compensation':
        return <><circle cx="8" cy="8" r="2.5" {...common} /><path d="M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4" {...common} /><path d="M5.5 14.5l-2-1.8 2-1.8M3.5 12.7h4" {...common} /></>;
      case 'serviceTask:agentTask':
        return <path d="M8 1.5l1.6 4.9 4.9 1.6-4.9 1.6L8 14.5l-1.6-4.9L1.5 8l4.9-1.6z" {...common} />;
      case 'businessRuleTask':
        return <><rect x="1.5" y="2.5" width="13" height="11" rx="1.5" {...common} /><path d="M1.5 6h13M6 6v7.5" {...common} /></>;
      case 'task':
        return <rect x="1.5" y="3.5" width="13" height="9" rx="2" {...common} />;
      case 'manualTask':
        return <path d="M4 14V7.5a1 1 0 012 0V4.5a1 1 0 012 0v-.5a1 1 0 012 0v.5a1 1 0 012 0V10c0 2.5-1.5 4-4 4z" {...common} />;
      case 'scriptTask':
        return <><path d="M4 2.5h8v11H4zM6 5.5h4M6 8h4M6 10.5h2.5" {...common} /></>;
      case 'callActivity':
        return <><rect x="1.5" y="2.5" width="13" height="11" rx="2" {...common} /><path d="M8 5.5v5M5.5 8h5" {...common} /></>;
      case 'subProcess':
        return <><rect x="1.5" y="2.5" width="13" height="11" rx="2" {...common} /><rect x="5.5" y="8" width="5" height="3.5" {...common} /></>;
      case 'transaction':
        return <path d="M5 2.5H2.5v11H5M11 2.5h2.5v11H11" {...common} />;
      case 'intermediateCatchEvent:timer':
        return <><circle cx="8" cy="8" r="6" {...common} /><path d="M8 4.5V8l2.5 1.5" {...common} /></>;
      case 'boundaryEvent:timer':
        return <><circle cx="8" cy="8" r="6" {...common} /><path d="M8 4.5V8l2.5 1.5" {...common} /></>;
      case 'startEvent:timer':
        return <><circle cx="8" cy="8" r="6" {...common} /><path d="M8 4.5V8l2.5 1.5" {...common} /></>;
      case 'startEvent:error':
        return <path d="M9.5 1.5L3.5 9h4l-1 5.5L12.5 7h-4z" {...common} />;
      case 'startEvent:escalation':
        return <path d="M8 2l5 11-5-3-5 3z" {...common} />;
      case 'startEvent:compensate':
        return <path d="M8 4l-5 4 5 4zM14 4l-5 4 5 4z" {...common} />;
      case 'subProcess:event':
        return <><rect x="1.5" y="2.5" width="13" height="11" rx="2" {...common} strokeDasharray="2 2" /><circle cx="6" cy="8" r="1.8" {...common} /></>;
      case 'boundaryEvent:error':
        return <path d="M9.5 1.5L3.5 9h4l-1 5.5L12.5 7h-4z" {...common} />;
      case 'endEvent:error':
        return <path d="M9.5 1.5L3.5 9h4l-1 5.5L12.5 7h-4z" {...common} fill="currentColor" />;
      case 'boundaryEvent:escalation':
      case 'intermediateThrowEvent:escalation':
        return <path d="M8 2l5 11-5-3-5 3z" {...common} />;
      case 'endEvent:escalation':
        return <path d="M8 2l5 11-5-3-5 3z" {...common} fill="currentColor" />;
      case 'endEvent:terminate':
        return <circle cx="8" cy="8" r="5" {...common} fill="currentColor" />;
      case 'intermediateThrowEvent:link':
        return <path d="M2 8h10M8.5 4.5L12 8l-3.5 3.5" {...common} fill="currentColor" />;
      case 'intermediateCatchEvent:link':
        return <path d="M2 8h10M8.5 4.5L12 8l-3.5 3.5" {...common} />;
      case 'startEvent:signal':
      case 'boundaryEvent:signal':
      case 'intermediateCatchEvent:signal':
        return <path d="M8 2.5l6 10.5H2z" {...common} />;
      case 'endEvent:signal':
      case 'intermediateThrowEvent:signal':
        return <path d="M8 2.5l6 10.5H2z" {...common} fill="currentColor" />;
      case 'startEvent:conditional':
      case 'boundaryEvent:conditional':
      case 'intermediateCatchEvent:conditional':
        return <path d="M3.5 2.5h9v11h-9zM5.5 5.5h5M5.5 8h5M5.5 10.5h5" {...common} />;
      case 'startEvent:message':
      case 'boundaryEvent:message':
      case 'intermediateCatchEvent:message':
      case 'receiveTask':
        // A hollow envelope: the message is awaited.
        return <><rect x="1.5" y="3.5" width="13" height="9" rx="1.5" {...common} /><path d="M2 4.5L8 9l6-4.5" {...common} /></>;
      case 'endEvent:message':
      case 'intermediateThrowEvent:message':
      case 'sendTask':
        // A filled envelope: the message is sent.
        return <><rect className="icon--filled" x="1.5" y="3.5" width="13" height="9" rx="1.5" {...common} /><path className="icon--knockout" d="M2 4.5L8 9l6-4.5" {...common} /></>;
      case 'boundaryEvent:compensate':
      case 'intermediateThrowEvent:compensate':
        return <path d="M8 4l-5 4 5 4zM14 4l-5 4 5 4z" {...common} />;
      case 'endEvent:compensate':
        return <path d="M8 4l-5 4 5 4zM14 4l-5 4 5 4z" {...common} fill="currentColor" />;
      case 'boundaryEvent:cancel':
        return <path d="M4 4l8 8M12 4l-8 8" {...common} />;
      case 'endEvent:cancel':
        return <path d="M4 4l8 8M12 4l-8 8" {...common} strokeWidth={2.4} />;
      case 'generic':
        return <><rect x="2" y="2" width="12" height="12" rx="2" {...common} strokeDasharray="2 2" /><path d="M6.5 6.2a1.6 1.6 0 113 .8c-.6.4-1.5.8-1.5 1.8M8 11.3v.1" {...common} /></>;
      default:
        return null;
    }
  })();
  if (body === null) return null;
  return <g transform={`translate(${String(x)} ${String(y)})`} aria-hidden="true">{body}</g>;
}
