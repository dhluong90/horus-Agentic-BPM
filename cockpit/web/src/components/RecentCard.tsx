import type { ReactNode } from 'react';
import { InitialDisc } from './TintTiles';

/** One recent change: what changed, its state in words, and when. Information only; the list holds the links. */
export interface RecentItem {
  key: string;
  name: string;
  status: ReactNode;
  when: string;
}

/**
 * A short card of what changed last beside a list, so the lower half of the page is never empty. At most `items`
 * as given; the full list stays in the table.
 */
export function RecentCard({ title, items, empty }: { title: string; items: readonly RecentItem[]; empty: string }) {
  return (
    <aside className="card recent" aria-label={title}>
      <h2 className="recent__title">{title}</h2>
      {items.length === 0 ? <p className="reading__note">{empty}</p> : (
        <ul className="recent__list">
          {items.map((item) => (
            <li key={item.key} className="recent__item">
              <InitialDisc name={item.name} />
              <span className="recent__text">
                <span className="recent__name">{item.name}</span>
                <span className="recent__meta">{item.status} · <span className="tnum">{item.when}</span></span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}
