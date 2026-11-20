import type { MessageKey } from '../../../i18n/en';

/*
 * Why the engine refused one place of a document, as a short clause in the user's language that follows the
 * place ("card-fee.yaml, row R4, column Balance: compares a money amount with a plain number"). The engine's
 * English sentence is the fallback for a message not listed here, marked as English for screen readers.
 */
const REASONS: readonly [RegExp, MessageKey][] = [
  [/compares a money with a number/i, 'reason.moneyVsNumber'],
  [/needs a currency|is not a money amount/i, 'reason.needsCurrency'],
  [/compares an? \S+ with an? /i, 'reason.typeMismatch'],
  [/unknown (input|output|column)/i, 'reason.unknownColumn'],
  [/unexpected end of expression|expected an? (value|expression)/i, 'reason.incomplete'],
  [/duplicate (rule )?id/i, 'reason.duplicateRow'],
];

export function refusalReason(engineMessage: string): MessageKey | undefined {
  return REASONS.find(([pattern]) => pattern.test(engineMessage))?.[1];
}
