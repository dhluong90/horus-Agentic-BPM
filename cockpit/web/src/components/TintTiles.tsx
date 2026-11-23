/*
 * A row of summary tiles above a list or a result: a number and what it counts, on a soft tint of the Green theme.
 * The tint is only a ground; the label says what the number is, so colour is never the only signal.
 */
export type Tint = 'lime' | 'mint' | 'amber' | 'neutral';

export interface TintTile {
  key: string;
  value: string;
  label: string;
  tint: Tint;
}

export function TintTiles({ label, tiles }: { label: string; tiles: readonly TintTile[] }) {
  return (
    <div className="tint-tiles" role="group" aria-label={label}>
      {tiles.map((tile) => (
        <div key={tile.key} className={`tint-tile tint-tile--${tile.tint}`}>
          <span className="tint-tile__label">{tile.label}</span>
          <span className="tint-tile__value tnum">{tile.value}</span>
        </div>
      ))}
    </div>
  );
}

const DISCS = ['lime', 'mint', 'sage'] as const;

/** The initials of a name on a small round tint, for a row; the tint follows the name, so a name keeps its disc. */
export function InitialDisc({ name }: { name: string }) {
  const words = name.trim().split(/[\s\-_]+/).filter((word) => word !== '');
  const initials = (words.length > 1 ? `${words[0]?.charAt(0) ?? ''}${words[1]?.charAt(0) ?? ''}` : (words[0] ?? '?').slice(0, 2)).toUpperCase();
  let sum = 0;
  for (const char of name) sum += char.charCodeAt(0);
  return <span className={`disc disc--${DISCS[sum % DISCS.length] ?? 'lime'}`} aria-hidden="true">{initials}</span>;
}
