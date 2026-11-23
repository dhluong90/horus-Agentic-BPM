import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { HostCallError } from '../../../api/hostError';
import { allVersions } from '../../../api/rules/catalogue';
import type { ExportedFile, ExportFormat, RuleDocument } from '../../../api/rules/rulesClient';
import type { AcknowledgedWarning, DecisionResult, DiscardedVersion, DraftValidation, HistoryReadings, ReportRequest, ReplayRequest, RuleSetVersion, SimulationReport, SimulationSample, VersionSummary } from '../../../api/rules/schemas';
import { runReplay } from '../model/replay';
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
    // The checked text is the key: an answer is only ever shown for the table it was given.
    check: (name: string, version: number, text: string) => [...rules, name, 'check', version, text] as const,
    readings: (name: string, version: number, hash: string) => [...rules, name, 'readings', version, hash] as const,
    diffReadings: (name: string, version: number, hash: string, base: number) => [...rules, name, 'diff-readings', version, hash, base] as const,
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
export function useSingleFlight<TArgs, TResult>(run: (args: TArgs, key: string) => Promise<TResult>, onSuccess: (result: TResult) => Promise<void> | void, onError?: (error: unknown) => Promise<void> | void) {
  const running = useRef(false);
  const last = useRef<{ fingerprint: string; key: string } | undefined>(undefined);
  const mutation = useMutation({
    mutationFn: async (args: TArgs) => {
      const fingerprint = JSON.stringify(args);
      if (last.current?.fingerprint !== fingerprint) last.current = { fingerprint, key: newIdempotencyKey() };
      return run(args, last.current.key);
    },
    onSuccess,
    ...(onError ? { onError } : {}),
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

/** A spreadsheet file the user picked: its exact bytes, a fingerprint of them, and the name it had. */
export interface PickedSheet {
  bytes: ArrayBuffer;
  fileName: string;
  /** SHA-256 of the bytes: one Idempotency-Key per file and base, so a repeat of the same file creates one draft. */
  fingerprint: string;
}

/**
 * Imports a spreadsheet as the next draft on the columns of version `base` (RLE-192). One request per intent; the
 * same file and `attempt` reuse their Idempotency-Key, and a deliberate retry (a higher `attempt`) sends a new one.
 */
export function useImportSpreadsheet(name: string, onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (args: { base: number; sheet: PickedSheet; attempt: number }, key) => client.importSpreadsheet(name, args.base, args.sheet.bytes, key),
    async (version) => {
      await refresh();
      onDone(version);
    },
  );
}

/**
 * Creates the next draft as a copy of `copyOf` (RLE-062), so the grid always edits a stored draft and never the
 * version it starts from.
 */
export function useCopyVersion(name: string, onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    (copyOf: number, key) => client.copyVersion(name, copyOf, key),
    async (version) => {
      await refresh();
      onDone(version);
    },
  );
}

/**
 * Saves the edited table into the draft (replaceDraft), bound to the lock version the author loaded. A stale lock
 * version is the engine's 409 VERSION_CONFLICT; it is never retried with a newer one.
 */
export function useReplaceDraft(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    ({ ruleSet, version, lockVersion, document }: { ruleSet: string; version: number; lockVersion: number; document: RuleDocument }, key) =>
      client.replaceDraft(ruleSet, version, lockVersion, document, key),
    async (version) => {
      await refresh();
      onDone(version);
    },
    // A stale lock version: read the version again, so the author can load what the other person saved.
    async (error) => {
      const hostError = error instanceof HostCallError ? error.hostError : undefined;
      if (hostError?.kind === 'problem' && hostError.code === 'VERSION_CONFLICT') await refresh();
    },
  );
}

/**
 * The engine's check of the committed table (RLE-090). Stores nothing, so it is a read keyed by the text: a late
 * answer for an older table can never mark the cells of a newer one. While a new table is checked the previous
 * answer stays on screen, flagged as a placeholder.
 */
export function useDraftCheck(ruleSet: string, version: number, document: RuleDocument | undefined) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery<DraftValidation>({
    queryKey: keys.check(ruleSet, version, document?.text ?? ''),
    queryFn: ({ signal }) => client.validateDraft(ruleSet, version, document ?? { contentType: 'application/yaml', text: '' }, signal),
    enabled: document !== undefined,
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: 60_000,
    retry: false,
    refetchOnWindowFocus: false,
    placeholderData: keepPreviousData,
  });
}

/**
 * `report` is the simulation report to attach to the request (RLE-201); `effectiveFrom` is when the version should take
 * effect (RLE-210). Without either the request carries neither.
 */
export function useRequestPublish(onDone: (version: RuleSetVersion) => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    ({ version, report, effectiveFrom }: { version: VersionSummary; report?: string | undefined; effectiveFrom?: string | undefined }, key) =>
      client.requestPublish(version.ruleSet, version.version, version.lockVersion, key, { simulationReportId: report, effectiveFrom }),
    async (version) => {
      await refresh();
      onDone(version);
    },
  );
}

/**
 * Discards the author's own draft (RLE-098). Nothing is deleted: the version becomes DISCARDED and leaves every list,
 * so after the engine answers the lists are read again and the screen moves on.
 */
export function useDiscardDraft(onDone: (version: DiscardedVersion) => void) {
  const { client } = useRulesSession();
  const queryClient = useQueryClient();
  const keys = useKeys();
  return useSingleFlight(
    ({ version, reason }: { version: VersionSummary; reason: string | undefined }, key) => client.discardDraft(version.ruleSet, version.version, version.lockVersion, reason, key),
    async (discarded) => {
      // The version list first, so the screen opens the version now in front; the discarded one is never read again.
      await queryClient.invalidateQueries({ queryKey: keys.versions(discarded.ruleSet) });
      queryClient.removeQueries({ queryKey: keys.version(discarded.ruleSet, discarded.version) });
      onDone(discarded);
      await queryClient.invalidateQueries({ queryKey: keys.all, predicate: (query) => {
        // [host, tenant, 'rules', name, kind, version, ...]: nothing about the discarded version is read again.
        const [, , , name, , number] = query.queryKey;
        return !(name === discarded.ruleSet && number === discarded.version);
      } });
    },
  );
}

/** A 409 VERSION_CONFLICT: the version moved on. It is read again and the decision is never sent again on its own. */
function conflictHandler(refresh: () => Promise<void>, onConflict: (() => void) | undefined) {
  return async (error: unknown) => {
    const hostError = error instanceof HostCallError ? error.hostError : undefined;
    if (hostError?.kind !== 'problem' || hostError.code !== 'VERSION_CONFLICT') return;
    onConflict?.();
    await refresh();
  };
}

export function useApprove(onDone: (version: RuleSetVersion) => void, onConflict?: () => void) {
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
    conflictHandler(refresh, onConflict),
  );
}

export function useReject(onDone: (version: RuleSetVersion) => void, onConflict?: () => void) {
  const { client } = useRulesSession();
  const refresh = useRefreshVersions();
  return useSingleFlight(
    ({ version, reviewedHash, reason }: { version: VersionSummary; reviewedHash: string; reason: string }, key) => client.reject(version.ruleSet, version.version, version.lockVersion, reviewedHash, reason, key),
    async (version) => {
      await refresh();
      onDone(version);
    },
    conflictHandler(refresh, onConflict),
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

/** Readings are refused for good by these answers, so a retry cannot help: no permission, or too many rows to read. */
function retryReading(failures: number, error: unknown): boolean {
  const status = error instanceof HostCallError && error.hostError.kind === 'problem' ? error.hostError.status : undefined;
  return failures < 2 && status !== 403 && status !== 404 && status !== 422;
}

/** Every row of a version in plain language (RLE-091); keyed by the content hash, so new content is read again. */
export function useVersionReadings(version: Pick<RuleSetVersion, 'ruleSet' | 'version' | 'canonicalHash'>, enabled: boolean) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.readings(version.ruleSet, version.version, version.canonicalHash),
    queryFn: ({ signal }) => client.readVersion(version.ruleSet, version.version, signal),
    enabled,
    staleTime: 60_000,
    retry: retryReading,
  });
}

/** The changed rows of a diff, each read in both versions (RLE-092); off while there is no base. */
export function useDiffReadings(version: Pick<RuleSetVersion, 'ruleSet' | 'version' | 'canonicalHash'>, base: number | undefined) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.diffReadings(version.ruleSet, version.version, version.canonicalHash, base ?? 0),
    queryFn: ({ signal }) => client.readDiff(version.ruleSet, version.version, base ?? 0, signal),
    enabled: base !== undefined,
    staleTime: 60_000,
    retry: retryReading,
  });
}

/** Runs one sample beside the version in effect and reads both outcomes (RLE-093). The engine logs nothing. */
export function useSimulateReadings(version: Pick<RuleSetVersion, 'ruleSet' | 'version'>) {
  const { client } = useRulesSession();
  return useMutation({ mutationFn: (inputs: Readonly<Record<string, unknown>>) => client.simulateReadings(version.ruleSet, version.version, [{ inputs }]) });
}

/**
 * Runs several samples of a draft beside the version in effect, in one request, and reads every outcome (RLE-093,
 * CKP-004). A read: no Idempotency-Key, and the engine logs nothing.
 */
export function useSimulateSamples(version: Pick<RuleSetVersion, 'ruleSet' | 'version'>) {
  const { client } = useRulesSession();
  return useMutation({ mutationFn: (samples: readonly SimulationSample[]) => client.simulateReadings(version.ruleSet, version.version, samples) });
}

/** The templates a new rule set can start from; the host's own, the same for every author (RLE-197). */
export function useTemplates(enabled: boolean) {
  const { client, hostId, tenant } = useRulesSession();
  return useQuery({ queryKey: [hostId, tenant, 'rules', 'templates'], queryFn: ({ signal }) => client.listTemplates(signal), enabled, staleTime: 300_000 });
}

/** The stored reports of a version, newest first, without rows (RLE-200). */
export function useReports(name: string, version: number, enabled = true) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({ queryKey: [...keys.version(name, version), 'reports'], queryFn: ({ signal }) => client.listSimulationReports(name, version, signal), enabled, staleTime: 5_000 });
}

/** One report with its rows. A report never changes, so it is read once; keyed by its id. */
export function useReport(name: string, version: number, reportId: string | undefined) {
  const { client } = useRulesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: [...keys.version(name, version), 'report', reportId ?? ''],
    queryFn: ({ signal }) => client.getSimulationReport(name, version, reportId ?? '', signal),
    enabled: reportId !== undefined,
    staleTime: 60_000,
    retry: retryReading,
  });
}

/** Runs a simulation and stores the report (RLE-199); one request per intent, the repeat of it reuses its key. */
export function useCreateReport(name: string, version: number, onDone: (report: SimulationReport) => void) {
  const { client } = useRulesSession();
  const queryClient = useQueryClient();
  const keys = useKeys();
  return useSingleFlight(
    (request: ReportRequest, key) => client.createSimulationReport(name, version, request, key),
    async (report) => {
      await queryClient.invalidateQueries({ queryKey: [...keys.version(name, version), 'reports'] });
      onDone(report);
    },
  );
}

/**
 * Replays the logged decisions of a period through a draft and reads each in plain language (RLE-202). A read: no
 * Idempotency-Key, nothing is logged. `cancel` abandons the wait; the engine writes nothing, so nothing is left behind.
 */
export function useReplay(version: Pick<RuleSetVersion, 'ruleSet' | 'version'>) {
  const { client } = useRulesSession();
  const controller = useRef<AbortController | undefined>(undefined);
  // What came in before a cancel: the engine writes nothing, so the steps already read stay valid to show.
  const [progress, setProgress] = useState<{ partial: HistoryReadings; done: number; total: number } | undefined>(undefined);
  const mutation = useMutation({
    mutationFn: (request: ReplayRequest & { limit: number }) => {
      const abort = new AbortController();
      controller.current = abort;
      setProgress(undefined);
      return runReplay(
        request,
        (step) => client.simulateHistoryReadings(version.ruleSet, version.version, step, abort.signal),
        (partial, done, total) => { setProgress({ partial, done, total }); },
      );
    },
  });
  const cancel = () => { controller.current?.abort(); };
  const reset = () => { setProgress(undefined); mutation.reset(); };
  return { ...mutation, cancel, reset, progress };
}
