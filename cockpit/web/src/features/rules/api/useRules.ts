import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { listVersions, rememberRuleSet, summarise } from '../../../api/rules/catalogue';
import type { RuleDocument } from '../../../api/rules/rulesClient';
import type { DecisionResult, RuleSetVersion } from '../../../api/rules/schemas';
import { useRulesSession } from './rulesContext';

/*
 * Server state of the rule screens. Every key starts with host and tenant, so another host's or tenant's
 * answer can never show (cockpit DESIGN section 12). Nothing changes on screen before the engine answers.
 */

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

function useKeys() {
  const { hostId, tenant } = useRulesSession();
  return {
    versions: (name: string) => [hostId, tenant, 'rules', name, 'versions'] as const,
    source: (name: string, version: number) => [hostId, tenant, 'rules', name, 'source', version] as const,
  };
}

export function useVersions(name: string) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.versions(name), queryFn: ({ signal }) => listVersions(client, name, signal), staleTime: 10_000 });
}

export function useSummaries(names: readonly string[]) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQueries({
    queries: names.map((name) => ({
      queryKey: keys.versions(name),
      queryFn: ({ signal }: { signal: AbortSignal }) => listVersions(client, name, signal),
      staleTime: 10_000,
      select: (versions: RuleSetVersion[]) => summarise(name, versions),
    })),
  });
}

export function useSource(name: string, version: number, enabled: boolean) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.source(name, version),
    queryFn: ({ signal }) => client.getVersion(name, version, { source: true, signal }),
    enabled,
    staleTime: Number.POSITIVE_INFINITY,
  });
}

/**
 * A mutation that sends one request per user intent: a second click while the first runs is ignored, and
 * the Idempotency-Key is made once per intent, so a retry by the network layer cannot create a second version.
 */
function useSingleFlight<TArgs, TResult>(run: (args: TArgs, key: string) => Promise<TResult>, onSuccess: (result: TResult) => Promise<void> | void) {
  const running = useRef(false);
  const mutation = useMutation({
    mutationFn: async (args: TArgs) => run(args, newIdempotencyKey()),
    onSuccess,
    onSettled: () => {
      running.current = false;
    },
  });
  const start = (args: TArgs) => {
    if (running.current) return;
    running.current = true;
    mutation.mutate(args);
  };
  return { ...mutation, start };
}

function useRefreshVersions() {
  const queryClient = useQueryClient();
  const keys = useKeys();
  const { hostId, tenant } = useRulesSession();
  return async (version: RuleSetVersion) => {
    rememberRuleSet(hostId, tenant, version.ruleSet);
    await queryClient.invalidateQueries({ queryKey: keys.versions(version.ruleSet) });
  };
}

export function useImportRuleSet(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (document: RuleDocument, key) => client.importRuleSet(document, key),
    async (version) => {
      await refresh(version);
      onDone(version);
    },
  );
}

/** Saves a draft. This engine has no PUT for drafts yet, so a save imports the edited table as the next draft version. */
export function useSaveDraft(name: string, onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (document: RuleDocument, key) => client.importVersion(name, document, key),
    async (version) => {
      await refresh(version);
      onDone(version);
    },
  );
}

export function useRequestPublish(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (version: RuleSetVersion, key) => client.requestPublish(version.ruleSet, version.version, version.lockVersion, key),
    async (version) => {
      await refresh(version);
      onDone(version);
    },
  );
}

export function useApprove(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    ({ version, comment }: { version: RuleSetVersion; comment: string }, key) => client.approve(version.ruleSet, version.version, version.lockVersion, comment, key),
    async (version) => {
      await refresh(version);
      onDone(version);
    },
  );
}

export function useEvaluate(ruleSet: string, onDone: (result: DecisionResult) => void) {
  const { client } = useRulesSession();
  return useSingleFlight((inputs: Record<string, unknown>, key) => client.evaluate(ruleSet, inputs, key), onDone);
}
