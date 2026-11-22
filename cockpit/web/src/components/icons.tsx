import type { ReactElement } from 'react';
import type { Area } from '../app/route';

/** Line icons of the menu, drawn like the approved prototype: 20 px grid, 1.5 px stroke, currentColor. */
const PATHS: Record<Area, ReactElement> = {
  home: (
    <>
      <path d="M3 9.2 10 3.5l7 5.7" />
      <path d="M5 8v8.5h3.5V12h3v4.5H15V8" />
    </>
  ),
  rules: (
    <>
      <rect x="2.5" y="3.5" width="15" height="13" rx="2" />
      <path d="M2.5 8h15M8 8v8.5" />
    </>
  ),
  approvals: (
    <>
      <path d="M10 2.5l6 2.5v5c0 3.8-2.6 6.3-6 7.5-3.4-1.2-6-3.7-6-7.5V5z" />
      <path d="M7.2 10l2 2 3.8-4" />
    </>
  ),
  decisions: (
    <>
      <path d="M10 2.5 17 10l-7 7.5L3 10z" />
      <path d="M7.5 10.2 9.3 12l3.4-3.8" />
    </>
  ),
  processes: (
    <>
      <circle cx="4" cy="10" r="2" />
      <rect x="8" y="3" width="5" height="4" rx="1" />
      <rect x="8" y="13" width="5" height="4" rx="1" />
      <path d="M6 10h1.5M7.5 5v10M13 5h2.5v10H13M15.5 10H18" />
    </>
  ),
  tasks: (
    <>
      <rect x="3" y="3.5" width="14" height="13" rx="2" />
      <path d="M6.5 8.2 8 9.7l2.6-2.7M6.5 13h7" />
    </>
  ),
  incidents: (
    <>
      <path d="M10 3 17.5 16.5h-15z" />
      <path d="M10 8.5v3.5M10 14.2v.1" />
    </>
  ),
};

export function AreaIcon({ area }: { area: Area }) {
  return (
    <svg className="area-icon" viewBox="0 0 20 20" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      {PATHS[area]}
    </svg>
  );
}

export function CollapseIcon({ collapsed }: { collapsed: boolean }) {
  return (
    <svg className="area-icon" viewBox="0 0 20 20" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2.5" y="3.5" width="15" height="13" rx="2" />
      <path d="M7.5 3.5v13" />
      <path d={collapsed ? 'M11 8l2 2-2 2' : 'M13 8l-2 2 2 2'} />
    </svg>
  );
}
