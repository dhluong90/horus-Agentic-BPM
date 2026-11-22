import { useEffect, useRef } from 'react';

/**
 * Keeps the selected row of a list in view: when the selection changes, or the list first shows it, the row scrolls
 * only as far as needed (`block: 'nearest'`), so a row already on screen never moves.
 */
export function useKeepInView<T extends HTMLElement>(key: string | undefined) {
  const ref = useRef<T>(null);
  useEffect(() => {
    const element = ref.current;
    if (key === undefined || element === null || typeof element.scrollIntoView !== 'function') return;
    element.scrollIntoView({ block: 'nearest' });
  }, [key]);
  return ref;
}
