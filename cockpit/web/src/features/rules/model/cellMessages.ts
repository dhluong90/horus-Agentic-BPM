import type { MessageKey } from '../../../i18n/en';

/*
 * One plain sentence for a cell the engine refused, saying what to do (styleguide section 11). The engine's
 * own sentence stays one click away in the notice's Details. Unknown messages get a general sentence.
 */
const PATTERNS: readonly [RegExp, MessageKey][] = [
  [/compares a money with a number|needs a currency|is not a money amount/i, 'cellError.currency'],
  [/unexpected end of expression|expected an? (value|expression)/i, 'cellError.value'],
  [/unknown input/i, 'cellError.choice'],
  [/compares a .+ with a/i, 'cellError.type'],
];

export function cellSentence(engineMessage: string): MessageKey {
  return PATTERNS.find(([pattern]) => pattern.test(engineMessage))?.[1] ?? 'cellError.other';
}

/** A format hint for a refused cell, with its parameters. */
export interface CellHint { key: MessageKey; params: Readonly<Record<string, string>> }

/**
 * What to type in a refused cell whose amount field is empty: an amount, with the cell's saved value as the example
 * when it had one. This is a format hint drawn from the column's type and the editor's own field, never a business
 * value: the engine's sentence stays beside it as the detail. Undefined when the amount field is not empty, so the
 * engine's sentence is shown alone.
 */
export function emptyAmountHint(model: { kind: string; amount?: string; currency?: string | undefined }, example: string | undefined, currency: string | undefined): CellHint | undefined {
  if ((model.kind !== 'compare' && model.kind !== 'money') || (model.amount ?? '').trim() !== '') return undefined;
  if (example !== undefined) return { key: 'cellHint.amountExample', params: { example } };
  const code = model.currency ?? currency;
  return code !== undefined ? { key: 'cellHint.amountIn', params: { currency: code } } : { key: 'cellHint.number', params: {} };
}
