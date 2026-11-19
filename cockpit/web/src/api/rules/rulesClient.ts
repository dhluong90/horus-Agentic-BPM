import type { z } from 'zod';
import { callHost, type HostRequest } from '../hostCall';
import { decisionResult, ruleSetVersion, type DecisionResult, type RuleSetVersion } from './schemas';

type Fetch = typeof fetch;

export const RULES_BASE = '/api/bpm/rules/v1';

/** A document to import: the text and its media type (application/yaml or application/json). */
export interface RuleDocument {
  contentType: 'application/yaml' | 'application/json';
  text: string;
}

/**
 * Typed client of the Rule Engine REST API (rule-engine/api/openapi.yaml 0.1.0). One method per operation;
 * the operation id is named on each. Every state-changing call carries the caller's Idempotency-Key, and
 * calls on an existing version carry its lockVersion as If-Match.
 */
export interface RulesClient {
  /** getVersion */
  getVersion(name: string, version: number, options?: { source?: boolean; signal?: AbortSignal }): Promise<RuleSetVersion>;
  /** importRuleSet: version 1 of a new rule set */
  importRuleSet(document: RuleDocument, idempotencyKey: string): Promise<RuleSetVersion>;
  /** importVersion: the next draft version of an existing rule set */
  importVersion(name: string, document: RuleDocument, idempotencyKey: string): Promise<RuleSetVersion>;
  /** requestPublish */
  requestPublish(name: string, version: number, lockVersion: number, idempotencyKey: string): Promise<RuleSetVersion>;
  /** approve */
  approve(name: string, version: number, lockVersion: number, comment: string | undefined, idempotencyKey: string): Promise<RuleSetVersion>;
  /** evaluate: runs the version in effect at the host clock and logs one decision */
  evaluate(ruleSet: string, inputs: Readonly<Record<string, unknown>>, idempotencyKey: string): Promise<DecisionResult>;
}

const segment = encodeURIComponent;

export function createRulesClient(baseUrl: string, fetchImpl: Fetch = fetch): RulesClient {
  const call = <T,>(request: HostRequest, schema: z.ZodType<T>) => callHost(fetchImpl, baseUrl, request, schema);
  const versionPath = (name: string, version: number) => `${RULES_BASE}/rule-sets/${segment(name)}/versions/${version}`;
  const ifMatch = (lockVersion: number) => `"${lockVersion}"`;

  return {
    async getVersion(name, version, options = {}) {
      const query = options.source ? '?include=source' : '';
      const answer = await call({ method: 'GET', path: `${versionPath(name, version)}${query}`, ...(options.signal ? { signal: options.signal } : {}) }, ruleSetVersion);
      return answer.data;
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
    async approve(name, version, lockVersion, comment, idempotencyKey) {
      const answer = await call(
        {
          method: 'POST',
          path: `${versionPath(name, version)}:approve`,
          headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': ifMatch(lockVersion) },
          ...(comment !== undefined && comment !== '' ? { body: { contentType: 'application/json', text: JSON.stringify({ comment }) } } : {}),
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
