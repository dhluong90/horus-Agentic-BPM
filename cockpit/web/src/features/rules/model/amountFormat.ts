import type { RuleSetDefinition } from '../../../api/rules/schemas';
import type { MessageKey } from '../../../i18n/en';
import { inputKind, outputKind, readConditionCell, readOutputCell } from './cells';
import type { ErrorPlace } from './engineErrors';
import type { EditableRow } from './table';

/*
 * The one basic format check the grid makes itself: an amount typed into a money or number cell must be a plain
 * number ("50000000", "12.5", "-5"). Anything else ("abc") is kept in the cell, marked, and blocks Save draft until
 * fixed. What an amount may be (scale, sign, limits) stays the engine's decision.
 */

const NUMBER = /^-?\d+(?:\.\d+)?$/;

/** True when the text of an amount field is something other than an empty field or a plain number. */
export function isMalformedAmount(amount: string): boolean {
  return amount !== '' && !NUMBER.test(amount);
}

/** The cells of `rows` whose typed amount is not a number, as cell problems in the reader's language. */
export function amountProblems(definition: RuleSetDefinition, rows: readonly EditableRow[], message: (key: MessageKey) => string): ErrorPlace[] {
  const found: ErrorPlace[] = [];
  for (const row of rows) {
    for (const input of definition.inputs) {
      const kind = inputKind(input);
      if (kind !== 'money' && kind !== 'number') continue;
      const model = readConditionCell(row.when[input.name], kind);
      // A comparison with no currency may name another input (">= income"): only the engine can tell.
      const typed = model.kind === 'compare' && (model.currency !== undefined || /^[\d.,+-]/.test(model.amount));
      if (typed && isMalformedAmount(model.amount)) found.push({ kind: 'cell', ruleId: row.id, column: input.name, message: message('cellError.amount') });
    }
    for (const output of definition.outputs) {
      if (outputKind(output) !== 'money') continue;
      const model = readOutputCell(row.then[output.name] ?? '', 'money', undefined);
      if (model.kind === 'money' && isMalformedAmount(model.amount)) found.push({ kind: 'cell', ruleId: row.id, column: output.name, message: message('cellError.amount') });
    }
  }
  return found;
}
