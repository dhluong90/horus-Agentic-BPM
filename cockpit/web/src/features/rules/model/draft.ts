import { nextRowId, type EditableRow } from './table';

/** The editor's working copy. Edits never reach the engine until "Save draft". */
export interface DraftState {
  rows: EditableRow[];
  /** Counter for keys of rows added in this session; keys never repeat. */
  added: number;
}

export type DraftAction =
  | { type: 'edit-cell'; key: string; part: 'when' | 'then'; column: string; text: string }
  | { type: 'edit-id'; key: string; text: string }
  | { type: 'add' }
  | { type: 'copy'; key: string }
  | { type: 'delete'; key: string }
  | { type: 'move'; key: string; by: -1 | 1 }
  | { type: 'reset'; rows: EditableRow[] };

export function initialDraft(rows: EditableRow[]): DraftState {
  return { rows, added: 0 };
}

function update(rows: readonly EditableRow[], key: string, change: (row: EditableRow) => EditableRow): EditableRow[] {
  return rows.map((row) => (row.key === key ? change(row) : row));
}

export function draftReducer(state: DraftState, action: DraftAction): DraftState {
  switch (action.type) {
    case 'edit-cell':
      return {
        ...state,
        rows: update(state.rows, action.key, (row) => ({ ...row, [action.part]: { ...row[action.part], [action.column]: action.text } })),
      };
    case 'edit-id':
      return { ...state, rows: update(state.rows, action.key, (row) => ({ ...row, id: action.text })) };
    case 'add': {
      const row: EditableRow = { key: `new-${state.added + 1}`, id: nextRowId(state.rows), when: {}, then: {}, groups: {}, original: undefined };
      return { rows: [...state.rows, row], added: state.added + 1 };
    }
    case 'copy': {
      const index = state.rows.findIndex((row) => row.key === action.key);
      const source = state.rows[index];
      if (!source) return state;
      const copy: EditableRow = {
        ...source,
        key: `new-${state.added + 1}`,
        id: nextRowId(state.rows),
        when: { ...source.when },
        then: { ...source.then },
        original: source.original ? { ...source.original, id: '' } : undefined,
      };
      return { rows: [...state.rows.slice(0, index + 1), copy, ...state.rows.slice(index + 1)], added: state.added + 1 };
    }
    case 'delete':
      return { ...state, rows: state.rows.filter((row) => row.key !== action.key) };
    case 'move': {
      const index = state.rows.findIndex((row) => row.key === action.key);
      const target = index + action.by;
      const row = state.rows[index];
      const other = state.rows[target];
      if (!row || !other) return state;
      const rows = [...state.rows];
      rows[index] = other;
      rows[target] = row;
      return { ...state, rows };
    }
    case 'reset':
      return initialDraft(action.rows);
  }
}
