import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react';

export interface TabItem<T extends string> {
  id: T;
  label: string;
}

/** Underline tabs (WAI-ARIA tabs): arrow keys move between tabs; the panel follows the selection. */
export function Tabs<T extends string>({ label, items, selected, onSelect, children, className }: {
  label: string;
  items: readonly TabItem<T>[];
  selected: T;
  onSelect: (id: T) => void;
  children: ReactNode;
  className?: string;
}) {
  const base = useId();
  const list = useRef<HTMLDivElement>(null);
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
    event.preventDefault();
    const index = items.findIndex((item) => item.id === selected);
    const next = items[(index + (event.key === 'ArrowRight' ? 1 : items.length - 1)) % items.length];
    if (next) {
      onSelect(next.id);
      list.current?.querySelector<HTMLButtonElement>(`#${CSS.escape(`${base}-${next.id}`)}`)?.focus();
    }
  };
  return (
    <div className={className}>
      <div ref={list} role="tablist" aria-label={label} className="tabs" onKeyDown={onKey}>
        {items.map((item) => (
          <button key={item.id} id={`${base}-${item.id}`} type="button" role="tab" className="tabs__tab hit" aria-selected={item.id === selected}
            aria-controls={`${base}-panel`} tabIndex={item.id === selected ? 0 : -1} onClick={() => { onSelect(item.id); }}>
            {item.label}
          </button>
        ))}
      </div>
      <div id={`${base}-panel`} role="tabpanel" aria-labelledby={`${base}-${selected}`} className="tabs__panel">
        {children}
      </div>
    </div>
  );
}
