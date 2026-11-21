import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import type { EvidenceRequest } from '../../../api/rules/auditSchemas';
import type { EvidenceFile } from '../../../api/rules/rulesClient';
import { useRulesSession } from '../../rules/api/rulesContext';
import { useSingleFlight } from '../../rules/api/useRules';
import { monthPeriod } from '../model/references';

/*
 * Server state of the audit screens: the decision log, explanations, a rule set's history and the evidence
 * export. Every key starts with host and tenant (cockpit DESIGN section 12). Nothing is shown before the engine
 * answers, and nothing is computed here: the explanation, the masking and the SHA-256 are the engine's.
 */

function useAuditKeys() {
  const { hostId, tenant } = useRulesSession();
  const rules = [hostId, tenant, 'rules'] as const;
  return {
    search: (search: DecisionSearchKey) => [...rules, 'decisions', 'search', search] as const,
    decision: (id: string) => [...rules, 'decisions', id] as const,
    explanation: (id: string, language: string) => [...rules, 'decisions', id, 'explain', language] as const,
    history: (name: string) => [...rules, name, 'rule-set-history'] as const,
  };
}

/** One search of the log: a rule set or a case reference, within one calendar month. */
export interface DecisionSearchKey {
  ruleSet?: string | undefined;
  caseRef?: string | undefined;
  month: string;
  pageSize: number;
}

/** The decisions of one search, newest first, a page at a time (RLE-029). Off until the search is complete. */
export function useDecisionSearch(search: DecisionSearchKey | undefined) {
  const { client, can } = useRulesSession();
  const keys = useAuditKeys();
  const enabled = search !== undefined && can('rules:log:read') && (search.ruleSet !== undefined || search.caseRef !== undefined);
  return useInfiniteQuery({
    queryKey: keys.search(search ?? { month: '', pageSize: 0 }),
    queryFn: ({ pageParam, signal }) => {
      const { from, to } = monthPeriod(search?.month ?? '');
      return client.searchDecisions({ ruleSet: search?.ruleSet, correlationId: search?.caseRef, from, to, limit: search?.pageSize, cursor: pageParam, signal });
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor !== null ? last.nextCursor : undefined),
    enabled,
    staleTime: 30_000,
  });
}

export function useDecision(id: string, enabled: boolean) {
  const { client } = useRulesSession();
  const keys = useAuditKeys();
  return useQuery({ queryKey: keys.decision(id), queryFn: ({ signal }) => client.getDecision(id, signal), enabled, staleTime: Number.POSITIVE_INFINITY });
}

/** The engine's explanation in the screen's language. The same decision always explains the same way, so it is kept. */
export function useExplanation(id: string, language: string, enabled: boolean) {
  const { client } = useRulesSession();
  const keys = useAuditKeys();
  return useQuery({ queryKey: keys.explanation(id, language), queryFn: ({ signal }) => client.explainDecision(id, language, signal), enabled, staleTime: Number.POSITIVE_INFINITY });
}

/** Every version of a rule set, newest first, with its events, and the rule set's evidence exports (PD-116). */
export function useRuleSetHistory(name: string) {
  const { client } = useRulesSession();
  const keys = useAuditKeys();
  return useInfiniteQuery({
    queryKey: keys.history(name),
    queryFn: ({ pageParam, signal }) => client.getRuleSetHistory(name, { cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor !== null ? last.nextCursor : undefined),
    staleTime: 10_000,
  });
}

/**
 * Exports evidence: one POST per intent with an Idempotency-Key, reused when the same request is repeated, so a
 * double click or a retry gets the same file and one history entry. The history is read again afterwards.
 */
export function useExportEvidence(name: string, onDone: (file: EvidenceFile, request: EvidenceRequest) => void) {
  const { client } = useRulesSession();
  const keys = useAuditKeys();
  const queryClient = useQueryClient();
  return useSingleFlight(
    async (request: EvidenceRequest, key) => ({ file: await client.exportEvidence(name, request, key), request }),
    async ({ file, request }) => {
      onDone(file, request);
      await queryClient.invalidateQueries({ queryKey: keys.history(name) });
    },
  );
}
