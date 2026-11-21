import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { allVersions } from '../../../api/rules/catalogue';
import type { ExportedFile, ExportFormat, RuleDocument } from '../../../api/rules/rulesClient';
import type { AcknowledgedWarning, DecisionResult, RuleSetVersion, VersionSummary } from '../../../api/rules/schemas';
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
  const rules = [hostId, tenant, 'rules'] as const;
  return {
    all: rules,
    list: [...rules, 'list'] as const,
    versions: (name: string) => [...rules, name, 'versions'] as const,
    version: (name: string, version: number) => [...rules, name, 'version', version] as const,
    history: (name: string, version: number) => [...rules, name, 'history', version] as const,
    source: (name: string, version: number) => [...rules, name, 'source', version] as const,
    // The content hash and lock version of the version read are part of the key: a diff is never reused for other content.
    diff: (name: string, version: number, hash: string, lockVersion: number, base: number) => [...rules, name, 'diff', version, hash, lockVersion, base] as const,
  };
}

/** Every version of a rule set, newest first, without definitions. */
export function useVersions(name: string) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.versions(name), queryFn: ({ signal }) => allVersions(client, name, signal), staleTime: 10_000 });
}

/** One version with its definition. */
export function useVersion(name: string, version: number | undefined) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.version(name, version ?? 0),
    queryFn: ({ signal }) => client.getVersion(name, version ?? 0, { signal }),
    enabled: version !== undefined,
    staleTime: 10_000,
  });
}

/**
 * The engine's row-level diff of a version against a base version (RLE-028); off while there is no base. Keyed by
 * the content hash and lock version of the version on screen, so a version that changes gets a new diff.
 */
export function useVersionDiff(version: Pick<RuleSetVersion, 'ruleSet' | 'version' | 'canonicalHash' | 'lockVersion'>, base: number | undefined) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.diff(version.ruleSet, version.version, version.canonicalHash, version.lockVersion, base ?? 0),
    queryFn: ({ signal }) => client.diffVersions(version.ruleSet, version.version, base ?? 0, signal),
    enabled: base !== undefined,
    staleTime: 10_000,
  });
}

export function useHistory(name: string, version: number) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.history(name, version), queryFn: ({ signal }) => client.getHistory(name, version, signal), staleTime: 10_000 });
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
 * A mutation that sends one request per user intent. A second click while the first runs is ignored, and the
 * same request repeated (a double click after a fast answer, a retry after a timeout) reuses its
 * Idempotency-Key, so the engine replays the first answer instead of creating a second version.
 */
export function useSingleFlight<TArgs, TResult>(run: (args: TArgs, key: string) => Promise<TResult>, onSuccess: (result: TResult) => Promise<void> | void) {
  const running = useRef(false);
  const last = useRef<{ fingerprint: string; key: string } | undefined>(undefined);
  const mutation = useMutation({
    mutationFn: async (args: TArgs) => {
      const fingerprint = JSON.stringify(args);
      if (last.current?.fingerprint !== fingerprint) last.current = { fingerprint, key: newIdempotencyKey() };
      return run(args, last.current.key);
    },
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

/** After a change the engine confirmed, every list, version and history of this tenant is read again. */
function useRefreshVersions() {
  const queryClient = useQueryClient();
  const keys = useKeys();
  return async () => {
    await queryClient.invalidateQueries({ queryKey: keys.all });
  };
}

export function useImportRuleSet(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (document: RuleDocument, key) => client.importRuleSet(document, key),
    async (version) => {
      await refresh();
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
      await refresh();
      onDone(version);
    },
  );
}

export function useRequestPublish(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (version: VersionSummary, key) => client.requestPublish(version.ruleSet, version.version, version.lockVersion, key),
    async (version) => {
      await refresh();
      onDone(version);
    },
  );
}

export function useApprove(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    // Never retried on a refusal: a 409 WARNINGS_NOT_ACKNOWLEDGED goes back to the approver (PD-104), and a 409 CONTENT_CHANGED to the reviewer (RLE-084).
    // `reviewedHash` is the hash of the content on screen when the reviewer opened it, never read again here.
    ({ version, reviewedHash, comment, acknowledgedWarnings = [] }: { version: VersionSummary; reviewedHash: string; comment: string; acknowledgedWarnings?: readonly AcknowledgedWarning[] }, key) =>
      client.approve(version.ruleSet, version.version, version.lockVersion, reviewedHash, comment, key, acknowledgedWarnings),
    async (version) => {
      await refresh();
      onDone(version);
    },
  );
}

export function useReject(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    ({ version, reviewedHash, reason }: { version: VersionSummary; reviewedHash: string; reason: string }, key) => client.reject(version.ruleSet, version.version, version.lockVersion, reviewedHash, reason, key),
    async (version) => {
      await refresh();
      onDone(version);
    },
  );
}

/**
 * Exports one version as a file (exportVersion). A read: no Idempotency-Key, nothing cached, and a second
 * click while the first download runs is ignored.
 */
export function useExportVersion(onDone: (exported: ExportedFile) => void) {
  const { client } = useRulesSession();
  const running = useRef(false);
  const mutation = useMutation({
    mutationFn: ({ name, version, format }: { name: string; version: number; format: ExportFormat }) => client.exportVersion(name, version, format),
    onSuccess: onDone,
    onSettled: () => {
      running.current = false;
    },
  });
  const start = (args: { name: string; version: number; format: ExportFormat }) => {
    if (running.current) return;
    running.current = true;
    mutation.mutate(args);
  };
  return { ...mutation, start };
}

export function useEvaluate(ruleSet: string, onDone: (result: DecisionResult) => void) {
  const { client } = useRulesSession();
  return useSingleFlight((inputs: Record<string, unknown>, key) => client.evaluate(ruleSet, inputs, key), onDone);
}
