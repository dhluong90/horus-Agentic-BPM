import type { Locale } from '../../../i18n/I18nProvider';
import type { Money, ThenValue } from '../../../api/rules/schemas';
import { formatDecimalText, formatMoney } from './money';

/*
 * How a cell reads in a view: wildcards as a dash, literals as human values with grouped amounts, the
 * common condition shapes as an operator and values. This is display only: the engine parses and validates
 * every cell, and anything not recognised here is shown as the engine's text, unchanged.
 */

export type Value = { kind: 'text'; text: string } | { kind: 'yes' } | { kind: 'no' } | { kind: 'empty' };

export type CellReading =
  | { kind: 'any' }
  | { kind: 'values'; negated: boolean; values: Value[] }
  | { kind: 'compare'; operator: '≥' | '≤' | '>' | '<'; value: Value }
  | { kind: 'value'; value: Value }
  | { kind: 'group' }
  | { kind: 'raw'; text: string };

const MONEY_LITERAL = /^(-?\d+(?:\.\d+)?)\s+([A-Z]{3})$/;
const NUMBER_LITERAL = /^-?\d+(?:\.\d+)?$/;
const QUOTED = /^'((?:[^'\\]|\\.)*)'$/;

/** One literal of the expression language as a human value, or undefined when the text is not a literal. */
export function readLiteral(text: string, locale: Locale): Value | undefined {
  const trimmed = text.trim();
  const money = MONEY_LITERAL.exec(trimmed);
  if (money) return { kind: 'text', text: `${formatDecimalText(money[1] ?? '', locale) ?? ''} ${money[2] ?? ''}` };
  if (NUMBER_LITERAL.test(trimmed)) return { kind: 'text', text: formatDecimalText(trimmed, locale) ?? trimmed };
  const quoted = QUOTED.exec(trimmed);
  if (quoted) return { kind: 'text', text: (quoted[1] ?? '').replaceAll("\\'", "'") };
  if (trimmed === 'true') return { kind: 'yes' };
  if (trimmed === 'false') return { kind: 'no' };
  if (trimmed === 'null') return { kind: 'empty' };
  return undefined;
}

function readList(body: string, locale: Locale): Value[] | undefined {
  const items = body.split(',').map((item) => readLiteral(item, locale));
  return items.every((item): item is Value => item !== undefined) ? items : undefined;
}

const COMPARE = /^(>=|<=|>|<)\s*(.+)$/;
const OPERATOR: Record<string, '≥' | '≤' | '>' | '<'> = { '>=': '≥', '<=': '≤', '>': '>', '<': '<' };

/** A condition cell of the `when` part. */
export function readCondition(cell: string | readonly unknown[] | undefined, locale: Locale): CellReading {
  if (cell === undefined) return { kind: 'any' };
  if (typeof cell !== 'string') return { kind: 'group' };
  const text = cell.trim();
  if (text === '') return { kind: 'any' };
  const equals = /^(==|!=)\s*(.+)$/.exec(text);
  if (equals) {
    const value = readLiteral(equals[2] ?? '', locale);
    if (value) return { kind: 'values', negated: equals[1] === '!=', values: [value] };
  }
  const list = /^(not\s+)?in\s*\[(.*)\]$/.exec(text);
  if (list) {
    const values = readList(list[2] ?? '', locale);
    if (values) return { kind: 'values', negated: list[1] !== undefined, values };
  }
  const compare = COMPARE.exec(text);
  if (compare) {
    const value = readLiteral(compare[2] ?? '', locale);
    const operator = OPERATOR[compare[1] ?? ''];
    if (value && operator) return { kind: 'compare', operator, value };
  }
  return { kind: 'raw', text };
}

/** An output cell of the `then` part. */
export function readOutput(cell: ThenValue | undefined, locale: Locale): CellReading {
  if (cell === undefined || cell === null) return { kind: 'any' };
  if (typeof cell === 'boolean') return { kind: 'value', value: { kind: cell ? 'yes' : 'no' } };
  if (typeof cell === 'number') return { kind: 'value', value: { kind: 'text', text: formatDecimalText(String(cell), locale) ?? String(cell) } };
  const value = readLiteral(cell, locale);
  return value ? { kind: 'value', value } : { kind: 'raw', text: cell };
}

/** A value the engine returned in a decision: money object, boolean, number or text. */
export function readResult(value: unknown, locale: Locale): Value {
  if (value === null || value === undefined) return { kind: 'empty' };
  if (typeof value === 'boolean') return { kind: value ? 'yes' : 'no' };
  if (typeof value === 'number') return { kind: 'text', text: formatDecimalText(String(value), locale) ?? String(value) };
  if (typeof value === 'string') return { kind: 'text', text: formatDecimalText(value, locale) ?? value };
  if (isMoney(value)) return { kind: 'text', text: formatMoney(value, locale) };
  if (Array.isArray(value)) return { kind: 'text', text: value.map((item) => valueText(readResult(item, locale))).join(', ') };
  return { kind: 'text', text: JSON.stringify(value) };
}

function isMoney(value: unknown): value is Money {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate['currency'] === 'string' && typeof candidate['amount'] === 'number';
}

/** Plain text of a value for joining; yes and no are translated by the caller's components. */
function valueText(value: Value): string {
  return value.kind === 'text' ? value.text : value.kind;
}

/** A column label from an attribute name: its description when present, else the name in words. */
export function columnLabel(name: string, description: string | undefined): string {
  if (description !== undefined && description.trim() !== '' && description.length <= 40) return description.charAt(0).toUpperCase() + description.slice(1);
  const words = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replaceAll('_', ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
