import { useEffect, useRef, useSyncExternalStore } from 'react';

/** Room kept under a diagram canvas for the docked decision before the dock has been measured (CKP-024). */
export const DOCK_RESERVE = 150;

/** The dock's own margins (16 px above, 12 px below) and one 8 px step of air between the card and the dock. */
const DOCK_GAP = 36;

/*
 * The docked decision grows when it says why Approve is unavailable or shows a refusal. It publishes its height here,
 * so the diagram above keeps exactly that room and the dock never covers the bottom of the changes card (#443).
 */
let height = 0;
const listeners = new Set<() => void>();

function publish(next: number): void {
  if (next === height) return;
  height = next;
  for (const listener of listeners) listener();
}

/** Attach to the docked decision: its height is published while it is on screen. */
export function useDockHeight<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    const element = ref.current;
    if (element === null) return;
    const measure = () => { publish(Math.ceil(element.getBoundingClientRect().height)); };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    observer?.observe(element);
    return () => {
      observer?.disconnect();
      publish(0);
    };
  }, []);
  return ref;
}

/** The room a diagram keeps below its canvas for the dock on screen now. */
export function useDockReserve(): number {
  const measured = useSyncExternalStore(
    (onChange) => { listeners.add(onChange); return () => { listeners.delete(onChange); }; },
    () => height,
    () => 0,
  );
  return measured > 0 ? measured + DOCK_GAP : DOCK_RESERVE;
}
