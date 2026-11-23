/*
 * The mock engine's reader of rule rows (rule-engine/api/openapi.yaml readVersion, readDiff, simulateReadings).
 * It stands in for the engine's message catalogue so the dev server and the tests have realistic sentences; the
 * cockpit itself never builds a reading. English and Vietnamese, a full sentence and a short form, money grouped
 * for the language, and a masked input named without its test.
 */

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface ReadableRule { id: string; when: Record<string, string>; then: Record<string, Json> }
export interface ReadableDefinition {
  ruleSet: string;
  inputs: { name: string; description?: string; mask?: boolean }[];
  outputs: { name: string; description?: string }[];
  rules: ReadableRule[];
}

type Language = 'en' | 'vi';
export const LANGUAGES: readonly Language[] = ['en', 'vi'];
export interface Reading { full: string; short: string; source: 'GENERATED' }
export type Readings = Record<Language, Reading>;

const WORDS = {
  en: { rule: 'Rule', applies: 'applies when', always: 'always applies; it has no condition', then: 'Then', and: 'and', is: 'is', atLeast: 'is at least', atMost: 'is at most', above: 'is more than', below: 'is less than', not: 'is not', oneOf: 'is one of', masked: 'is masked; its test is not shown', yes: 'Yes', no: 'No', result: 'Result', noMatch: (name: string, v: number) => `No rule of ${name} version ${String(v)} matched. No output was produced.`, noMatchShort: 'No rule matched' },
  vi: { rule: 'Quy tắc', applies: 'áp dụng khi', always: 'luôn áp dụng; không có điều kiện', then: 'Khi đó', and: 'và', is: 'là', atLeast: 'ít nhất là', atMost: 'nhiều nhất là', above: 'lớn hơn', below: 'nhỏ hơn', not: 'khác', oneOf: 'là một trong', masked: 'được che; điều kiện không hiển thị', yes: 'Có', no: 'Không', result: 'Kết quả', noMatch: (name: string, v: number) => `Không quy tắc nào của ${name} phiên bản ${String(v)} khớp. Không có kết quả.`, noMatchShort: 'Không quy tắc nào khớp' },
} as const;

function group(digits: string, language: Language): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, language === 'vi' ? '.' : ',');
}

function label(name: string, description: string | undefined): string {
  if (description !== undefined && description.trim() !== '' && description.length <= 40) return description;
  return name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
}

function literal(raw: string, language: Language): string {
  const text = raw.trim();
  const money = /^(-?\d+)\s+([A-Z]{3})$/.exec(text);
  if (money) return `${group(money[1] ?? '', language)} ${money[2] ?? ''}`;
  if (/^-?\d+$/.test(text)) return group(text, language);
  if (/^'.*'$/.test(text)) return text.slice(1, -1);
  if (text === 'true') return WORDS[language].yes;
  if (text === 'false') return WORDS[language].no;
  return text;
}

function value(raw: Json, language: Language): string {
  if (typeof raw === 'boolean') return raw ? WORDS[language].yes : WORDS[language].no;
  if (typeof raw === 'number') return group(String(raw), language);
  if (typeof raw === 'string') return literal(raw, language);
  return JSON.stringify(raw);
}

const COMPARE: Record<string, 'atLeast' | 'atMost' | 'above' | 'below'> = { '>=': 'atLeast', '<=': 'atMost', '>': 'above', '<': 'below' };
const SHORT_WORDS = {
  en: { atLeast: 'at least', atMost: 'at most', above: 'over', below: 'under', not: 'not', oneOf: 'one of', masked: '(masked)', always: 'Any other case' },
  vi: { atLeast: 'ít nhất', atMost: 'tối đa', above: 'trên', below: 'dưới', not: 'khác', oneOf: 'một trong', masked: '(đã che)', always: 'Mọi trường hợp khác' },
} as const;

/** The author's Vietnamese column labels in the seed tables, as an author would write them in the rule table. */
const VI_LABELS: Record<string, string> = { tier: 'Hạng', balance: 'Số dư', txCount: 'Số giao dịch', fee: 'phí', waived: 'miễn phí', route: 'tuyến xử lý', segment: 'Phân khúc', nationalId: 'Số định danh' };

function nameOf(name: string, description: string | undefined, language: Language, input: boolean): string {
  if (language === 'vi' && VI_LABELS[name] !== undefined) return VI_LABELS[name];
  const base = label(name, description);
  return input ? base.charAt(0).toUpperCase() + base.slice(1) : base;
}

/** A code is worded as the rule table shows it: GOLD is Gold, FIRST_TIME is First time. */
function worded(raw: string, language: Language): string {
  const text = literal(raw, language);
  return /^[A-Z][A-Z0-9_]*$/.test(text) && /[A-Z]{2}/.test(text) ? text.charAt(0) + text.slice(1).toLowerCase().replaceAll('_', ' ') : text;
}

interface Test { full: string; short: string }

/**
 * A full expression of nested and, or and not (the condition builder writes these) as words: each comparison becomes its
 * sentence, and the connectors become the language's words, so the mock never shows an expression in a reading.
 */
function compoundWords(text: string, definition: ReadableDefinition, language: Language): string {
  const w = WORDS[language];
  const sw = SHORT_WORDS[language];
  const who = (name: string) => nameOf(name, definition.inputs.find((i) => i.name === name)?.description, language, true);
  const items = (list: string) => list.split(',').map((item) => worded(item, language)).join(', ');
  const verb = (op: string) => (op === '==' ? w.is : op === '!=' ? w.not : w[COMPARE[op] ?? 'atLeast']);
  const empty = language === 'vi' ? 'để trống' : 'is empty';
  const filled = language === 'vi' ? 'không để trống' : 'is not empty';
  return text
    .replace(/(\w+)\s+not in\s*\[([^\]]*)\]/g, (_m, n: string, l: string) => `${who(n)} ${sw.not} ${sw.oneOf} ${items(l)}`)
    .replace(/(\w+)\s+in\s*\[([^\]]*)\]/g, (_m, n: string, l: string) => `${who(n)} ${w.oneOf} ${items(l)}`)
    .replace(/(\w+)\s+is not null/g, (_m, n: string) => `${who(n)} ${filled}`)
    .replace(/(\w+)\s+is null/g, (_m, n: string) => `${who(n)} ${empty}`)
    .replace(/(\w+)\s*(==|!=|>=|<=|>|<)\s*('[^']*'|\d+(?:\.\d+)?(?:\s+[A-Z]{3})?|true|false)/g, (_m, n: string, op: string, v: string) => `${who(n)} ${verb(op)} ${worded(v, language)}`)
    .replace(/\bnot\s*\(/g, language === 'vi' ? 'không phải (' : 'not (')
    .replace(/\band\b/g, w.and)
    .replace(/\bor\b/g, language === 'vi' ? 'hoặc' : 'or');
}

function condition(name: string, cell: string, definition: ReadableDefinition, language: Language): Test {
  const input = definition.inputs.find((i) => i.name === name);
  const who = nameOf(name, input?.description, language, true);
  const w = WORDS[language];
  const sw = SHORT_WORDS[language];
  if (input?.mask === true) return { full: `${who} ${w.masked}`, short: `${who} ${sw.masked}` };
  const text = cell.trim();
  if (/\b(and|or)\b|^not\b|\bnot\s*\(/.test(text) && !/^(==|!=|<|>|in\s|in\[|not in|is\s)/.test(text)) {
    const words = compoundWords(text, definition, language);
    return { full: words, short: words };
  }
  let m = /^(==|!=)\s*(.+)$/.exec(text);
  if (m) {
    const operand = worded(m[2] ?? '', language);
    const equal = m[1] === '==';
    const isText = /^'.*'$/.test((m[2] ?? '').trim());
    const short = equal ? (isText && language === 'en' ? operand : `${who} ${operand}`) : `${who} ${sw.not} ${operand}`;
    return { full: `${who} ${equal ? w.is : w.not} ${operand}`, short };
  }
  m = /^in\s*\[(.*)\]$/.exec(text);
  if (m) {
    const items = (m[1] ?? '').split(',').map((item) => worded(item, language)).join(', ');
    return { full: `${who} ${w.oneOf} ${items}`, short: `${who} ${sw.oneOf} ${items}` };
  }
  m = /^(>=|<=|>|<)\s*(.+)$/.exec(text);
  if (m) {
    const key = COMPARE[m[1] ?? ''] ?? 'atLeast';
    return { full: `${who} ${w[key]} ${literal(m[2] ?? '', language)}`, short: `${who} ${sw[key]} ${literal(m[2] ?? '', language)}` };
  }
  return { full: `${who}: ${text}`, short: `${who}: ${text}` };
}

function outputs(rule: ReadableRule, definition: ReadableDefinition, language: Language): Test {
  const parts = definition.outputs.filter((o) => o.name in rule.then).map((o) => {
    const raw = rule.then[o.name] ?? null;
    const what = typeof raw === 'string' ? worded(raw, language) : value(raw, language);
    return { who: nameOf(o.name, o.description, language, false), what, flag: typeof raw === 'boolean' };
  });
  return { full: parts.map((p) => `${p.who} ${WORDS[language].is} ${p.what}`).join('; '), short: parts.map((p) => (p.flag ? `${p.who}: ${p.what}` : `${p.who} ${p.what}`)).join('; ') };
}

function readIn(rule: ReadableRule, definition: ReadableDefinition, language: Language, result: boolean): Reading {
  const w = WORDS[language];
  const tests = Object.entries(rule.when).filter(([, cell]) => cell.trim() !== '').map(([name, cell]) => condition(name, cell, definition, language));
  const then = outputs(rule, definition, language);
  const lead = tests.length === 0 ? `${w.rule} ${rule.id} ${w.always}.` : `${w.rule} ${rule.id} ${w.applies} ${tests.map((c) => c.full).join(` ${w.and} `)}.`;
  const full = `${lead} ${result ? `${w.result}:` : w.then} ${then.full}.`;
  const short = result ? `${rule.id}: ${then.short}` : `${tests.length === 0 ? SHORT_WORDS[language].always : tests.map((c) => c.short).join(', ')}: ${then.short}`;
  return { full, short, source: 'GENERATED' };
}

export function readRule(rule: ReadableRule, definition: ReadableDefinition, result = false): Readings {
  return { en: readIn(rule, definition, 'en', result), vi: readIn(rule, definition, 'vi', result) };
}

export function readNoMatch(name: string, version: number): Readings {
  const of = (language: Language): Reading => ({ full: WORDS[language].noMatch(name, version), short: WORDS[language].noMatchShort, source: 'GENERATED' });
  return { en: of('en'), vi: of('vi') };
}

export function sameReadings(a: Readings | null, b: Readings | null): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
