import type { Locale } from '../../../i18n/I18nProvider';
import { currencyDigits, formatDecimalText, toMinorUnits } from '../../rules/model/money';
import { fieldLabel, isMoney } from '../model/review';

/*
 * What the form screen of a task draws and sends (CKP-021). The engine decides everything that matters: which fields
 * exist, which are required in which case, and whether the result meets the schema. This module only reads the
 * schema and the hints it was given and checks the format of what the operator typed. A rule it does not know
 * (a field required only when another has a value) is left to the engine's 422, shown under the field it names.
 */

export type FieldKind =
  | { kind: 'text'; multiline: boolean; maxLength: number | undefined }
  | { kind: 'number'; integer: boolean; grouped: boolean }
  | { kind: 'money'; currencies: readonly string[] }
  | { kind: 'date' }
  | { kind: 'datetime' }
  | { kind: 'choice'; options: readonly string[]; radio: boolean }
  | { kind: 'yesno' };

export interface FormField {
  name: string;
  label: string;
  kind: FieldKind;
  required: boolean;
  readOnly: boolean;
  /** What each choice is called in the user's language, from the hints; a code with no label is shown in words (see `optionText`). */
  optionLabels: Readonly<Record<string, string>>;
  /** What choosing each option leads to, one short sentence per option, from the hints. */
  effects: Readonly<Record<string, string>>;
  /** A unit or currency shown after the input ("VND", "months"), from the `units` and `formats` hints. */
  unit: string | undefined;
  /** The schema's `if`/`then` makes this field required while another field has the given value. */
  requiredWhen: { field: string; equals: string } | undefined;
  /** The schema marks it `mask: true` and the task holds a value: the screen never shows or sends it. */
  masked: boolean;
}

type Json = Readonly<Record<string, unknown>>;

const asRecord = (value: unknown): Json | undefined => (typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Json) : undefined);
const asStrings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []);

function kindOf(property: Json, widget: string | undefined, grouping = false): FieldKind | undefined {
  const options = asStrings(property['enum']);
  if (options.length > 0) return { kind: 'choice', options, radio: widget === 'radio' || (widget === undefined && options.length <= 3) };
  switch (property['type']) {
    case 'string': {
      if (property['format'] === 'date') return { kind: 'date' };
      if (property['format'] === 'date-time') return { kind: 'datetime' };
      const max = property['maxLength'];
      return { kind: 'text', multiline: widget === 'textarea' || (typeof max === 'number' && max > 120), maxLength: typeof max === 'number' ? max : undefined };
    }
    case 'integer':
      return { kind: 'number', integer: true, grouped: grouping || widget === 'grouped' };
    case 'number':
      return { kind: 'number', integer: false, grouped: grouping || widget === 'grouped' };
    case 'boolean':
      return { kind: 'yesno' };
    case 'object': {
      const currency = asRecord(asRecord(property['properties'])?.['currency']);
      const fixed = typeof currency?.['const'] === 'string' ? [currency['const']] : asStrings(currency?.['enum']);
      return isMoneySchema(property) ? { kind: 'money', currencies: fixed } : undefined;
    }
    default:
      return undefined;
  }
}

function isMoneySchema(property: Json): boolean {
  const properties = asRecord(property['properties']);
  return properties !== undefined && Object.keys(properties).sort().join() === 'amount,currency';
}

const localized = (value: unknown, locale: Locale): string | undefined => {
  if (typeof value === 'string') return value;
  const named = asRecord(value);
  return typeof named?.[locale] === 'string' ? named[locale] : typeof named?.['en'] === 'string' ? named['en'] : undefined;
};

/** The `options` hint of one enum field: per value, a `label` and an optional `consequence`, each per language. */
const optionTexts = (value: unknown, member: 'label' | 'consequence', locale: Locale): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const [code, entry] of Object.entries(asRecord(value) ?? {})) {
    const text = localized(asRecord(entry)?.[member], locale);
    if (text !== undefined) out[code] = text;
  }
  return out;
};

/** The currency a `formats` hint names: a fixed code, or the code held by another field of the task's data. */
const currencyOf = (format: Json | undefined, data: Json): string | undefined => {
  if (typeof format?.['currency'] === 'string') return format['currency'];
  const from = format?.['currencyFrom'];
  const held = typeof from === 'string' ? data[from] : undefined;
  return typeof held === 'string' ? held : undefined;
};

/** `if: {required:[x], properties:{x:{const:v}}}, then: {required:[y]}` as "y is required while x is v". */
function requiredWhenOf(schema: Json): Map<string, { field: string; equals: string }> {
  const out = new Map<string, { field: string; equals: string }>();
  const condition = asRecord(schema['if']);
  const properties = asRecord(condition?.['properties']);
  const entry = properties === undefined ? undefined : Object.entries(properties)[0];
  const equals = asRecord(entry?.[1])?.['const'];
  if (entry === undefined || typeof equals !== 'string') return out;
  for (const name of asStrings(asRecord(schema['then'])?.['required'])) out.set(name, { field: entry[0], equals });
  return out;
}

/** A field is required by the schema, or while the field its `if` names has the value it names. */
export function isRequired(field: FormField, drafts: Drafts): boolean {
  return field.required || (field.requiredWhen !== undefined && drafts[field.requiredWhen.field] === field.requiredWhen.equals);
}

/** The fields of the form in the hints' order, then the rest in the schema's order. A field the cockpit cannot draw is left out. */
export function fieldsOf(form: { schema: Json; hints: Json; data: Json }, locale: Locale): FormField[] {
  const properties = asRecord(form.schema['properties']) ?? {};
  const required = new Set(asStrings(form.schema['required']));
  const widgets = asRecord(form.hints['widgets']) ?? {};
  const labels = asRecord(form.hints['labels']) ?? {};
  const readOnly = new Set(asStrings(form.hints['readOnly']));
  const order = asStrings(form.hints['order']).filter((name) => name in properties);
  const names = [...order, ...Object.keys(properties).filter((name) => !order.includes(name))];
  const fields: FormField[] = [];
  const conditional = requiredWhenOf(form.schema);
  const options = asRecord(form.hints['options']) ?? {};
  const units = asRecord(form.hints['units']) ?? {};
  const formats = asRecord(form.hints['formats']) ?? {};
  for (const name of names) {
    const property = asRecord(properties[name]);
    if (property === undefined) continue;
    const widget = typeof widgets[name] === 'string' ? widgets[name] : undefined;
    const format = asRecord(formats[name]);
    const kind = kindOf(property, widget, format?.['grouping'] === true);
    if (kind === undefined) continue;
    const named = asRecord(labels[name]);
    const label = typeof named?.[locale] === 'string' ? named[locale] : typeof named?.['en'] === 'string' ? named['en'] : fieldLabel(name);
    fields.push({
      name,
      label,
      kind,
      required: required.has(name),
      readOnly: readOnly.has(name) || widget === 'readonly',
      optionLabels: optionTexts(options[name], 'label', locale),
      effects: optionTexts(options[name], 'consequence', locale),
      unit: localized(units[name], locale) ?? currencyOf(format, form.data),
      requiredWhen: conditional.get(name),
      masked: property['mask'] === true && form.data[name] !== undefined && form.data[name] !== null,
    });
  }
  return fields;
}

/** What the operator typed, by field name; money keeps its currency apart under `<name>.currency`. */
export type Drafts = Readonly<Record<string, string>>;

/** What a choice is called: its hinted label, else the code in words ("APPROVED" is "Approved"), never the raw code. */
export const optionText = (field: Pick<FormField, 'optionLabels'>, code: string): string => field.optionLabels[code] ?? inWords(code);

/** "APPROVED" as "Approved", "NEEDS_REVIEW" as "Needs review": a code with no label is still read as words. */
const inWords = (code: string): string => {
  const text = code.split(/[\s_-]+/).filter((word) => word !== '').join(' ').toLowerCase();
  return text === '' ? code : text.charAt(0).toUpperCase() + text.slice(1);
};

export const currencyKey = (name: string) => `${name}.currency`;

/** Integer minor units as the decimal text of major units, by string arithmetic: {VND, 2500000} -> "2500000", {USD, 1250} -> "12.50". */
function minorToText(amount: number, currency: string): string {
  const digits = currencyDigits(currency);
  const negative = amount < 0;
  const padded = BigInt(negative ? -amount : amount).toString().padStart(digits + 1, '0');
  const integer = digits === 0 ? padded : padded.slice(0, -digits);
  const fraction = digits === 0 ? '' : `.${padded.slice(-digits)}`;
  return `${negative ? '-' : ''}${integer}${fraction}`;
}

/** A moment as the local `datetime-local` text the browser's field takes. */
function localInput(iso: string): string {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return '';
  const two = (n: number) => String(n).padStart(2, '0');
  return `${String(when.getFullYear())}-${two(when.getMonth() + 1)}-${two(when.getDate())}T${two(when.getHours())}:${two(when.getMinutes())}`;
}

/** The starting texts: the task's prefilled values, in the language's separators. A masked value is never put in a text. */
export function initialDrafts(fields: readonly FormField[], data: Json, locale: Locale): Drafts {
  const drafts: Record<string, string> = {};
  for (const field of fields) {
    const value = data[field.name];
    if (field.masked) continue;
    if (field.kind.kind === 'money') {
      const currency = isMoney(value) ? value.currency : field.kind.currencies[0] ?? '';
      drafts[currencyKey(field.name)] = currency;
      if (isMoney(value)) {
        const plain = minorToText(value.amount, value.currency);
        drafts[field.name] = (plain.startsWith('-') ? '-' : '') + (formatDecimalText(plain.replace('-', ''), locale) ?? plain);
      }
      continue;
    }
    if (value === undefined || value === null) continue;
    if (typeof value === 'string') drafts[field.name] = field.kind.kind === 'datetime' ? localInput(value) : value;
    else if (typeof value === 'number') {
      drafts[field.name] = field.kind.kind === 'number' && field.kind.grouped ? (formatDecimalText(String(value), locale) ?? String(value)) : locale === 'vi' ? String(value).replace('.', ',') : String(value);
    }
    else if (typeof value === 'boolean') drafts[field.name] = String(value);
  }
  return drafts;
}

/** One line of the recorded decision: what the field is called and the value as the reader reads it; `masked` and `missing` carry no text. */
export interface RecordedRow {
  name: string;
  label: string;
  text: string | undefined;
  masked: boolean;
  wide: boolean;
}

const isoDay = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The values a completed task's form holds, as read-only text: a choice by its label, money in the language's
 * separators and the currency's minor units, a masked field never as its value. A field with no value has no text.
 */
export function recordedRows(fields: readonly FormField[], data: Json, locale: Locale, words: { yes: string; no: string; when: (iso: string) => string | undefined }): RecordedRow[] {
  return fields.map((field) => {
    const value = data[field.name];
    const wide = field.kind.kind === 'text' && field.kind.multiline;
    const row = (text: string | undefined): RecordedRow => ({ name: field.name, label: field.label, text, masked: field.masked, wide });
    if (field.masked) return row(undefined);
    if (value === undefined || value === null || value === '') return row(undefined);
    const unit = field.unit !== undefined ? ` ${field.unit}` : '';
    switch (field.kind.kind) {
      case 'money': {
        if (!isMoney(value)) return row(undefined);
        const plain = minorToText(value.amount, value.currency);
        const negative = plain.startsWith('-');
        return row(`${negative ? '-' : ''}${formatDecimalText(plain.replace('-', ''), locale) ?? plain} ${value.currency}`);
      }
      case 'number': {
        if (typeof value !== 'number' && typeof value !== 'string') return row(undefined);
        const raw = String(value);
        return row(`${field.kind.grouped ? formatDecimalText(raw, locale) ?? raw : locale === 'vi' ? raw.replace('.', ',') : raw}${unit}`);
      }
      case 'choice':
        return row(typeof value === 'string' ? optionText(field, value) : undefined);
      case 'yesno':
        return row(typeof value === 'boolean' ? (value ? words.yes : words.no) : undefined);
      case 'datetime':
        return row(typeof value === 'string' ? words.when(value) ?? value : undefined);
      case 'date':
        return row(typeof value === 'string' && isoDay.test(value) ? words.when(`${value}T00:00:00Z`)?.replace(/ \d{2}:\d{2}$/, '') ?? value : undefined);
      case 'text':
        return row(typeof value === 'string' ? `${value}${unit}` : undefined);
    }
  });
}

/** A masked field as the engine stores it: the marker, never the value. */
const isMarker = (value: unknown): boolean => typeof value === 'object' && value !== null && !Array.isArray(value) && (value as Record<string, unknown>)['masked'] === true;

/**
 * The lines of the decision as it was recorded: the stored submission read through the form's own labels, units and
 * formats. A masked field is the engine's marker, so it is `masked` whatever the case holds now.
 */
export function submissionRows(form: { schema: Json; hints: Json }, submission: Json, locale: Locale, words: { yes: string; no: string; when: (iso: string) => string | undefined }): RecordedRow[] {
  const fields = fieldsOf({ ...form, data: submission }, locale).map((field) => ({ ...field, masked: isMarker(submission[field.name]) }));
  return recordedRows(fields, submission, locale, words);
}

export type Problem = 'required' | 'number' | 'integer' | 'money' | 'moneyScale' | 'moneyRange' | 'moneyCurrency' | 'date' | 'datetime' | 'choice' | 'tooLong';

export interface Built {
  result: Record<string, unknown>;
  problems: Record<string, Problem>;
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

function validDate(text: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().startsWith(text);
}

/**
 * The result to send and the format problems found. A field left empty is omitted; a masked field the operator did
 * not replace is omitted too, and one they replaced goes as typed. Only format is checked here: whether the result
 * meets the form is the engine's check (422 FORM_VALIDATION_FAILED).
 */
export function build(fields: readonly FormField[], drafts: Drafts, data: Json, locale: Locale, replaced: ReadonlySet<string>): Built {
  const result: Record<string, unknown> = {};
  const problems: Record<string, Problem> = {};
  for (const field of fields) {
    const name = field.name;
    if (field.readOnly) {
      if (!field.masked && data[name] !== undefined && data[name] !== null) result[name] = data[name];
      continue;
    }
    const required = isRequired(field, drafts);
    if (field.masked && !replaced.has(name)) {
      if (required) problems[name] = 'required';
      continue;
    }
    const text = (drafts[name] ?? '').trim();
    if (text === '') {
      if (required) problems[name] = 'required';
      continue;
    }
    switch (field.kind.kind) {
      case 'text':
        if (field.kind.maxLength !== undefined && text.length > field.kind.maxLength) problems[name] = 'tooLong';
        else result[name] = text;
        break;
      case 'number': {
        const ungrouped = field.kind.grouped ? text.split(locale === 'vi' ? '.' : ',').join('') : text;
        const plain = locale === 'vi' ? ungrouped.replace(',', '.') : ungrouped;
        if (!DECIMAL.test(plain)) problems[name] = 'number';
        else if (field.kind.integer && (!/^-?\d+$/.test(plain) || !Number.isSafeInteger(Number(plain)))) problems[name] = 'integer';
        else result[name] = Number(plain);
        break;
      }
      case 'money': {
        const currency = (drafts[currencyKey(name)] ?? field.kind.currencies[0] ?? '').trim();
        if (!/^[A-Z]{3}$/.test(currency)) {
          problems[name] = 'moneyCurrency';
          break;
        }
        const minor = toMinorUnits(text, currency, locale);
        if (minor.ok) result[name] = { currency, amount: minor.amount };
        else problems[name] = minor.reason === 'scale' ? 'moneyScale' : minor.reason === 'range' ? 'moneyRange' : 'money';
        break;
      }
      case 'date':
        if (validDate(text)) result[name] = text;
        else problems[name] = 'date';
        break;
      case 'datetime': {
        const when = new Date(text);
        if (Number.isNaN(when.getTime())) problems[name] = 'datetime';
        else result[name] = when.toISOString();
        break;
      }
      case 'choice':
        if (field.kind.options.includes(text)) result[name] = text;
        else problems[name] = 'choice';
        break;
      case 'yesno':
        result[name] = text === 'true';
        break;
    }
  }
  return { result, problems };
}

export interface ApiFieldErrors {
  /** The engine's words by field name, from the JSON Pointer of each failure. */
  byField: Record<string, string>;
  /** Failures of the whole result, with no field. */
  other: string[];
}

/** The 422 FORM_VALIDATION_FAILED `errors`: one `{pointer, rule}` per failing field. Never a submitted value. */
export function apiErrorsOf(errors: unknown): ApiFieldErrors {
  const out: ApiFieldErrors = { byField: {}, other: [] };
  if (!Array.isArray(errors)) return out;
  for (const item of errors) {
    const entry = asRecord(item);
    const rule = typeof entry?.['rule'] === 'string' ? entry['rule'] : undefined;
    if (rule === undefined) continue;
    const pointer = typeof entry?.['pointer'] === 'string' ? entry['pointer'] : '';
    const name = /^\/([^/]+)/.exec(pointer)?.[1]?.replace(/~1/g, '/').replace(/~0/g, '~');
    if (name === undefined) out.other.push(rule);
    else out.byField[name] = rule;
  }
  return out;
}
