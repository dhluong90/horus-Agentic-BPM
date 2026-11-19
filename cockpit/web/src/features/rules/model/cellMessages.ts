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
