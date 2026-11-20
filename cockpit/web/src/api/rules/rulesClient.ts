import type { z } from 'zod';
import { callHost, downloadFromHost, type HostRequest } from '../hostCall';
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

export interface RulesClient {
  /** listRuleSets: one page, by name */
  listRuleSets(request?: PageRequest): Promise<Page<RuleSetSummary>>;
  /** listVersions: one page, newest first, without definitions */
  listVersions(name: string, request?: PageRequest): Promise<Page<VersionSummary>>;
  /** getVersionHistory: lifecycle entries, oldest first */
  getHistory(name: string, version: number, signal?: AbortSignal): Promise<HistoryEntry[]>;
  /** reject: back to DRAFT with a required reason */
  reject(name: string, version: number, lockVersion: number, reason: string, idempotencyKey: string): Promise<RuleSetVersion>;
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
  /** approve; `acknowledgedWarnings` are the exact { code, input } pairs the approver accepted (PD-104, RLE-053) */
  approve(name: string, version: number, lockVersion: number, comment: string | undefined, idempotencyKey: string, acknowledgedWarnings?: readonly AcknowledgedWarning[]): Promise<RuleSetVersion>;
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
    async reject(name, version, lockVersion, reason, idempotencyKey) {
      const answer = await call(
        {
          method: 'POST',
          path: `${versionPath(name, version)}:reject`,
          headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) },
          body: { contentType: 'application/json', text: JSON.stringify({ reason }) },
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
    async approve(name, version, lockVersion, comment, idempotencyKey, acknowledgedWarnings = []) {
      // Members are sent only when they carry something, so an approval without either has no body, as before.
      const payload = {
        ...(comment !== undefined && comment !== '' ? { comment } : {}),
        ...(acknowledgedWarnings.length > 0 ? { acknowledgedWarnings } : {}),
      };
      const answer = await call(
        {
          method: 'POST',
          path: `${versionPath(name, version)}:approve`,
          headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) },
          ...(Object.keys(payload).length > 0 ? { body: { contentType: 'application/json', text: JSON.stringify(payload) } } : {}),
        },
        ruleSetVersion,
      );
      return answer.data;
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
