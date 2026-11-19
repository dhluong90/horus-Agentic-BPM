import type { InputMasking, MaskingStatus, VersionDiff } from '../../../api/rules/schemas';
import type { StructureNote } from './versionDiff';

/** True for an input whose values the decision log masks, by declaration or by the tenant's default. */
export function isMasked(status: MaskingStatus | null): boolean {
  return status === 'MASKED' || status === 'MASKED_BY_DEFAULT';
}

/** An input that was masked in the base version and is kept in plain text in this one: the change an approver must see. */
export function maskRemoved(masking: InputMasking): boolean {
  return masking.from !== null && masking.to !== null && isMasked(masking.from) && !isMasked(masking.to);
}

/** A changed input of this version that the decision log will not mask: its header carries the amber ▲. */
export function flagged(masking: InputMasking | undefined): boolean {
  return masking !== undefined && masking.changed && masking.to !== null && !isMasked(masking.to);
}

/** The masking change of an input both versions have, said as one sentence above the table (RLE-051). */
export interface MaskingNote { kind: 'masking'; name: string; from: MaskingStatus; to: MaskingStatus; removed: boolean }

/** A column added to this version, with its masking when it is a condition the engine reported. */
export interface AddedColumnNote { kind: 'columnAdded'; group: 'when' | 'then'; name: string; masking: MaskingStatus | undefined }

export type ChangeNote = Exclude<StructureNote, { kind: 'columnAdded' }> | AddedColumnNote | MaskingNote;

/**
 * The sentences above the table: the diff's structure notes with the masking column folded in. A condition
 * column whose only change is its `mask` reads as its masking sentence, not as "column changed"; an added
 * condition column names its masking.
 */
export function changeNotes(diff: VersionDiff, notes: readonly StructureNote[]): ChangeNote[] {
  const masking = new Map(diff.masking.map((item) => [item.input, item]));
  const onlyMask = new Set(diff.inputs.changed.filter((column) => column.fields.every((field) => field.path === '/mask')).map((column) => column.name));
  const sentences: MaskingNote[] = diff.masking.flatMap((item) => (item.changed && item.from !== null && item.to !== null
    ? [{ kind: 'masking' as const, name: item.input, from: item.from, to: item.to, removed: maskRemoved(item) }]
    : []));
  const said = new Set(sentences.map((note) => note.name));
  const rest = notes.flatMap((note): ChangeNote[] => {
    if (note.kind === 'columnChanged' && note.group === 'when' && onlyMask.has(note.name) && said.has(note.name)) return [];
    if (note.kind === 'columnAdded') return [{ ...note, masking: note.group === 'when' ? (masking.get(note.name)?.to ?? undefined) : undefined }];
    return [note];
  });
  return [...sentences, ...rest];
}
