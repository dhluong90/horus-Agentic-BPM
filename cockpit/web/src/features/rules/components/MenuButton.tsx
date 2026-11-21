import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';

export interface MenuItem {
  label: string;
  onSelect: () => void;
  disabled?: boolean;
  danger?: boolean;
}

/**
 * A button that opens a menu (WAI-ARIA menu button): Enter, Space or the arrow keys open it, arrows move,
 * Escape closes and returns focus to the button, a click outside closes it.
 */
/** `tooltip` shows the button's name on hover and on keyboard focus, for an icon-only trigger such as the row `⋯`. */
export function MenuButton({ label, trigger, items, className, tooltip }: { label: string; trigger: ReactNode; items: readonly MenuItem[]; className?: string; tooltip?: string }) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const button = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not([disabled])')?.focus();
    const close = (event: MouseEvent) => {
      if (!menu.current?.contains(event.target as Node) && !button.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => { document.removeEventListener('mousedown', close); };
  }, [open]);

  const onMenuKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const entries = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not([disabled])') ?? []);
    const index = entries.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === 'Escape') {
      event.preventDefault();
      setOpen(false);
      button.current?.focus();
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const next = entries[(index + (event.key === 'ArrowDown' ? 1 : entries.length - 1)) % Math.max(entries.length, 1)];
      next?.focus();
    } else if (event.key === 'Tab') setOpen(false);
  };

  return (
    <span className="menu">
      <button ref={button} type="button" className={`${className ?? 'btn btn--quiet'}${tooltip !== undefined && !open ? ' has-tip' : ''}`} aria-label={label}
        {...(tooltip !== undefined ? { 'data-tip': tooltip } : {})} aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined}
        onClick={() => { setOpen((value) => !value); }}
        onKeyDown={(event) => { if (event.key === 'ArrowDown') { event.preventDefault(); setOpen(true); } }}>
        {trigger}
      </button>
      {open ? (
        <div ref={menu} id={menuId} role="menu" aria-label={label} className="menu__list" onKeyDown={onMenuKey}>
          {items.map((item) => (
            <button key={item.label} type="button" role="menuitem" className={item.danger ? 'menu__item menu__item--danger' : 'menu__item'} disabled={item.disabled}
              onClick={() => { setOpen(false); button.current?.focus(); item.onSelect(); }}>
              {item.label}
            </button>
          ))}
        </div>
      ) : null}
    </span>
  );
}
