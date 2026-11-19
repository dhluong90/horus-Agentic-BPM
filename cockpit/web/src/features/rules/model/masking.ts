import type { InputMasking, MaskingStatus } from '../../../api/rules/schemas';

/** True for an input whose values the decision log masks, by declaration or by the tenant's default. */
export function isMasked(status: MaskingStatus | null): boolean {
  return status === 'MASKED' || status === 'MASKED_BY_DEFAULT';
}

/** An input that was masked in the base version and is kept in plain text in this one: the change an approver must see. */
export function maskRemoved(masking: InputMasking): boolean {
  return masking.from !== null && masking.to !== null && isMasked(masking.from) && !isMasked(masking.to);
}

export type MaskingMark =
  | { kind: 'none' }
  | { kind: 'changed'; from: MaskingStatus; to: MaskingStatus; removed: boolean }
  | { kind: 'added'; to: MaskingStatus };

/** What the diff header shows for one input of this version: nothing when its masking did not change. */
export function maskingMark(masking: InputMasking | undefined): MaskingMark {
  if (masking === undefined || !masking.changed || masking.to === null) return { kind: 'none' };
  if (masking.from === null) return { kind: 'added', to: masking.to };
  return { kind: 'changed', from: masking.from, to: masking.to, removed: maskRemoved(masking) };
}
