import type { z } from 'zod';
import { callHost, downloadFromHost, type HostRequest } from '../hostCall';
import { HostCallError } from '../hostError';
import {
  decisionResult,
  page,
  ruleSetSummary,
  ruleSetVersion,
  versionHistory,
  versionSummary,
  type DecisionResult,
  type HistoryEntry,
  type Page,
  type AcknowledgedWarning,
  type RuleSetSummary,
  type RuleSetVersion,
  type VersionSummary,
  versionDiff,
  type VersionDiff,
} from './schemas';
import {
  decisionExplanation,
  decisionLogPage,
  loggedDecision,
  ruleSetHistory,
  type DecisionExplanation,
  type DecisionLogPage,
  type EvidenceRequest,
  type LoggedDecision,
  type RuleSetHistory,
} from './auditSchemas';

type Fetch = typeof fetch;

export const RULES_BASE = '/api/bpm/rules/v1';

/** A document to import: the text and its media type (YAML, JSON, or a DMN decision table as XML). */
export interface RuleDocument {
  contentType: 'application/yaml' | 'application/json' | 'application/xml';
  text: string;
}

/** The export formats the engine offers (exportVersion): the RFC 8785 canonical JSON document, or DMN 1.5. */
export type ExportFormat = 'json' | 'dmn';

/** An exported version: the bytes as the engine sent them and the file name to save them under. */
export interface ExportedFile {
  file: Blob;
  fileName: string;
}

/**
 * Typed client of the Rule Engine REST API (rule-engine/api/openapi.yaml). One method per operation;
 * the operation id is named on each. Every state-changing call carries the caller's Idempotency-Key, and
 * calls on an existing version carry its lockVersion as If-Match.
 */
export interface PageRequest {
  cursor?: string | undefined;
  limit?: number | undefined;
  signal?: AbortSignal | undefined;
}

/** One search of the decision log: one rule set, or one case reference, in a period of at most 31 days. */
export interface DecisionSearch extends PageRequest {
  ruleSet?: string | undefined;
  correlationId?: string | undefined;
  /** First instant, inclusive, ISO 8601 in UTC. */
  from: string;
  /** End, exclusive. */
  to: string;
}

/** An evidence file as the engine wrote it, with the SHA-256 of its content the engine stated (PD-116). */
export interface EvidenceFile extends ExportedFile {
  contentSha256: string;
  /** RFC 9530 digest of the whole body, as the engine sent it; undefined when absent. */
  contentDigest: string | undefined;
  /** Size of the saved file in bytes. */
  bytes: number;
}

export interface RulesClient {
  /** listRuleSets: one page, by name */
  listRuleSets(request?: PageRequest): Promise<Page<RuleSetSummary>>;
  /** listVersions: one page, newest first, without definitions */
  listVersions(name: string, request?: PageRequest): Promise<Page<VersionSummary>>;
  /** getVersionHistory: lifecycle entries, oldest first */
  getHistory(name: string, version: number, signal?: AbortSignal): Promise<HistoryEntry[]>;
  /** reject: back to DRAFT with a required reason; `reviewedHash` is the canonicalHash of the content the reviewer saw (RLE-084) */
  reject(name: string, version: number, lockVersion: number, reviewedHash: string, reason: string, idempotencyKey: string): Promise<RuleSetVersion>;
  /** withdraw: the requester takes their own request back to DRAFT, with an optional reason (RLE-046, PD-089) */
  withdraw(name: string, version: number, lockVersion: number, reason: string | undefined, idempotencyKey: string): Promise<RuleSetVersion>;
  /** getVersion */
  getVersion(name: string, version: number, options?: { source?: boolean; signal?: AbortSignal }): Promise<RuleSetVersion>;
  /** diffVersions: the engine's row-level diff of `version` against `base`; writes nothing (RLE-028) */
  diffVersions(name: string, version: number, base: number, signal?: AbortSignal): Promise<VersionDiff>;
  /** exportVersion: a file that imports back with the same canonical hash (RLE-032); writes nothing */
  exportVersion(name: string, version: number, format: ExportFormat, signal?: AbortSignal): Promise<ExportedFile>;
  /** importRuleSet: version 1 of a new rule set */
  importRuleSet(document: RuleDocument, idempotencyKey: string): Promise<RuleSetVersion>;
  /** importVersion: the next draft version of an existing rule set */
  importVersion(name: string, document: RuleDocument, idempotencyKey: string): Promise<RuleSetVersion>;
  /** requestPublish */
  requestPublish(name: string, version: number, lockVersion: number, idempotencyKey: string): Promise<RuleSetVersion>;
  /** approve; `reviewedHash` is the canonicalHash of the content the reviewer saw, never re-read at approval time (RLE-084); `acknowledgedWarnings` are the exact { code, input } pairs the approver accepted (PD-104, RLE-053) */
  approve(name: string, version: number, lockVersion: number, reviewedHash: string, comment: string | undefined, idempotencyKey: string, acknowledgedWarnings?: readonly AcknowledgedWarning[]): Promise<RuleSetVersion>;
  /** searchDecisions: one page, newest first (RLE-029); writes nothing */
  searchDecisions(search: DecisionSearch): Promise<DecisionLogPage>;
  /** getDecision: one logged decision, masked inputs as their keyed hash */
  getDecision(id: string, signal?: AbortSignal): Promise<LoggedDecision>;
  /** explainDecision: the engine's plain-language explanation in `lang` (RLE-030, RLE-031); writes nothing */
  explainDecision(id: string, lang: string, signal?: AbortSignal): Promise<DecisionExplanation>;
  /** getRuleSetHistory: every version newest first, with its events, and the rule set's evidence exports (PD-116) */
  getRuleSetHistory(name: string, request?: PageRequest): Promise<RuleSetHistory>;
  /** exportEvidence: the engine writes, hashes and records the file; the bytes are kept as sent (PD-116) */
  exportEvidence(name: string, request: EvidenceRequest, idempotencyKey: string): Promise<EvidenceFile>;
  /** evaluate: runs the version in effect at the host clock and logs one decision */
  evaluate(ruleSet: string, inputs: Readonly<Record<string, unknown>>, idempotencyKey: string): Promise<DecisionResult>;
}

const segment = encodeURIComponent;

export function createRulesClient(baseUrl: string, fetchImpl: Fetch = fetch): RulesClient {
  const call = <T,>(request: HostRequest, schema: z.ZodType<T>) => callHost(fetchImpl, baseUrl, request, schema);
  const versionPath = (name: string, version: number) => `${RULES_BASE}/rule-sets/${segment(name)}/versions/${version}`;
  const ifMatch = (lockVersion: number) => `"${lockVersion}"`;

  const pageQuery = ({ cursor, limit }: PageRequest) => {
    const query = new URLSearchParams();
    if (limit !== undefined) query.set('limit', String(limit));
    if (cursor !== undefined) query.set('cursor', cursor);
    const text = query.toString();
    return text === '' ? '' : `?${text}`;
  };

  return {
    async listRuleSets(request = {}) {
      const answer = await call({ method: 'GET', path: `${RULES_BASE}/rule-sets${pageQuery(request)}`, ...(request.signal ? { signal: request.signal } : {}) }, page(ruleSetSummary));
      return answer.data;
    },
    async listVersions(name, request = {}) {
      const answer = await call(
        { method: 'GET', path: `${RULES_BASE}/rule-sets/${segment(name)}/versions${pageQuery(request)}`, ...(request.signal ? { signal: request.signal } : {}) },
        page(versionSummary),
      );
      return answer.data;
    },
    async getHistory(name, version, signal) {
      const answer = await call({ method: 'GET', path: `${versionPath(name, version)}/history`, ...(signal ? { signal } : {}) }, versionHistory);
      return answer.data.data;
    },
    async reject(name, version, lockVersion, reviewedHash, reason, idempotencyKey) {
      const answer = await call(
        {
          method: 'POST',
          path: `${versionPath(name, version)}:reject`,
          headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) },
          body: { contentType: 'application/json', text: JSON.stringify({ reason, expectedCanonicalHash: reviewedHash }) },
        },
        ruleSetVersion,
      );
      return answer.data;
    },
    async withdraw(name, version, lockVersion, reason, idempotencyKey) {
      const answer = await call(
        {
          method: 'POST',
          path: `${versionPath(name, version)}:withdraw`,
          headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) },
          body: { contentType: 'application/json', text: JSON.stringify(reason === undefined ? {} : { reason }) },
        },
        ruleSetVersion,
      );
      return answer.data;
    },
    async getVersion(name, version, options = {}) {
      const query = options.source ? '?include=source' : '';
      const answer = await call({ method: 'GET', path: `${versionPath(name, version)}${query}`, ...(options.signal ? { signal: options.signal } : {}) }, ruleSetVersion);
      return answer.data;
    },
    async diffVersions(name, version, base, signal) {
      const answer = await call({ method: 'GET', path: `${versionPath(name, version)}/diff?base=${String(base)}`, ...(signal ? { signal } : {}) }, versionDiff);
      return answer.data;
    },
    async exportVersion(name, version, format, signal) {
      const answer = await downloadFromHost(
        fetchImpl,
        baseUrl,
        { method: 'GET', path: `${versionPath(name, version)}/export?format=${format}`, ...(signal ? { signal } : {}) },
        format === 'json' ? 'application/json' : 'application/xml',
      );
      return { file: answer.file, fileName: answer.fileName ?? `${name}-v${String(version)}.${format}` };
    },
    async importRuleSet(document, idempotencyKey) {
      const answer = await call(
        { method: 'POST', path: `${RULES_BASE}/rule-sets`, headers: { 'Idempotency-Key': idempotencyKey }, body: { contentType: document.contentType, text: document.text } },
        ruleSetVersion,
      );
      return answer.data;
    },
    async importVersion(name, document, idempotencyKey) {
      const answer = await call(
        {
          method: 'POST',
          path: `${RULES_BASE}/rule-sets/${segment(name)}/versions`,
          headers: { 'Idempotency-Key': idempotencyKey },
          body: { contentType: document.contentType, text: document.text },
        },
        ruleSetVersion,
      );
      return answer.data;
    },
    async requestPublish(name, version, lockVersion, idempotencyKey) {
      const answer = await call(
        { method: 'POST', path: `${versionPath(name, version)}:request-publish`, headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) } },
        ruleSetVersion,
      );
      return answer.data;
    },
    async approve(name, version, lockVersion, reviewedHash, comment, idempotencyKey, acknowledgedWarnings = []) {
      // The reviewed hash is always sent: the engine refuses an approval that names no content (RLE-084).
      const payload = {
        expectedCanonicalHash: reviewedHash,
        ...(comment !== undefined && comment !== '' ? { comment } : {}),
        ...(acknowledgedWarnings.length > 0 ? { acknowledgedWarnings } : {}),
      };
      const answer = await call(
        {
          method: 'POST',
          path: `${versionPath(name, version)}:approve`,
          headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) },
          body: { contentType: 'application/json', text: JSON.stringify(payload) },
        },
        ruleSetVersion,
      );
      return answer.data;
    },
    async searchDecisions(search) {
      const query = new URLSearchParams();
      if (search.ruleSet !== undefined) query.set('ruleSet', search.ruleSet);
      if (search.correlationId !== undefined) query.set('correlationId', search.correlationId);
      query.set('from', search.from);
      query.set('to', search.to);
      if (search.limit !== undefined) query.set('limit', String(search.limit));
      if (search.cursor !== undefined) query.set('cursor', search.cursor);
      const answer = await call({ method: 'GET', path: `${RULES_BASE}/decisions?${query.toString()}`, ...(search.signal ? { signal: search.signal } : {}) }, decisionLogPage);
      return answer.data;
    },
    async getDecision(id, signal) {
      const answer = await call({ method: 'GET', path: `${RULES_BASE}/decisions/${segment(id)}`, ...(signal ? { signal } : {}) }, loggedDecision);
      return answer.data;
    },
    async explainDecision(id, lang, signal) {
      const answer = await call(
        { method: 'GET', path: `${RULES_BASE}/decisions/${segment(id)}/explain?lang=${segment(lang)}`, headers: { 'Accept-Language': lang }, ...(signal ? { signal } : {}) },
        decisionExplanation,
      );
      return answer.data;
    },
    async getRuleSetHistory(name, request = {}) {
      const answer = await call(
        { method: 'GET', path: `${RULES_BASE}/rule-sets/${segment(name)}/history${pageQuery(request)}`, ...(request.signal ? { signal: request.signal } : {}) },
        ruleSetHistory,
      );
      return answer.data;
    },
    async exportEvidence(name, request, idempotencyKey) {
      // Members are sent only when they carry something; the engine needs at least one version or decision.
      const body = {
        format: request.format,
        ...(request.versions.length > 0 ? { versions: request.versions } : {}),
        ...(request.decisionIds.length > 0 ? { decisionIds: request.decisionIds } : {}),
      };
      const answer = await downloadFromHost(
        fetchImpl,
        baseUrl,
        {
          method: 'POST',
          path: `${RULES_BASE}/rule-sets/${segment(name)}/evidence`,
          headers: { 'Idempotency-Key': idempotencyKey },
          body: { contentType: 'application/json', text: JSON.stringify(body) },
        },
        request.format === 'csv' ? 'text/csv' : 'application/json',
      );
      const contentSha256 = answer.header('X-Evidence-Sha256') ?? '';
      // The SHA-256 is the engine's statement about the file; without it the answer is not evidence.
      if (!/^[0-9a-f]{64}$/.test(contentSha256)) throw new HostCallError({ kind: 'unexpected-response', status: 200, correlationId: answer.header('X-Correlation-Id') });
      return {
        file: answer.file,
        fileName: answer.fileName ?? `${name}-evidence.${request.format}`,
        contentSha256,
        contentDigest: answer.header('Content-Digest'),
        bytes: answer.file.size,
      };
    },
    async evaluate(ruleSet, inputs, idempotencyKey) {
      const answer = await call(
        {
          method: 'POST',
          path: `${RULES_BASE}/decisions/${segment(ruleSet)}:evaluate`,
          headers: { 'Idempotency-Key': idempotencyKey },
          body: { contentType: 'application/json', text: JSON.stringify({ inputs }) },
        },
        decisionResult,
      );
      return answer.data;
    },
  };
}
