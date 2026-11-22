/*
 * An in-memory stand-in for the Rule Engine REST API (rule-engine/api/openapi.yaml), for the dev server,
 * Vitest (through MSW) and Playwright (through page.route). It answers like the real engine on the paths the
 * cockpit uses: paged lists of rule sets and versions with a cursor, allowedActions computed for the caller,
 * history, the row-level diff of two versions, reject with a reason, and the problems the screens must show: 422 VALIDATION_FAILED with
 * violations, 403 SELF_APPROVAL_FORBIDDEN, 409 VERSION_CONFLICT, 400 QUERY_INVALID, 413 PAYLOAD_TOO_LARGE.
 * Export answers the canonical JSON document (keys sorted, no white space) or a small DMN 1.5 table, with
 * Content-Disposition; a POST repeated with the same Idempotency-Key replays the first answer.
 * It accepts JSON text only (JSON is YAML), which is what the cockpit's editor sends.
 */

import { createAuditRoutes, type AuditOptions } from './auditEngine';
import { readNoMatch, readRule, sameReadings, LANGUAGES, type Readings } from './ruleReadings';

/** The most rows or samples one readings request covers, as the engine's limit. */
const READINGS_MAX = 500;

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
interface Rule { id: string; when: Record<string, string>; then: Record<string, Json>; explain?: Partial<Record<'en' | 'vi', string>> }
interface Definition { schemaVersion: number; ruleSet: string; hitPolicy: string; inputs: { name: string; type: string; allowed?: string[]; mask?: boolean; description?: string }[]; outputs: { name: string; type: string }[]; rules: Rule[]; description?: string }

export interface StoredHistory { action: 'CREATED' | 'DRAFT_UPDATED' | 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'RETIRED' | 'WITHDRAWN' | 'DISCARDED'; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; lockVersion: number; occurredAt: string }

export interface StoredVersion {
  ruleSet: string;
  version: number;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'PUBLISHED' | 'RETIRED' | 'DISCARDED';
  lockVersion: number;
  createdBy: string;
  createdAt: string;
  requestedBy: string | null;
  requestedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  effectiveFrom: string | null;
  source: string;
  definition: Definition;
  history?: StoredHistory[];
}

export interface FakeRequest { method: string; path: string; headers: Record<string, string>; body: string; user: string; permissions: readonly string[] }
export interface FakeResponse { status: number; headers: Record<string, string>; body: string }

const TYPE = 'https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#';
let sequence = 0;
const correlation = () => `01JFAKE${String((sequence += 1)).padStart(19, '0')}`;
/** A stand-in for SHA-256: 64 hex digits from eight FNV-1a passes over the whole text, so any change shows. */
const hash = (text: string) => Array.from({ length: 8 }, (_, seed) => {
  let h = (0x811c9dc5 ^ seed) >>> 0;
  for (let i = 0; i < text.length; i += 1) h = Math.imul(h ^ text.charCodeAt(i), 0x01000193) >>> 0;
  return h.toString(16).padStart(8, '0');
}).join('');

interface Violation { ruleId: string | null; column: string | null; message: string; code: string }

/**
 * The engine's problem catalogue (explain_en / explain_vi.properties): one sentence per code, never the cell's text.
 * The `:validate` answer carries these by language code (RLE-090).
 */
const PROBLEM_CATALOGUE: Record<string, { en: string; vi: string }> = {
  SYNTAX_ERROR: { en: 'The cell is not a valid expression.', vi: 'Ô này không phải là biểu thức hợp lệ.' },
  UNKNOWN_INPUT: { en: 'The cell uses a name that is not a declared input.', vi: 'Ô này dùng một tên không phải là đầu vào đã khai báo.' },
  TYPE_MISMATCH: { en: 'The cell mixes values of different types, or is not a true or false condition.', vi: 'Ô này trộn các giá trị khác kiểu, hoặc không phải là điều kiện đúng hoặc sai.' },
  INVALID_LITERAL: { en: 'The cell has a date, time, money amount or number that is not written correctly.', vi: 'Ô này có ngày, giờ, số tiền hoặc số được viết sai.' },
  UNKNOWN_OUTPUT: { en: 'The rule sets a value for an output that is not declared.', vi: 'Quy tắc này gán giá trị cho một đầu ra chưa được khai báo.' },
  RULE_INVALID: { en: 'The rule cannot be accepted.', vi: 'Quy tắc này chưa được chấp nhận.' },
  COLUMN_INVALID: { en: 'The column cannot be accepted.', vi: 'Cột này chưa được chấp nhận.' },
  DOCUMENT_INVALID: { en: 'The document cannot be accepted.', vi: 'Tài liệu này chưa được chấp nhận.' },
};

/** At most this many problems in one `:validate` answer. */
const PROBLEM_LIMIT = 100;

function problem(status: number, code: string, detail: string, violations?: Violation[]): FakeResponse {
  const titles: Record<number, string> = { 400: 'Bad Request', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 413: 'Content Too Large', 422: 'Unprocessable Content' };
  return {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
    body: JSON.stringify({ type: TYPE + code.toLowerCase(), title: titles[status] ?? 'Error', status, code, detail, correlationId: correlation(), ...(violations ? { violations } : {}) }),
  };
}

const json = (body: unknown, extra: Record<string, string> = {}): FakeResponse => ({ status: 200, headers: { 'Content-Type': 'application/json', ...extra }, body: JSON.stringify(body) });

/** allowedActions for this caller, by status, permission and four eyes (rule-engine VersionActions). */
function actionsFor(v: StoredVersion, request: FakeRequest): string[] {
  const can = (permission: string) => request.permissions.includes(permission);
  // RLE-098: the author of a draft may also discard it.
  if (v.status === 'DRAFT') return can('rules:author') ? ['request-publish', ...(v.createdBy === request.user ? ['discard'] : [])] : [];
  if (v.status !== 'PENDING_APPROVAL' || !can('rules:approve')) return [];
  // PD-089: neither the requester nor the author may approve or reject; withdrawing is its own action.
  const self = v.requestedBy === request.user || v.createdBy === request.user;
  return self ? [] : ['approve', 'reject'];
}

/**
 * GET /approvals for this caller (RLE-190): what they may send, in the engine's order, and why approve and reject are
 * not offered. An empty `createdBy` stands for a version with no recorded author (PD-105).
 */
function pendingItem(v: StoredVersion, request: FakeRequest) {
  const approver = request.permissions.includes('rules:approve');
  const self = v.requestedBy === request.user || v.createdBy === request.user;
  const denial = !approver ? 'PERMISSION_REQUIRED' : self ? 'SELF_APPROVAL_FORBIDDEN' : v.createdBy === '' ? 'AUTHOR_UNKNOWN' : undefined;
  const withdraw = v.requestedBy === request.user && request.permissions.includes('rules:author');
  return {
    ruleSet: v.ruleSet, version: v.version, canonicalHash: hash(JSON.stringify(v.definition)), lockVersion: v.lockVersion,
    requestedBy: v.requestedBy ?? '', requestedAt: v.requestedAt ?? v.createdAt,
    allowedActions: [...(denial === undefined ? ['approve', 'reject'] : []), ...(withdraw ? ['withdraw'] : [])],
    deniedActions: denial === undefined ? {} : { approve: denial, reject: denial },
  };
}

function lifecycle(v: StoredVersion, request: FakeRequest) {
  return {
    ruleSet: v.ruleSet, version: v.version, status: v.status, hitPolicy: v.definition.hitPolicy.toUpperCase(), effectiveFrom: v.effectiveFrom,
    lockVersion: v.lockVersion, canonicalHash: hash(JSON.stringify(v.definition)),
    requestedBy: v.requestedBy, requestedAt: v.requestedAt, approvedBy: v.approvedBy, approvedAt: v.approvedAt,
    createdBy: v.createdBy, createdAt: v.createdAt, allowedActions: actionsFor(v, request),
  };
}

/** lifecycle.acknowledge-warnings at its default (PD-104). */
const ACKNOWLEDGE_WARNINGS: readonly string[] = ['UNMASKED_PERSONAL_DATA'];

/**
 * The fake engine's acknowledgement settings (PD-104, RLE-053): the codes the host lists, the tenant's
 * decision-log.mask-default, or, for a test, the engine's answer per warning given directly.
 */
export interface AcknowledgementSettings {
  acknowledgeWarnings?: readonly string[];
  maskDefault?: boolean;
  requiresAcknowledgement?: (warning: { code: string; input: string }) => boolean;
}

/** Whether an approval must accept a warning: one rule for the version view and the approve check, as the engine. */
type Marker = (warning: { code: string; input: string }) => boolean;

function markerOf({ acknowledgeWarnings = ACKNOWLEDGE_WARNINGS, maskDefault = false, requiresAcknowledgement }: AcknowledgementSettings): Marker {
  return requiresAcknowledgement ?? ((warning) => acknowledgeWarnings.includes(warning.code) && !maskDefault);
}

/** A part of the engine's built-in personal-data names (RLE-049), as words. */
const PERSONAL_DATA_NAMES = ['national id', 'passport', 'phone', 'phone number', 'email', 'address', 'date of birth', 'account number', 'cccd', 'cmnd'];

/** Splits an identifier into lower-case words: nationalId, national_id and NATIONAL_ID all give "national id". */
function words(name: string): string[] {
  return name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase().split(/[^a-z0-9]+/).filter((word) => word !== '');
}

/** The personal-data name an input's name matches as whole words, as the engine compares them; undefined when none. */
function personalDataName(input: string): string | undefined {
  const have = words(input).join(' ');
  const glued = words(input).join('');
  return PERSONAL_DATA_NAMES.find((name) => ` ${have} `.includes(` ${name} `) || glued === name.replaceAll(' ', ''));
}

/** UNMASKED_PERSONAL_DATA for every input that declares no mask and looks like personal data, marked by the engine. */
function warningsOf(definition: Definition, marks: Marker) {
  return definition.inputs.flatMap((input) => {
    const matched = input.mask === undefined ? personalDataName(input.name) : undefined;
    if (matched === undefined) return [];
    const warning = {
      code: 'UNMASKED_PERSONAL_DATA', input: input.name, matchedName: matched,
      message: `Input ${input.name} looks like personal data (it matches "${matched}") but declares no mask.`,
    };
    return [{ ...warning, requiresAcknowledgement: marks(warning) }];
  });
}

/** The tenant's decision-log.mask-default in this fake: off, so an undeclared input is not masked by default. */
function maskingStatus(input: Definition['inputs'][number] | undefined): string | null {
  if (input === undefined) return null;
  if (input.mask === true) return 'MASKED';
  if (input.mask === false) return 'NOT_MASKED';
  return 'NOT_MASKED_BY_DEFAULT';
}

/** The masking column of the engine's diff (RLE-051): inputs of this version first, then those only in base. */
function maskingDiff(next: Definition, base: Definition) {
  const names = [...next.inputs.map((input) => input.name), ...base.inputs.map((input) => input.name).filter((name) => !next.inputs.some((input) => input.name === name))];
  return names.map((name) => {
    const to = next.inputs.find((input) => input.name === name);
    const from = maskingStatus(base.inputs.find((input) => input.name === name));
    const status = maskingStatus(to);
    return { input: name, from, to: status, changed: from !== status, personalDataName: to !== undefined && to.mask === undefined ? (personalDataName(name) ?? null) : null };
  });
}

function view(v: StoredVersion, request: FakeRequest, marks: Marker, withSource = false): FakeResponse {
  const body = { ...lifecycle(v, request), sourceFormat: 'YAML', sourceHash: hash(v.source), ...(withSource ? { sourceDocument: v.source } : {}), definition: v.definition, warnings: warningsOf(v.definition, marks) };
  return json(body, { ETag: `"${String(v.lockVersion)}"` });
}

const MONEY_TEXT = /^-?\d+(\.\d+)?\s+[A-Z]{3}$/;

/** The engine's static checks that the screens show, every cell at once, in the engine's words. */
function validate(definition: Definition): Violation[] {
  const types = new Map(definition.inputs.map((input) => [input.name, input.type]));
  const outputs = new Map(definition.outputs.map((output) => [output.name, output.type]));
  const found: Violation[] = [];
  for (const rule of definition.rules) {
    for (const [input, condition] of Object.entries(rule.when)) {
      if (!types.has(input)) {
        found.push({ ruleId: rule.id, column: null, message: `unknown input '${input}'`, code: 'UNKNOWN_INPUT' });
        continue;
      }
      if (/^(==|!=|>=|<=|>|<)\s*$/.test(condition.trim())) {
        found.push({ ruleId: rule.id, column: input, message: `Unexpected end of expression at position ${String(condition.trim().length + 1)}`, code: 'SYNTAX_ERROR' });
        continue;
      }
      // Arithmetic on a money input with a plain number ("balance + 100 > 0") is a type error in the engine.
      if (types.get(input) === 'money' && /[A-Za-z_]\w*\s*[-+*/]\s*\d/.test(condition)) {
        found.push({ ruleId: rule.id, column: input, message: 'adds a number to a money', code: 'TYPE_MISMATCH' });
        continue;
      }
      const bare = /^(==|!=)\s*([A-Za-z_]\w*)$/.exec(condition);
      if (bare && bare[2] !== 'true' && bare[2] !== 'false' && bare[2] !== 'null') found.push({ ruleId: rule.id, column: input, message: `Unknown input '${bare[2] ?? ''}' at position ${String(condition.indexOf(bare[2] ?? '') + 1)}`, code: 'UNKNOWN_INPUT' });
      else if (types.get(input) === 'money' && /^(>=|<=|>|<|==|!=)\s*-?\d+(\.\d+)?$/.test(condition.trim())) found.push({ ruleId: rule.id, column: input, message: 'compares a money with a number', code: 'TYPE_MISMATCH' });
    }
    for (const [output, value] of Object.entries(rule.then)) {
      if (!outputs.has(output)) found.push({ ruleId: rule.id, column: null, message: `unknown output '${output}'`, code: 'UNKNOWN_OUTPUT' });
      else if (outputs.get(output) === 'money' && (typeof value === 'number' || (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value.trim())))) {
        found.push({ ruleId: rule.id, column: output, message: `output ${output} is money and needs a currency: write ${String(value)} followed by its ISO 4217 currency code`, code: 'INVALID_LITERAL' });
      } else if (outputs.get(output) === 'money' && typeof value === 'string' && !MONEY_TEXT.test(value.trim()) && /^\d/.test(value.trim())) {
        found.push({ ruleId: rule.id, column: output, message: `output ${output} is not a money amount`, code: 'INVALID_LITERAL' });
      }
    }
  }
  return found;
}

const COMMAND_LIMIT = 16_384;
const EVALUATE_LIMIT = 1_048_576;
const IMPORT_LIMIT = 1_048_576;

type PageQuery = { ok: true; limit: number; after: string | undefined } | { ok: false; response: FakeResponse };

/** limit 1 to 200 (default 50) and an opaque cursor this engine issued: "<scope>:<last key>" in base64url. */
function pageQuery(url: URL, scope: string): PageQuery {
  const limitText = url.searchParams.get('limit');
  const limit = limitText === null ? 50 : Number(limitText);
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) return { ok: false, response: problem(400, 'QUERY_INVALID', 'limit must be between 1 and 200') };
  const cursor = url.searchParams.get('cursor');
  if (cursor === null) return { ok: true, limit, after: undefined };
  let decoded: string;
  try {
    decoded = atob(cursor.replaceAll('-', '+').replaceAll('_', '/'));
  } catch {
    decoded = '';
  }
  if (!decoded.startsWith(`${scope}:`)) return { ok: false, response: problem(400, 'QUERY_INVALID', 'The cursor was not issued by this endpoint') };
  return { ok: true, limit, after: decoded.slice(scope.length + 1) };
}

const cursorOf = (scope: string, key: string) => btoa(`${scope}:${key}`).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');

function parseDocument(text: string): Definition | string {
  try {
    const parsed = JSON.parse(text) as Definition;
    if (typeof parsed !== 'object' || !Array.isArray(parsed.rules)) return 'Document must be a mapping';
    return parsed;
  } catch {
    return 'Document is not well-formed YAML: the fake engine reads JSON text only';
  }
}

function moneyMinor(text: string): { currency: string; amount: number } | undefined {
  const match = /^(-?\d+)\s+([A-Z]{3})$/.exec(text.trim());
  return match ? { currency: match[2] ?? '', amount: Number(match[1]) } : undefined;
}

function matches(condition: string, value: Json | undefined): boolean {
  const text = condition.trim();
  const literal = (raw: string): Json => {
    const t = raw.trim();
    if (/^'.*'$/.test(t)) return t.slice(1, -1);
    if (/^-?\d+$/.test(t)) return Number(t);
    return moneyMinor(t)?.amount ?? t;
  };
  const scalar = typeof value === 'object' && value !== null && !Array.isArray(value) ? (value['amount'] as number) : value;
  const eq = /^==\s*(.+)$/.exec(text);
  if (eq) return scalar === literal(eq[1] ?? '');
  const list = /^in\s*\[(.*)\]$/.exec(text);
  if (list) return (list[1] ?? '').split(',').map(literal).includes(scalar ?? null);
  const cmp = /^(>=|<=|>|<)\s*(.+)$/.exec(text);
  if (cmp && typeof scalar === 'number') {
    const bound = literal(cmp[2] ?? '') as number;
    return cmp[1] === '>=' ? scalar >= bound : cmp[1] === '<=' ? scalar <= bound : cmp[1] === '>' ? scalar > bound : scalar < bound;
  }
  return false;
}

/** Printable ASCII only (U+0020 to U+007E) and no escape such as \u200B, as the engine's nonAscii rule (RLE-048). */
const flagged = (value: Json | undefined): boolean => {
  const text = typeof value === 'string' ? value : JSON.stringify(value ?? null);
  return /[^\x20-\x7e]/.test(text) || /\\u[0-9a-fA-F]{4}/.test(text);
};

/** A canonical diff value: a money literal in a `then` cell is Money in minor units (VND has none), other cells their text. */
function canonicalValue(part: 'when' | 'then', value: Json | undefined): Json {
  if (value === undefined) return null;
  if (part === 'then' && typeof value === 'string') return moneyMinor(value) ?? value;
  return value;
}

const pointer = (name: string) => name.replaceAll('~', '~0').replaceAll('/', '~1');

/** Ids of `next` kept in place: the longest common subsequence of the two orders, earliest rows of `next` on a tie. */
function keptInOrder(base: readonly string[], next: readonly string[]): Set<string> {
  const rows = base.length + 1;
  const table = Array.from({ length: rows }, () => new Array<number>(next.length + 1).fill(0));
  for (let i = base.length - 1; i >= 0; i -= 1) {
    for (let j = next.length - 1; j >= 0; j -= 1) {
      const row = table[i] as number[];
      row[j] = base[i] === next[j] ? ((table[i + 1] as number[])[j + 1] ?? 0) + 1 : Math.max((table[i + 1] as number[])[j] ?? 0, row[j + 1] ?? 0);
    }
  }
  const kept = new Set<string>();
  let i = 0;
  let j = 0;
  while (i < base.length && j < next.length) {
    if (base[i] === next[j]) { kept.add(next[j] ?? ''); i += 1; j += 1; }
    else if (((table[i + 1] as number[])[j] ?? 0) >= ((table[i] as number[])[j + 1] ?? 0)) i += 1;
    else j += 1;
  }
  return kept;
}

type Clause = { name: string } & Record<string, unknown>;

function columnChanges(base: readonly Clause[], next: readonly Clause[]) {
  const baseNames = base.map((c) => c.name);
  const nextNames = next.map((c) => c.name);
  const changed = next.flatMap((clause) => {
    const old = base.find((c) => c.name === clause.name);
    if (!old) return [];
    const members = [...new Set([...Object.keys(old), ...Object.keys(clause)])].filter((m) => m !== 'name').sort();
    const fields = members.filter((m) => JSON.stringify(old[m] ?? null) !== JSON.stringify(clause[m] ?? null))
      .map((m) => ({ path: `/${m}`, from: (old[m] ?? null) as Json, to: (clause[m] ?? null) as Json, nonAscii: flagged(old[m] as Json) || flagged(clause[m] as Json) }));
    return fields.length > 0 ? [{ name: clause.name, fields }] : [];
  });
  const common = nextNames.filter((n) => baseNames.includes(n));
  return {
    added: nextNames.filter((n) => !baseNames.includes(n)),
    removed: baseNames.filter((n) => !nextNames.includes(n)),
    changed,
    reordered: common.join('\u0000') !== baseNames.filter((n) => nextNames.includes(n)).join('\u0000'),
  };
}

/** The engine's version diff (rule-engine DESIGN section 4.5) over the stored definitions. */
export function diffDefinitions(next: StoredVersion, base: StoredVersion) {
  const nextRules = next.definition.rules;
  const baseRules = base.definition.rules;
  const baseIndex = new Map(baseRules.map((rule, index) => [rule.id, index]));
  const nextIds = new Set(nextRules.map((rule) => rule.id));
  const kept = keptInOrder(baseRules.map((r) => r.id).filter((id) => nextIds.has(id)), nextRules.map((r) => r.id).filter((id) => baseIndex.has(id)));
  const rows: Record<string, Json>[] = [];
  let unchanged = 0;
  nextRules.forEach((rule, index) => {
    const at = baseIndex.get(rule.id);
    if (at === undefined) { rows.push({ ruleId: rule.id, change: 'ADDED', position: index + 1 }); return; }
    const old = baseRules[at] as Rule;
    const fields: Json[] = [];
    for (const part of ['when', 'then'] as const) {
      const names = [...new Set([...Object.keys(old[part]), ...Object.keys(rule[part])])].sort();
      for (const name of names) {
        const from = canonicalValue(part, old[part][name]);
        const to = canonicalValue(part, rule[part][name]);
        if (JSON.stringify(from) !== JSON.stringify(to)) fields.push({ path: `/${part}/${pointer(name)}`, from, to, nonAscii: flagged(old[part][name]) || flagged(rule[part][name]) });
      }
    }
    const moved = !kept.has(rule.id);
    const place = { position: index + 1, basePosition: at + 1 };
    if (fields.length > 0) rows.push({ ruleId: rule.id, change: 'CHANGED', ...place, moved, fields });
    else if (moved) rows.push({ ruleId: rule.id, change: 'MOVED', ...place });
    else unchanged += 1;
  });
  baseRules.forEach((rule, index) => { if (!nextIds.has(rule.id)) rows.push({ ruleId: rule.id, change: 'REMOVED', basePosition: index + 1 }); });
  const header: Json[] = [];
  const skip = new Set(['inputs', 'outputs', 'rules', 'ruleSet', 'effectiveFrom']);
  const a = base.definition as unknown as Record<string, Json>;
  const b = next.definition as unknown as Record<string, Json>;
  for (const member of [...new Set([...Object.keys(a), ...Object.keys(b)])].filter((m) => !skip.has(m)).sort()) {
    if (JSON.stringify(a[member] ?? null) !== JSON.stringify(b[member] ?? null)) header.push({ path: `/${member}`, from: a[member] ?? null, to: b[member] ?? null, nonAscii: flagged(a[member]) || flagged(b[member]) });
  }
  return {
    ruleSet: next.ruleSet, version: next.version, base: base.version,
    canonicalHash: hash(JSON.stringify(next.definition)), baseCanonicalHash: hash(JSON.stringify(base.definition)), sourceHashChanged: next.source !== base.source,
    effectiveFrom: { from: base.effectiveFrom, to: next.effectiveFrom },
    header,
    inputs: columnChanges(base.definition.inputs, next.definition.inputs),
    outputs: columnChanges(base.definition.outputs, next.definition.outputs),
    rows,
    unchangedCount: unchanged,
    masking: maskingDiff(next.definition, base.definition),
  };
}

/** RFC 8785 in short, for the values a definition holds: object keys sorted, no white space. */
export function canonicalJson(value: Json): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key] as Json)}`).join(',')}}`;
  return JSON.stringify(value);
}

const xml = (text: string) => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

/** A DMN 1.5 document with one decision table, as the engine's DMN export shapes it. */
function toDmn(d: Definition): string {
  const inputs = d.inputs.map((i) => `      <input label="${xml(i.name)}"><inputExpression typeRef="${xml(i.type)}"><text>${xml(i.name)}</text></inputExpression></input>`);
  const outputs = d.outputs.map((o) => `      <output name="${xml(o.name)}" typeRef="${xml(o.type)}"/>`);
  const rules = d.rules.map((r) => [
    `      <rule id="${xml(r.id)}">`,
    ...d.inputs.map((i) => `        <inputEntry><text>${xml(r.when[i.name] ?? '-')}</text></inputEntry>`),
    ...d.outputs.map((o) => `        <outputEntry><text>${xml(JSON.stringify(r.then[o.name] ?? null))}</text></outputEntry>`),
    '      </rule>',
  ].join('\n'));
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" name="${xml(d.ruleSet)}" namespace="urn:horus:rules">`,
    `  <decision id="${xml(d.ruleSet)}" name="${xml(d.ruleSet)}">`,
    `    <decisionTable hitPolicy="${xml(d.hitPolicy.toUpperCase())}">`,
    ...inputs, ...outputs, ...rules,
    '    </decisionTable>',
    '  </decision>',
    '</definitions>',
    '',
  ].join('\n');
}

function exportOf(v: StoredVersion, format: string | null): FakeResponse {
  const fileName = `${v.ruleSet}-v${String(v.version)}`;
  if (format === 'json') {
    return { status: 200, headers: { 'Content-Type': 'application/json', 'Content-Disposition': `attachment; filename="${fileName}.json"` }, body: canonicalJson(v.definition as unknown as Json) };
  }
  if (format === 'dmn') {
    return { status: 200, headers: { 'Content-Type': 'application/xml', 'Content-Disposition': `attachment; filename="${fileName}.dmn"` }, body: toDmn(v.definition) };
  }
  return problem(400, 'INPUT_INVALID', 'format must be one of json, dmn');
}

export function createFakeEngine(seed: StoredVersion[], acknowledgement: AcknowledgementSettings = {}, auditOptions: AuditOptions = {}) {
  /** First answers by Idempotency-Key and request, replayed when the same request comes again. */
  const replays = new Map<string, FakeResponse>();
  const store: StoredVersion[] = seed.map((v) => structuredClone(v));
  const marks = markerOf(acknowledgement);
  const listed = acknowledgement.acknowledgeWarnings ?? ACKNOWLEDGE_WARNINGS;
  const now = () => new Date('2026-11-19T03:00:00Z').toISOString();
  const find = (name: string, version: number) => store.find((v) => v.ruleSet === name && v.version === version);
  // A discarded version is left out of every list and of latestVersion (RLE-098); its number is never used again.
  const versionsOf = (name: string) => store.filter((v) => v.ruleSet === name && v.status !== 'DISCARDED');
  const nextNumber = (name: string) => store.filter((v) => v.ruleSet === name).length + 1;
  const audit = createAuditRoutes({
    versions: () => store.map((v) => ({
      ruleSet: v.ruleSet, version: v.version, status: v.status, createdBy: v.createdBy, createdAt: v.createdAt, requestedBy: v.requestedBy, requestedAt: v.requestedAt,
      approvedBy: v.approvedBy, approvedAt: v.approvedAt, effectiveFrom: v.effectiveFrom, canonicalHash: hash(JSON.stringify(v.definition)), hitPolicy: v.definition.hitPolicy,
      inputs: v.definition.inputs, rules: v.definition.rules, history: v.history ?? [],
    })),
    problem,
    hash,
    now,
    options: auditOptions,
  });

  function importVersion(name: string | undefined, request: FakeRequest): FakeResponse {
    const copyOf = new URL(request.path, 'http://host').searchParams.get('copyOf');
    if (name !== undefined && copyOf !== null) return copyVersion(name, copyOf, request);
    // The import limit as #145 answers it (an engine before #145 says 422 "Document larger than ...").
    if (request.body.length > IMPORT_LIMIT) return problem(413, 'PAYLOAD_TOO_LARGE', `The document is larger than ${String(IMPORT_LIMIT)} bytes`);
    // As the engine of this release: the media type names the source format, and only YAML is imported.
    if ((request.headers['content-type'] ?? '').includes('json')) return problem(400, 'INPUT_INVALID', 'Only YAML import is available in this release');
    const parsed = parseDocument(request.body);
    if (typeof parsed === 'string') return problem(422, 'VALIDATION_FAILED', parsed);
    if (name !== undefined && parsed.ruleSet !== name) return problem(400, 'INPUT_INVALID', `Document ruleSet ${parsed.ruleSet} differs from ${name}`);
    if (name === undefined && versionsOf(parsed.ruleSet).length > 0) return problem(409, 'RULE_SET_EXISTS', `Rule set ${parsed.ruleSet} exists`);
    if (name !== undefined && versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const violations = validate(parsed);
    const first = violations[0];
    if (first) {
      const where = first.ruleId === null ? '' : first.column === null ? `Rule ${first.ruleId}: ` : `Rule ${first.ruleId}, column ${first.column}: `;
      const more = violations.length > 1 ? ` (and ${String(violations.length - 1)} more)` : '';
      return problem(422, 'VALIDATION_FAILED', `${where}${first.message}${more}`, violations);
    }
    const stored: StoredVersion = {
      ruleSet: parsed.ruleSet, version: nextNumber(parsed.ruleSet), status: 'DRAFT', lockVersion: 0, createdBy: request.user, createdAt: now(),
      requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: request.body, definition: parsed,
      history: [{ action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: request.user, reason: null, lockVersion: 0, occurredAt: now() }],
    };
    store.push(stored);
    const answer = view(stored, request, marks);
    return { ...answer, status: 201, headers: { ...answer.headers, Location: `/api/bpm/rules/v1/rule-sets/${stored.ruleSet}/versions/${String(stored.version)}` } };
  }

  /** The next draft as a byte-for-byte copy of version `copyOf` (RLE-062); a body sent with it is refused. */
  function copyVersion(name: string, copyOf: string, request: FakeRequest): FakeResponse {
    if (!request.permissions.includes('rules:author')) return problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:author');
    const number = Number(copyOf);
    if (!Number.isInteger(number) || number < 1 || request.body !== '') return problem(400, 'INPUT_INVALID', 'copyOf must be a version number of 1 or more, sent with no body');
    if (versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const from = find(name, number);
    if (!from) return problem(404, 'VERSION_NOT_FOUND', `Version ${copyOf} of rule set ${name} not found`);
    const stored: StoredVersion = {
      ruleSet: name, version: nextNumber(name), status: 'DRAFT', lockVersion: 0, createdBy: request.user, createdAt: now(),
      requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: from.source, definition: structuredClone(from.definition),
      history: [{ action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: request.user, reason: null, lockVersion: 0, occurredAt: now() }],
    };
    store.push(stored);
    const answer = view(stored, request, marks);
    return { ...answer, status: 201, headers: { ...answer.headers, Location: `/api/bpm/rules/v1/rule-sets/${name}/versions/${String(stored.version)}` } };
  }

  /** The checks every draft write and validate share, in the engine's order after the permission. */
  function draftDocument(v: StoredVersion, request: FakeRequest): { ok: true; document: Definition } | { ok: false; response: FakeResponse } {
    if (request.body.length > IMPORT_LIMIT) return { ok: false, response: problem(413, 'PAYLOAD_TOO_LARGE', `The document is larger than ${String(IMPORT_LIMIT)} bytes`) };
    if (v.status !== 'DRAFT') return { ok: false, response: problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`) };
    const parsed = parseDocument(request.body);
    if (typeof parsed === 'string') return { ok: false, response: problem(422, 'VALIDATION_FAILED', parsed) };
    if (parsed.ruleSet !== v.ruleSet) return { ok: false, response: problem(400, 'INPUT_INVALID', `Document ruleSet ${parsed.ruleSet} differs from ${v.ruleSet}`) };
    return { ok: true, document: parsed };
  }

  /** validateDraft (RLE-090): the checks of a save, answered as cell problems with catalogue messages; nothing is written. */
  function validateDraft(v: StoredVersion, request: FakeRequest): FakeResponse {
    if (!request.permissions.includes('rules:author')) return problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:author');
    const checked = draftDocument(v, request);
    if (!checked.ok) {
      const refused = JSON.parse(checked.response.body) as { code?: string };
      if (refused.code !== 'VALIDATION_FAILED') return checked.response;
      return json({ ruleSet: v.ruleSet, version: v.version, valid: false, problems: [{ ruleId: null, column: null, code: 'DOCUMENT_INVALID', position: null, message: PROBLEM_CATALOGUE['DOCUMENT_INVALID'] }], problemsTruncated: false, readings: [], readingsTruncated: false, readingsReason: null });
    }
    const found = validate(checked.document);
    const problems = found.slice(0, PROBLEM_LIMIT).map((f) => ({
      ruleId: f.ruleId, column: f.column, code: f.code, position: null,
      message: PROBLEM_CATALOGUE[f.code] ?? PROBLEM_CATALOGUE[f.column !== null ? 'RULE_INVALID' : 'DOCUMENT_INVALID'],
    }));
    // The engine reads a valid draft with the reader of RLE-091 and answers each row's full sentence.
    // A table of more than READINGS_MAX rows is still valid: only its readings are left out, with a reason.
    const tooLarge = found.length === 0 && checked.document.rules.length > READINGS_MAX;
    const readings = found.length > 0 || tooLarge ? [] : checked.document.rules.map((rule) => {
      const read = readRule(rule, checked.document);
      return { ruleId: rule.id, text: { en: read.en.full, vi: read.vi.full } };
    });
    return json({ ruleSet: v.ruleSet, version: v.version, valid: found.length === 0, problems, problemsTruncated: found.length > PROBLEM_LIMIT, readings, readingsTruncated: tooLarge, readingsReason: tooLarge ? 'TABLE_TOO_LARGE' : null });
  }

  /** replaceDraft: only the draft's author, with the lock version they loaded; a refused document stores nothing. */
  function replaceDraft(v: StoredVersion, request: FakeRequest): FakeResponse {
    if (!request.permissions.includes('rules:author')) return problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:author');
    if (v.createdBy !== request.user) return problem(403, 'PERMISSION_DENIED', `Only the author of ${v.ruleSet} v${String(v.version)} may replace it; import your own document as a new version`);
    const ifMatch = request.headers['if-match'];
    if (ifMatch === undefined) return problem(428, 'PRECONDITION_REQUIRED', 'If-Match is required');
    if (request.headers['idempotency-key'] === undefined) return problem(400, 'INPUT_INVALID', 'Idempotency-Key is required');
    const checked = draftDocument(v, request);
    if (!checked.ok) return checked.response;
    if (ifMatch !== `"${String(v.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT', `Expected lock version ${ifMatch.replaceAll('"', '')} but ${v.ruleSet} v${String(v.version)} is at ${String(v.lockVersion)}`);
    const violations = validate(checked.document);
    const first = violations[0];
    if (first) {
      const where = first.ruleId === null ? '' : first.column === null ? `Rule ${first.ruleId}: ` : `Rule ${first.ruleId}, column ${first.column}: `;
      return problem(422, 'VALIDATION_FAILED', `${where}${first.message}`, violations);
    }
    Object.assign(v, { definition: checked.document, source: request.body, lockVersion: v.lockVersion + 1 });
    record(v, 'DRAFT_UPDATED', 'DRAFT', request.user, null);
    return view(v, request, marks);
  }

  function record(v: StoredVersion, action: StoredHistory['action'], from: string, actor: string, reason: string | null) {
    (v.history ??= []).push({ action, fromStatus: from, toStatus: v.status, actor, reason, lockVersion: v.lockVersion, occurredAt: now() });
  }

  function transition(v: StoredVersion, request: FakeRequest, action: 'request-publish' | 'approve' | 'reject'): FakeResponse {
    if (action !== 'request-publish' && request.body.length > COMMAND_LIMIT) return problem(413, 'PAYLOAD_TOO_LARGE', `The document is larger than ${String(COMMAND_LIMIT)} bytes`);
    const needs = action === 'request-publish' ? 'rules:author' : 'rules:approve';
    if (!request.permissions.includes(needs)) return problem(403, 'PERMISSION_DENIED', `The caller lacks permission ${needs}`);
    const ifMatch = request.headers['if-match'];
    if (ifMatch !== undefined && ifMatch !== `"${String(v.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT', `Expected lock version ${ifMatch.replaceAll('"', '')} but ${v.ruleSet} v${String(v.version)} is at ${String(v.lockVersion)}`);
    const from = v.status;
    if (action === 'request-publish') {
      if (v.status !== 'DRAFT') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
      Object.assign(v, { status: 'PENDING_APPROVAL', requestedBy: request.user, requestedAt: now(), lockVersion: v.lockVersion + 1 });
      record(v, 'REQUESTED', from, request.user, null);
      return view(v, request, marks);
    }
    if (v.status !== 'PENDING_APPROVAL') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
    const body = request.body === '' ? {} : (JSON.parse(request.body) as { comment?: string; reason?: string; expectedCanonicalHash?: string });
    // RLE-084: as the engine, a decision names the content it reviewed, and a stale hash is CONTENT_CHANGED, never a retry.
    if (body.expectedCanonicalHash === undefined) return problem(400, 'CONTENT_HASH_REQUIRED', 'expectedCanonicalHash is required: send the canonicalHash of the version the reviewer saw');
    const contentChanged = (): FakeResponse | undefined => {
      const stored = hash(JSON.stringify(v.definition));
      if (body.expectedCanonicalHash === stored) return undefined;
      const changed = problem(409, 'CONTENT_CHANGED', `The content of ${v.ruleSet} v${String(v.version)} changed since it was reviewed`);
      return { ...changed, body: JSON.stringify({ ...(JSON.parse(changed.body) as object), currentCanonicalHash: stored, currentLockVersion: v.lockVersion }) };
    };
    if (action === 'reject') {
      if (v.requestedBy === request.user || v.createdBy === request.user) return problem(403, 'SELF_APPROVAL_FORBIDDEN', `The requester or author of ${v.ruleSet} v${String(v.version)} cannot reject it`);
      const staleReject = contentChanged();
      if (staleReject) return staleReject;
      const reason = (body.reason ?? '').trim();
      if (reason === '') return problem(400, 'INPUT_INVALID', 'reason is required and must not be blank');
      if (reason.length > 2000) return problem(400, 'INPUT_INVALID', 'reason is longer than 2000 characters');
      Object.assign(v, { status: 'DRAFT', requestedBy: null, requestedAt: null, lockVersion: v.lockVersion + 1 });
      record(v, 'REJECTED', from, request.user, reason);
      return view(v, request, marks);
    }
    if (v.requestedBy === request.user || v.createdBy === request.user) return problem(403, 'SELF_APPROVAL_FORBIDDEN', `The requester or author of ${v.ruleSet} v${String(v.version)} cannot approve it`);
    const staleApprove = contentChanged();
    if (staleApprove) return staleApprove;
    // PD-104, RLE-053: the sent { code, input } pairs must equal, as a set, the pairs that need an acknowledgement.
    const needed = warningsOf(v.definition, marks).filter((warning) => warning.requiresAcknowledgement);
    const pairKey = (pair: { code?: unknown; input?: unknown }) => `${String(pair.code)}\u0000${String(pair.input)}`;
    const sent = new Set(((body as { acknowledgedWarnings?: { code?: unknown; input?: unknown }[] }).acknowledgedWarnings ?? []).map(pairKey));
    const wanted = new Set(needed.map(pairKey));
    if (sent.size !== wanted.size || [...wanted].some((key) => !sent.has(key))) {
      const refusal = problem(409, 'WARNINGS_NOT_ACKNOWLEDGED', `Approval needs an acknowledgement of ${needed.map((w) => `${w.code} on ${w.input}`).join(', ') || 'nothing'}`);
      const extra = { warnings: needed.map(({ code, input, matchedName }) => ({ code, input, matchedName })), setting: listed };
      return { ...refusal, body: JSON.stringify({ ...(JSON.parse(refusal.body) as object), ...extra }) };
    }
    for (const other of versionsOf(v.ruleSet)) if (other.status === 'PUBLISHED') other.status = 'RETIRED';
    Object.assign(v, { status: 'PUBLISHED', approvedBy: request.user, approvedAt: now(), effectiveFrom: now(), lockVersion: v.lockVersion + 1 });
    record(v, 'APPROVED', from, request.user, body.comment ?? null);
    return view(v, request, marks);
  }

  function summary(name: string) {
    const versions = versionsOf(name);
    const latest = versions.reduce((a, b) => (b.version > a.version ? b : a));
    const published = [...versions].reverse().find((v) => v.status === 'PUBLISHED');
    const times = versions.flatMap((v) => [v.createdAt, v.requestedAt, v.approvedAt, ...(v.history ?? []).map((h) => h.occurredAt)]).filter((x): x is string => typeof x === 'string');
    return { ruleSet: name, latestVersion: latest.version, latestStatus: latest.status, publishedVersion: published?.version ?? null, lastChangedAt: times.sort().at(-1) ?? latest.createdAt };
  }

  function listRuleSets(url: URL): FakeResponse {
    const query = pageQuery(url, 'rule-sets');
    if (!query.ok) return query.response;
    const names = [...new Set(store.map((v) => v.ruleSet))].sort().filter((name) => query.after === undefined || name > query.after);
    const pageNames = names.slice(0, query.limit);
    const hasNext = names.length > query.limit;
    const last = pageNames.at(-1);
    return json({ data: pageNames.map(summary), nextCursor: hasNext && last !== undefined ? cursorOf('rule-sets', last) : null, hasNext });
  }

  function listVersions(name: string, url: URL, request: FakeRequest): FakeResponse {
    if (versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const query = pageQuery(url, `versions:${name}`);
    if (!query.ok) return query.response;
    const after = query.after === undefined ? undefined : Number(query.after);
    const all = versionsOf(name).sort((a, b) => b.version - a.version).filter((v) => after === undefined || v.version < after);
    const items = all.slice(0, query.limit);
    const hasNext = all.length > query.limit;
    const last = items.at(-1);
    return json({ data: items.map((v) => lifecycle(v, request)), nextCursor: hasNext && last ? cursorOf(`versions:${name}`, String(last.version)) : null, hasNext });
  }

  /** listPendingApprovals (RLE-190): every pending version, oldest request first, with a cursor bound to the list. */
  function listPendingApprovals(url: URL, request: FakeRequest): FakeResponse {
    if (!request.permissions.includes('rules:read')) return problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:read');
    const query = pageQuery(url, 'approvals');
    if (!query.ok) return query.response;
    const order = (v: StoredVersion) => `${v.requestedAt ?? v.createdAt}\u0000${v.ruleSet}\u0000${String(v.version).padStart(6, '0')}`;
    const all = store.filter((v) => v.status === 'PENDING_APPROVAL').sort((a, b) => order(a).localeCompare(order(b))).filter((v) => query.after === undefined || order(v) > query.after);
    const items = all.slice(0, query.limit);
    const hasNext = all.length > query.limit;
    const last = items.at(-1);
    return json({ data: items.map((v) => pendingItem(v, request)), nextCursor: hasNext && last ? cursorOf('approvals', order(last)) : null, hasNext });
  }

  /** withdraw (RLE-046, PD-089): only the requester, back to DRAFT, with an optional reason. */
  function withdraw(v: StoredVersion, request: FakeRequest): FakeResponse {
    if (v.status !== 'PENDING_APPROVAL') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
    if (v.requestedBy !== request.user) return problem(403, 'PERMISSION_DENIED', `Only the requester of ${v.ruleSet} v${String(v.version)} may withdraw it`);
    const ifMatch = request.headers['if-match'];
    if (ifMatch !== undefined && ifMatch !== `"${String(v.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT', `${v.ruleSet} v${String(v.version)} is at ${String(v.lockVersion)}`);
    const body = request.body === '' ? {} : (JSON.parse(request.body) as { reason?: string });
    Object.assign(v, { status: 'DRAFT', requestedBy: null, requestedAt: null, lockVersion: v.lockVersion + 1 });
    record(v, 'WITHDRAWN', 'PENDING_APPROVAL', request.user, body.reason ?? null);
    return view(v, request, marks);
  }

  /** discardDraft (RLE-098): the draft's author only; DISCARDED, kept for the audit trail, left out of every list. */
  function discard(v: StoredVersion, request: FakeRequest): FakeResponse {
    if (!request.permissions.includes('rules:author')) return problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:author');
    if (request.headers['if-match'] === undefined) return problem(428, 'PRECONDITION_REQUIRED', 'If-Match is required');
    if (v.createdBy !== request.user) return problem(403, 'PERMISSION_DENIED', `Only the author of ${v.ruleSet} v${String(v.version)} may discard it`);
    if (v.status !== 'DRAFT') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
    if (request.headers['if-match'] !== `"${String(v.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT', `${v.ruleSet} v${String(v.version)} is at ${String(v.lockVersion)}`);
    const body = request.body === '' ? {} : (JSON.parse(request.body) as { reason?: string });
    Object.assign(v, { status: 'DISCARDED', lockVersion: v.lockVersion + 1 });
    record(v, 'DISCARDED', 'DRAFT', request.user, body.reason ?? null);
    return json({ ruleSet: v.ruleSet, version: v.version, status: v.status, lockVersion: v.lockVersion, hitPolicy: v.definition.hitPolicy, canonicalHash: hash(JSON.stringify(v.definition)) });
  }

  function evaluate(name: string, request: FakeRequest): FakeResponse {
    if (request.body.length > EVALUATE_LIMIT) return problem(413, 'PAYLOAD_TOO_LARGE', `The document is larger than ${String(EVALUATE_LIMIT)} bytes`);
    const effective = [...versionsOf(name)].reverse().find((v) => v.status === 'PUBLISHED');
    if (!effective) return problem(404, 'NO_EFFECTIVE_VERSION', `No version of ${name} is effective`);
    const inputs = (JSON.parse(request.body) as { inputs: Record<string, Json> }).inputs;
    const rule = effective.definition.rules.find((r) => Object.entries(r.when).every(([input, condition]) => matches(condition, inputs[input])));
    const outputs: Record<string, Json> = {};
    for (const [key, value] of Object.entries(rule?.then ?? {})) outputs[key] = typeof value === 'string' ? (moneyMinor(value) ?? value) : value;
    return {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decisionId: correlation(), ruleSet: name, version: effective.version, evaluatedAt: now(), outcome: rule ? 'MATCHED' : 'NO_MATCH', matchedRuleIds: rule ? [rule.id] : [], outputs }),
    };
  }

  /** Plain-language readings (RLE-091 to RLE-093): every row, the changed rows of a diff, a simulation. */
  function readings(name: string, number: number, tail: string, url: URL, request: FakeRequest): FakeResponse {
    const simulating = tail === ':simulate-readings';
    if (!request.permissions.includes('rules:read') || (simulating && !request.permissions.includes('rules:simulate'))) return problem(403, 'PERMISSION_DENIED', 'The caller lacks the permission for readings');
    if (versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const v = find(name, number);
    if (!v) return problem(404, 'VERSION_NOT_FOUND', `Version ${String(number)} of rule set ${name} not found`);
    const tooLarge = (what: string, count: number) => problem(422, 'READINGS_TOO_LARGE', `One request reads at most ${String(READINGS_MAX)} ${what}, this one covers ${String(count)}; read fewer at a time`);
    if (tail === '/readings') {
      if (v.definition.rules.length > READINGS_MAX) return tooLarge('rows', v.definition.rules.length);
      return json({ ruleSet: name, version: v.version, languages: LANGUAGES, rows: v.definition.rules.map((rule, index) => ({ ruleId: rule.id, position: index + 1, readings: readRule(rule, v.definition) })) });
    }
    if (tail === '/diff/readings') {
      const baseNumber = Number(url.searchParams.get('base') ?? Number.NaN);
      if (!Number.isInteger(baseNumber) || baseNumber < 1 || baseNumber === v.version) return problem(400, 'INPUT_INVALID', 'base is required and must name another version');
      const base = find(name, baseNumber);
      if (!base) return problem(404, 'VERSION_NOT_FOUND', `Version ${String(baseNumber)} of rule set ${name} not found`);
      const diff = diffDefinitions(v, base);
      if (diff.rows.length > READINGS_MAX) return tooLarge('rows', diff.rows.length);
      const read = (definition: Definition, id: Json | undefined): Readings | null => {
        const rule = definition.rules.find((r) => r.id === id);
        return rule ? readRule(rule, definition) : null;
      };
      const rows = diff.rows.map((row) => {
        const previous = row['change'] === 'ADDED' ? null : read(base.definition, row['ruleId']);
        const current = row['change'] === 'REMOVED' ? null : read(v.definition, row['ruleId']);
        return { ruleId: row['ruleId'], change: row['change'], position: row['position'], basePosition: row['basePosition'], readingChanged: !sameReadings(previous, current), previous, current };
      });
      return json({ ruleSet: name, version: v.version, base: base.version, languages: LANGUAGES, rows, unchangedCount: diff.unchangedCount });
    }
    const samples = (JSON.parse(request.body || '{}') as { samples?: { label?: string; inputs: Record<string, Json> }[] }).samples ?? [];
    if (samples.length > READINGS_MAX) return tooLarge('samples', samples.length);
    const effective = [...versionsOf(name)].reverse().find((candidate) => candidate.status === 'PUBLISHED');
    const outcome = (version: StoredVersion, inputs: Record<string, Json>) => {
      const rule = version.definition.rules.find((r) => Object.entries(r.when).every(([input, cell]) => matches(cell, inputs[input])));
      return rule ? { kind: 'MATCHED', ruleIds: [rule.id], readings: readRule(rule, version.definition, true), then: rule.then } : { kind: 'NO_MATCH', ruleIds: [], readings: readNoMatch(name, version.version), then: null };
    };
    const results = samples.map((sample, index) => {
      const simulated = outcome(v, sample.inputs);
      const baseline = effective ? outcome(effective, sample.inputs) : null;
      const changed = baseline === null || simulated.kind !== baseline.kind || JSON.stringify(simulated.then) !== JSON.stringify(baseline.then);
      const strip = (o: ReturnType<typeof outcome>) => ({ kind: o.kind, ruleIds: o.ruleIds, readings: o.readings });
      // The label is written back as sent; the engine never builds one from the inputs.
      return { index, reference: `S${String(index + 1)}`, label: sample.label ?? null, changed, simulated: strip(simulated), baseline: baseline ? strip(baseline) : null };
    });
    return json({ ruleSet: name, version: v.version, baselineVersion: effective?.version ?? null, languages: LANGUAGES, results });
  }

  function handle(request: FakeRequest): FakeResponse {
    const key = request.headers['idempotency-key'];
    if ((request.method !== 'POST' && request.method !== 'PUT') || key === undefined) return route(request);
    const replayKey = `${request.user}\u0000${key}\u0000${request.path}`;
    const replayed = replays.get(replayKey);
    if (replayed) return replayed;
    const answer = route(request);
    if (answer.status < 500) replays.set(replayKey, answer);
    return answer;
  }

  function route(request: FakeRequest): FakeResponse {
    const url = new URL(request.path, 'http://host');
    const path = decodeURIComponent(url.pathname).replace(/^.*\/api\/bpm\/rules\/v1/, '');
    let m: RegExpExecArray | null;
    if (path === '/approvals' && request.method === 'GET') return listPendingApprovals(url, request);
    if (path === '/rule-sets') return request.method === 'GET' ? listRuleSets(url) : importVersion(undefined, request);
    if (request.method === 'GET' && (m = /^\/rule-sets\/([^/]+)$/.exec(path))) {
      const name = m[1] ?? '';
      return versionsOf(name).length === 0 ? problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`) : json(summary(name));
    }
    if ((m = /^\/rule-sets\/([^/]+)\/versions$/.exec(path))) return request.method === 'GET' ? listVersions(m[1] ?? '', url, request) : importVersion(m[1], request);
    if ((m = /^\/rule-sets\/([^/]+)\/versions\/(\d+)(\/readings|\/diff\/readings|:simulate-readings)$/.exec(path))) return readings(m[1] ?? '', Number(m[2]), m[3] ?? '', url, request);
    if ((m = /^\/rule-sets\/([^/]+)\/versions\/(\d+)(\/history|\/diff|\/export|:request-publish|:approve|:reject|:validate|:withdraw|:discard)?$/.exec(path))) {
      const name = m[1] ?? '';
      if (m[3] === '/diff' && versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
      const v = find(name, Number(m[2]));
      if (!v) return problem(404, 'VERSION_NOT_FOUND', `Version ${m[2] ?? ''} of rule set ${name} not found`);
      const tail = m[3];
      if (request.method === 'GET' && tail === '/diff') {
        const baseText = url.searchParams.get('base');
        const baseNumber = baseText === null ? Number.NaN : Number(baseText);
        if (!Number.isInteger(baseNumber) || baseNumber < 1 || baseNumber === v.version) return problem(400, 'INPUT_INVALID', 'base is required and must name another version');
        const base = find(name, baseNumber);
        if (!base) return problem(404, 'VERSION_NOT_FOUND', `Version ${String(baseNumber)} of rule set ${name} not found`);
        return json(diffDefinitions(v, base));
      }
      if (request.method === 'GET' && tail === undefined) return view(v, request, marks, url.searchParams.get('include') === 'source');
      if (request.method === 'GET' && tail === '/history') return json({ data: v.history ?? [] });
      if (request.method === 'GET' && tail === '/export') return exportOf(v, url.searchParams.get('format'));
      if (request.method === 'PUT' && tail === undefined) return replaceDraft(v, request);
      if (request.method === 'POST' && tail === ':validate') return validateDraft(v, request);
      if (request.method === 'POST' && tail === ':withdraw') return withdraw(v, request);
      if (request.method === 'POST' && tail === ':discard') return discard(v, request);
      if (request.method === 'POST' && tail?.startsWith(':')) return transition(v, request, tail.slice(1) as 'request-publish' | 'approve' | 'reject');
    }
    if (request.method === 'POST' && (m = /^\/decisions\/([^/:]+):evaluate$/.exec(path))) return evaluate(m[1] ?? '', request);
    const audited = audit.route(path, url, request);
    if (audited) return audited;
    return problem(404, 'VERSION_NOT_FOUND', `No route ${request.method} ${path}`);
  }

  return { handle, store, audit };
}

const CARD_FEE_V1: Definition = {
  schemaVersion: 1, ruleSet: 'card-fee', description: 'Annual card fee by tier and balance', hitPolicy: 'first',
  inputs: [{ name: 'tier', type: 'string', allowed: ['CLASSIC', 'GOLD', 'PLATINUM'] }, { name: 'balance', type: 'money' }, { name: 'txCount', type: 'integer' }],
  outputs: [{ name: 'fee', type: 'money' }, { name: 'waived', type: 'boolean' }],
  rules: [
    { id: 'R1', when: { tier: "== 'PLATINUM'" }, then: { fee: '0 VND', waived: true } },
    {
      id: 'R2', when: { tier: "== 'GOLD'", balance: '>= 50000000 VND' }, then: { fee: '0 VND', waived: true },
      // The author's sentence per language (the rule set's explain), which the engine fills with grouped values.
      explain: {
        en: 'The card fee is waived because a Gold customer keeps a balance of {input.balance}, at least 50,000,000 VND.',
        vi: 'Phí thẻ được miễn vì khách hàng hạng Vàng duy trì số dư {input.balance}, từ 50.000.000 VND trở lên.',
      },
    },
    { id: 'R3', when: { tier: "== 'GOLD'" }, then: { fee: '30000 VND', waived: false } },
    { id: 'R4', when: { txCount: '>= 20' }, then: { fee: '15000 VND', waived: false } },
    { id: 'R5', when: {}, then: { fee: '50000 VND', waived: false } },
  ],
};

/** Against v1: R6 added, R3's fee raised from 30,000 to 35,000 VND, R4 (the transaction-count discount) removed. */
const CARD_FEE_V2: Definition = {
  ...CARD_FEE_V1,
  rules: [
    CARD_FEE_V1.rules[0] as Rule,
    CARD_FEE_V1.rules[1] as Rule,
    { id: 'R6', when: { tier: "== 'GOLD'", balance: '>= 20000000 VND' }, then: { fee: '10000 VND', waived: false } },
    { id: 'R3', when: { tier: "== 'GOLD'" }, then: { fee: '35000 VND', waived: false } },
    CARD_FEE_V1.rules[4] as Rule,
  ],
};

const KYC_RISK_V1: Definition = {
  schemaVersion: 1, ruleSet: 'kyc-risk', hitPolicy: 'first',
  inputs: [{ name: 'country', type: 'string' }, { name: 'monthlyIncome', type: 'money' }, { name: 'pep', type: 'boolean', description: 'PEP' }],
  outputs: [{ name: 'risk', type: 'string' }],
  rules: [
    { id: 'K1', when: { pep: '== true' }, then: { risk: "'HIGH'" } },
    { id: 'K2', when: { country: "in ['VN', 'SG']", monthlyIncome: '>= 200000000 VND' }, then: { risk: "'MEDIUM'" } },
    { id: 'K3', when: {}, then: { risk: "'LOW'" } },
  ],
};

const base = { lockVersion: 2, requestedAt: '2026-11-01T02:00:00Z', approvedAt: '2026-11-01T03:00:00Z', effectiveFrom: '2026-11-01T03:00:00Z' };

/** Sao Mai Bank's demo data: card-fee v1 published, v2 waiting for approval by Lan; kyc-risk v1 published. */
export function demoSeed(): StoredVersion[] {
  return [
    { ruleSet: 'card-fee', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-11-01T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(CARD_FEE_V1, null, 2), definition: CARD_FEE_V1, ...base,
      history: [
        { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-01T01:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 1, occurredAt: '2026-11-01T02:00:00Z' },
        { action: 'APPROVED', fromStatus: 'PENDING_APPROVAL', toStatus: 'PUBLISHED', actor: 'minh', reason: 'Matches the tariff', lockVersion: 2, occurredAt: '2026-11-01T03:00:00Z' },
      ] },
    { ruleSet: 'card-fee', version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'lan', createdAt: '2026-11-18T08:00:00Z', requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(CARD_FEE_V2, null, 2), definition: CARD_FEE_V2,
      history: [
        { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-18T08:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 1, occurredAt: '2026-11-18T08:30:00Z' },
      ] },
    { ruleSet: 'kyc-risk', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-10-20T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(KYC_RISK_V1, null, 2), definition: KYC_RISK_V1, ...base },
  ];
}

/**
 * card-fee for the history and evidence screens (CKP-072): v1 created by Lan, approved by Minh and retired when v2
 * took effect; v2 rejected by Minh with a reason, sent again and approved with a comment; v3 a draft. kyc-risk v1 published.
 */
export function auditSeed(): StoredVersion[] {
  const v3: Definition = { ...CARD_FEE_V2, rules: CARD_FEE_V2.rules.map((rule) => (rule.id === 'R3' ? { ...rule, then: { fee: '40000 VND', waived: false } } : rule)) };
  return [
    { ruleSet: 'card-fee', version: 1, status: 'RETIRED', lockVersion: 3, createdBy: 'lan', createdAt: '2026-11-01T01:00:00Z', requestedBy: 'lan', requestedAt: '2026-11-01T02:00:00Z', approvedBy: 'minh', approvedAt: '2026-11-01T03:00:00Z', effectiveFrom: '2026-11-01T03:00:00Z', source: JSON.stringify(CARD_FEE_V1, null, 2), definition: CARD_FEE_V1,
      history: [
        { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-01T01:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 1, occurredAt: '2026-11-01T02:00:00Z' },
        { action: 'APPROVED', fromStatus: 'PENDING_APPROVAL', toStatus: 'PUBLISHED', actor: 'minh', reason: 'Matches the tariff', lockVersion: 2, occurredAt: '2026-11-01T03:00:00Z' },
        { action: 'RETIRED', fromStatus: 'PUBLISHED', toStatus: 'RETIRED', actor: 'minh', reason: null, lockVersion: 3, occurredAt: '2026-11-19T03:00:00Z' },
      ] },
    { ruleSet: 'card-fee', version: 2, status: 'PUBLISHED', lockVersion: 4, createdBy: 'lan', createdAt: '2026-11-15T01:00:00Z', requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: 'minh', approvedAt: '2026-11-19T03:00:00Z', effectiveFrom: '2026-11-19T03:00:00Z', source: JSON.stringify(CARD_FEE_V2, null, 2), definition: CARD_FEE_V2,
      history: [
        { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-15T01:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 1, occurredAt: '2026-11-15T02:00:00Z' },
        { action: 'REJECTED', fromStatus: 'PENDING_APPROVAL', toStatus: 'DRAFT', actor: 'minh', reason: 'R2 waiver needs pricing committee minutes', lockVersion: 2, occurredAt: '2026-11-16T03:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 3, occurredAt: '2026-11-18T08:30:00Z' },
        { action: 'APPROVED', fromStatus: 'PENDING_APPROVAL', toStatus: 'PUBLISHED', actor: 'minh', reason: 'Committee minutes attached', lockVersion: 4, occurredAt: '2026-11-19T03:00:00Z' },
      ] },
    { ruleSet: 'card-fee', version: 3, status: 'DRAFT', lockVersion: 0, createdBy: 'lan', createdAt: '2026-11-20T02:00:00Z', requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(v3, null, 2), definition: v3,
      history: [{ action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-20T02:00:00Z' }] },
    { ruleSet: 'kyc-risk', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-10-20T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(KYC_RISK_V1, null, 2), definition: KYC_RISK_V1, ...base },
  ];
}

/**
 * card-fee for the grid editor (CKP-006): v1 published, v2 the draft Lan is editing (R3's fee raised to
 * 35,000 VND), and kyc-risk v2 a draft written by Hoa, which only Hoa may change.
 */
export function draftSeed(): StoredVersion[] {
  const v2: Definition = { ...CARD_FEE_V1, rules: CARD_FEE_V1.rules.map((rule) => (rule.id === 'R3' ? { ...rule, then: { fee: '35000 VND', waived: false } } : rule)) };
  const created = (actor: string, at: string): StoredHistory[] => [{ action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor, reason: null, lockVersion: 0, occurredAt: at }];
  const [published, , kyc] = demoSeed();
  return [
    published as StoredVersion,
    { ruleSet: 'card-fee', version: 2, status: 'DRAFT', lockVersion: 0, createdBy: 'lan', createdAt: '2026-11-20T02:00:00Z', requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(v2, null, 2), definition: v2, history: created('lan', '2026-11-20T02:00:00Z') },
    kyc as StoredVersion,
    { ruleSet: 'kyc-risk', version: 2, status: 'DRAFT', lockVersion: 0, createdBy: 'hoa', createdAt: '2026-11-20T03:00:00Z', requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(KYC_RISK_V1, null, 2), definition: KYC_RISK_V1, history: created('hoa', '2026-11-20T03:00:00Z') },
  ];
}

/** KYC routing with a masked national id, for the masked readings (RLE-091). */
const KYC_ROUTING_V1: Definition = {
  schemaVersion: 1, ruleSet: 'kyc-routing', hitPolicy: 'first',
  inputs: [{ name: 'nationalId', type: 'string', mask: true, description: 'National ID' }, { name: 'segment', type: 'string', allowed: ['MASS', 'VIP'] }],
  outputs: [{ name: 'route', type: 'string' }],
  rules: [
    { id: 'K1', when: { nationalId: "== 'SEG-4471'" }, then: { route: "'MANUAL'" } },
    { id: 'K2', when: { segment: "== 'VIP'" }, then: { route: "'PRIORITY'" } },
    { id: 'K3', when: {}, then: { route: "'STANDARD'" } },
  ],
};

/** A fee table of 501 rows: more than one readings request covers, so the engine answers 422 READINGS_TOO_LARGE. */
function bigTable(): Definition {
  const rules: Rule[] = Array.from({ length: 501 }, (_, index) => ({ id: `B${String(index + 1)}`, when: { txCount: `>= ${String(500 - index)}` }, then: { fee: `${String(1000 + index * 100)} VND` } }));
  return { schemaVersion: 1, ruleSet: 'branch-fees', hitPolicy: 'first', inputs: [{ name: 'txCount', type: 'integer' }], outputs: [{ name: 'fee', type: 'money' }], rules };
}

/** The demo data plus the rule sets the readings states need: masked inputs, a table too large to read, no rows. */
export function readingsSeed(): StoredVersion[] {
  const published = (definition: Definition): StoredVersion => ({ ruleSet: definition.ruleSet, version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-10-20T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(definition, null, 2), definition, ...base });
  const empty: Definition = { schemaVersion: 1, ruleSet: 'new-fees', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }], rules: [] };
  return [...demoSeed(), published(KYC_ROUTING_V1), published(bigTable()), { ...published(empty), status: 'DRAFT', approvedBy: null, approvedAt: null, effectiveFrom: null, requestedBy: null, requestedAt: null, lockVersion: 0 }];
}

/**
 * The simulation screen (CKP-004): card-fee v1 in effect and v2 a draft by Lan (R6 added, R3 raised to 35,000 VND,
 * R4 removed); kyc-routing v1 in effect and v2 a draft that tests a masked national id; new-fees v1 a draft with no rows.
 */
export function simulationSeed(): StoredVersion[] {
  const draft = (definition: Definition, version: number): StoredVersion => ({
    ruleSet: definition.ruleSet, version, status: 'DRAFT', lockVersion: 0, createdBy: 'lan', createdAt: '2026-11-20T02:00:00Z', requestedBy: null, requestedAt: null,
    approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(definition, null, 2), definition,
    history: [{ action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-20T02:00:00Z' }],
  });
  const [cardFee, , kyc] = demoSeed();
  const routing = readingsSeed().find((v) => v.ruleSet === 'kyc-routing') as StoredVersion;
  const empty: Definition = { schemaVersion: 1, ruleSet: 'new-fees', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }], rules: [] };
  return [cardFee as StoredVersion, draft(CARD_FEE_V2, 2), kyc as StoredVersion, routing, draft(KYC_ROUTING_V1, 2), draft(empty, 1)];
}
