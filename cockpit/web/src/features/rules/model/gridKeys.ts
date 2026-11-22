/*
 * Arrow-key movement in the grid editor. Up and Down go to the same column of the row above or below; Left and
 * Right go to the previous or next control of the row, across cells. A key that the focused control needs for
 * itself is left to it: Up and Down in a select, Left and Right inside the text of an input, any arrow in an open
 * value list, and any arrow with a modifier.
 */

export type Move = 'up' | 'down' | 'left' | 'right';

const ARROWS: Readonly<Record<string, Move>> = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

const FOCUSABLE = 'input:not([type=hidden]):not([disabled]), select:not([disabled]), button:not([disabled])';

/** The move an arrow key asks for, or undefined when the focused control keeps the key. */
export function cellMove(key: string, target: HTMLElement, modified: boolean): Move | undefined {
  const move = ARROWS[key];
  if (move === undefined || modified) return undefined;
  if (target.closest('.picker__list')) return undefined;
  if (target instanceof HTMLSelectElement && (move === 'up' || move === 'down')) return undefined;
  if (target instanceof HTMLInputElement && target.type !== 'checkbox' && (move === 'left' || move === 'right')) {
    const { selectionStart, selectionEnd, value } = target;
    if (selectionStart === null || selectionStart !== selectionEnd) return undefined;
    if (move === 'left' && selectionStart > 0) return undefined;
    if (move === 'right' && selectionStart < value.length) return undefined;
  }
  return move;
}

/** The controls of one row, in reading order, outside any open value list. */
function controls(row: Element): HTMLElement[] {
  return [...row.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((element) => !element.closest('.picker__list'));
}

/** Where the focus goes for `move` from `from`, inside `table`; undefined at an edge. */
export function nextFocus(table: HTMLTableElement, from: HTMLElement, move: Move): HTMLElement | undefined {
  const row = from.closest('tr');
  const cell = from.closest('td, th');
  if (!row || !cell || !table.contains(row)) return undefined;
  if (move === 'left' || move === 'right') {
    const list = controls(row);
    const at = list.indexOf(from);
    return at === -1 ? undefined : list[move === 'left' ? at - 1 : at + 1];
  }
  const rows = [...table.querySelectorAll('tbody > tr:not(.dt__reading-row)')];
  const target = rows[rows.indexOf(row) + (move === 'up' ? -1 : 1)];
  const column = [...row.children].indexOf(cell);
  const targetCell = target?.children[column];
  return targetCell ? controls(targetCell)[0] : undefined;
}
