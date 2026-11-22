import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { MigrationDryRun, MigrationExecution, MigrationPlan, MigrationStatus } from '../../../api/processes/schemas';
import { setCrumbTrail } from '../../../app/crumbs';
import { usePersonName } from '../../../app/people';
import { formatRoute } from '../../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { Tabs } from '../../rules/components/Tabs';
import { useProcessesSession } from '../api/processesContext';
import { useDefinitionGraph, useDefinitionVersion, useInstance } from '../api/useProcesses';
import { ReviewValue } from '../review/ReviewValue';
import { MigrationDock, problemCode } from './MigrationDock';
import { counted } from './model/counted';
import { isFinished, nextStep, notMoved, reasonKey, stepRows, type StepRow } from './model/migration';
import {
  useApproveMigration, useCheckMigration, useMigrationExecution, useMigrationPlan, useNotMoved, useProposeMigration, useRejectMigration,
} from './api/useMigrations';

/* The two diagrams and dagre load on demand, in their own chunk, only when the diagrams tab opens (CKP-028). */
const MigrationDiagrams = lazy(() => import('./MigrationDiagrams'));

const LOOK: Record<MigrationStatus, { glyph: string; tone: string }> = {
  PLANNED: { glyph: '◇', tone: 'neutral' },
  PROPOSED: { glyph: '◷', tone: 'accent' },
  REJECTED: { glyph: '↩', tone: 'warn' },
  APPROVED: { glyph: '✓', tone: 'accent' },
  RUNNING: { glyph: '▶', tone: 'accent' },
  COMPLETED: { glyph: '✓', tone: 'ok' },
  COMPLETED_WITH_FAILURES: { glyph: '✓', tone: 'warn' },
};

/** The state of the plan in a word with its icon. */
function StatusPill({ status }: { status: MigrationStatus }) {
  const { t } = useI18n();
  const look = LOOK[status];
  return <span className={`badge badge--${look.tone}`}><span aria-hidden="true">{look.glyph}</span> {t(`migration.status.${status}`)}</span>;
}

type Tab = 'diagrams' | 'steps' | 'notMoved';

function useNow(everyMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => { setNow(Date.now()); }, everyMs);
    return () => { clearInterval(timer); };
  }, [everyMs]);
  return now;
}

/**
 * A migration plan: the cases of one version moving to another (CKP-027, CKP-056 to CKP-058). One view, one primary
 * action at a time, docked below: check the plan, ask for approval with a reason, approve as a second person, then
 * watch the run. Everything shown is what the engine reports; the page counts and decides nothing.
 */
export function MigrationPage({ migrationId, pageLimit }: { migrationId: string; pageLimit: number }) {
  const { t } = useI18n();
  const plan = useMigrationPlan(migrationId);
  useEffect(() => () => { setCrumbTrail(undefined); }, []);
  if (plan.isPending) return <section className="workspace" aria-busy="true"><LoadingRows rows={6} /></section>;
  if (plan.isError) {
    const host = toHostError(plan.error);
    const missing = host.kind === 'problem' && host.code === 'MIGRATION_NOT_FOUND';
    return (
      <section className="workspace" aria-labelledby="migration-title">
        <PageHead id="migration-title" title={t('migration.title')} subtitle={t('migration.subtitlePlain', { source: '–', target: '–' })} />
        <article className="card">
          {missing ? <EmptyState icon="processes" text={t('migration.problem.MIGRATION_NOT_FOUND')} action={{ label: t('menu.processes'), href: formatRoute({ name: 'processes' }) }} />
            : <AreaError error={plan.error} onRetry={() => { void plan.refetch(); }} />}
        </article>
      </section>
    );
  }
  return <PlanView plan={plan.data} pageLimit={pageLimit} />;
}

function PlanView({ plan, pageLimit }: { plan: MigrationPlan; pageLimit: number }) {
  const { t, locale } = useI18n();
  const { userId, can } = useProcessesSession();
  const person = usePersonName();
  const canManage = can('processes:migration:manage');
  const execution = useMigrationExecution(plan.id);
  const check = useCheckMigration(plan.id);
  const proposal = useProposeMigration(plan.id);
  const approval = useApproveMigration(plan.id);
  const rejection = useRejectMigration(plan.id);
  const target = useDefinitionVersion(plan.definitionKey, plan.targetVersion);
  const sourceGraph = useDefinitionGraph(plan.definitionKey, plan.sourceVersion);
  const targetGraph = useDefinitionGraph(plan.definitionKey, plan.targetVersion);
  const now = useNow(15_000);
  const name = target.data?.name ?? undefined;
  useEffect(() => {
    setCrumbTrail([
      ...(name !== undefined ? [{ label: name, href: formatRoute({ name: 'process', key: plan.definitionKey, version: plan.targetVersion }) }] : []),
      { label: t('migration.crumb') },
    ]);
  }, [name, plan.definitionKey, plan.targetVersion, t]);

  const stored = execution.data;
  const status = stored?.status ?? plan.status;
  // The proposal the user was first shown: Approve names this one, so a proposal that moved on is read again first.
  const [seenLock, setSeenLock] = useState<number | undefined>(undefined);
  if (seenLock === undefined && stored?.status === 'PROPOSED') setSeenLock(stored.lockVersion);
  // The engine said the check no longer holds: nothing on screen is a check of this plan any more.
  const stale = problemCode(proposal.error) === 'DRY_RUN_REQUIRED';
  const dryRun: MigrationDryRun | undefined = stale ? undefined : check.data;
  // A proposal is read, never acted on blind: anyone who may run migrations sees the engine's check of it now.
  const asked = useRef(false);
  const { mutate: runCheck } = check;
  const checked = check.data !== undefined;
  useEffect(() => {
    if (status === 'PROPOSED' && canManage && !asked.current) {
      asked.current = true;
      // The proposer just checked it; a person who opens the proposal has not.
      if (!checked) runCheck();
    }
  }, [status, canManage, checked, runCheck]);

  const step = stored === undefined ? undefined : nextStep({ plan: { status: plan.status, planHash: plan.planHash }, execution: stored, dryRun, checking: check.isPending, now, userId, canManage, seenLock });
  const rows = sourceGraph.data !== undefined && targetGraph.data !== undefined ? stepRows(plan, sourceGraph.data, targetGraph.data, dryRun) : [];
  const finished = isFinished(status);
  const left = stored !== undefined ? notMoved(stored.progress) : 0;
  const [picked, setTab] = useState<Tab | undefined>(undefined);
  const tab: Tab = picked ?? (finished && left > 0 ? 'notMoved' : 'diagrams');
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const items = [
    { id: 'diagrams' as const, label: t('migration.tab.diagrams') },
    { id: 'steps' as const, label: t('migration.tab.steps') },
    ...(finished && left > 0 ? [{ id: 'notMoved' as const, label: t('migration.tab.notMoved', { count: String(left) }) }] : []),
  ];
  const versions = { source: String(plan.sourceVersion), target: String(plan.targetVersion) };

  return (
    <section className="workspace workspace--docked" aria-labelledby="migration-title">
      <PageHead id="migration-title" title={t('migration.title')}
        subtitle={name !== undefined ? t('migration.subtitle', { name, ...versions }) : t('migration.subtitlePlain', versions)}
        action={<StatusPill status={status} />} />
      {execution.isError ? <article className="card"><AreaError error={execution.error} onRetry={() => { void execution.refetch(); }} /></article> : (
        <Outcome plan={plan} execution={stored} dryRun={dryRun} checking={check.isPending} person={person} locale={locale} />
      )}
      <article className="card card--diagram">
        <Tabs label={t('migration.tabs')} selected={tab} onSelect={setTab} items={items}>
          {tab === 'notMoved' ? <NotMoved plan={plan} pageLimit={pageLimit} /> : sourceGraph.isPending || targetGraph.isPending ? <LoadingRows rows={6} /> : sourceGraph.isError || targetGraph.isError ? (
            <AreaError error={sourceGraph.error ?? targetGraph.error} onRetry={() => { void sourceGraph.refetch(); void targetGraph.refetch(); }} />
          ) : tab === 'steps' ? <StepsList rows={rows} plan={plan} selected={selected} onSelect={setSelected} /> : (
            <>
              <ul className="pdiff__legend mdiagrams__legend" aria-label={t('migration.legend')}>
                <li className="pdiff__key pdiff__key--changed"><span className="pdiff__swatch" aria-hidden="true">•</span>{t('migration.mark.changed')}</li>
                {rows.some((row) => row.state === 'unmapped') ? <li className="pdiff__key pdiff__key--removed"><span className="pdiff__swatch" aria-hidden="true">−</span>{t('migration.mark.removed')}</li> : null}
              </ul>
              <Suspense fallback={<LoadingRows rows={6} />}>
                <MigrationDiagrams source={sourceGraph.data} target={targetGraph.data} sourceVersion={plan.sourceVersion} targetVersion={plan.targetVersion} rows={rows} selected={selected} onSelect={setSelected} />
              </Suspense>
            </>
          )}
        </Tabs>
      </article>
      {step !== undefined ? (
        <MigrationDock step={step} plan={plan} dryRun={dryRun}
          check={{ start: () => { check.mutate(); }, isPending: check.isPending, error: check.error }}
          propose={{ start: ({ reason }) => { proposal.mutate({ reason }); }, isPending: proposal.isPending, error: proposal.error }}
          approve={{ start: () => { if (seenLock !== undefined) approval.mutate({ lockVersion: seenLock }); }, isPending: approval.isPending, error: approval.error }}
          reject={{ start: ({ reason }) => { if (seenLock !== undefined) rejection.mutate({ lockVersion: seenLock, reason }); }, isPending: rejection.isPending, error: rejection.error }}
          onReviewAgain={() => { approval.reset(); rejection.reset(); setSeenLock(stored?.lockVersion); void execution.refetch(); }} />
      ) : null}
    </section>
  );
}

const clock = (iso: string, locale: string) =>
  new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(iso));

/**
 * The focal card: what the engine says will happen or has happened, in plain words. Before a check it says so; after
 * one, how many cases can move and how many cannot, and what each kind of instruction does; while the run goes, the
 * progress; at the end, how many moved and how many did not.
 */
function Outcome({ plan, execution, dryRun, checking, person, locale }: {
  plan: MigrationPlan; execution: MigrationExecution | undefined; dryRun: MigrationDryRun | undefined; checking: boolean;
  person: (login: string) => string; locale: string;
}) {
  const { t, locale: language } = useI18n();
  const number = (value: number) => value.toLocaleString(language === 'vi' ? 'vi-VN' : 'en-US');
  if (execution === undefined) return <article className="card card--outcome"><LoadingRows rows={2} /></article>;
  const { status, progress } = execution;
  const who = (login: string | null | undefined) => (login !== null && login !== undefined ? person(login) : '');
  if (status === 'APPROVED' || status === 'RUNNING') {
    const done = progress.migrated + progress.failed + progress.skipped;
    return (
      <article className="card card--outcome" aria-labelledby="outcome-title">
        <h2 id="outcome-title" className="outcome__lead"><span className="tnum">{t('migration.progress', { done: number(done), total: number(progress.total) })}</span></h2>
        <progress className="outcome__bar" max={progress.total} value={done} aria-label={t('migration.progress.label')} />
        <p className="outcome__line">{status === 'APPROVED' && done === 0 ? t('migration.progress.waiting', { who: who(execution.approvedBy) }) : t('migration.progress.running', { who: who(execution.approvedBy) })}</p>
      </article>
    );
  }
  if (isFinished(status)) {
    const left = notMoved(progress);
    return (
      <article className="card card--outcome" aria-labelledby="outcome-title">
        <div role="status">
          <h2 id="outcome-title" className="outcome__lead tnum">{left === 0 ? t('migration.finished.all', { moved: number(progress.migrated) }) : t('migration.finished.some', { moved: number(progress.migrated), left: number(left) })}</h2>
        </div>
      </article>
    );
  }
  return (
    <article className="card card--outcome" aria-labelledby="outcome-title">
      {dryRun === undefined ? (
        <>
          <h2 id="outcome-title" className="outcome__lead">{checking ? t('migration.result.checking') : t('migration.result.unchecked')}</h2>
          {!checking ? <p className="outcome__line">{t('migration.result.uncheckedBody')}</p> : null}
        </>
      ) : (
        <>
          <h2 id="outcome-title" className="outcome__lead tnum">
            {dryRun.instances.movable === 0 ? t('migration.result.none') : t(counted('migration.result.can', dryRun.instances.movable), { count: number(dryRun.instances.movable) })}
            {dryRun.instances.blocked > 0 ? <> {t('migration.result.cannot', { count: number(dryRun.instances.blocked) })}</> : null}
          </h2>
          <ul className="outcome__list">
            {(['mapped', 'cancelled', 'inFlight', 'unmapped'] as const).map((kind) => dryRun.impact[kind] > 0 ? (
              <li key={kind} className={kind === 'unmapped' || kind === 'inFlight' ? 'outcome__item outcome__item--warn' : 'outcome__item'}>
                <span aria-hidden="true">{kind === 'unmapped' || kind === 'inFlight' ? '!' : '→'}</span>{' '}
                {t(counted(`migration.impact.${kind}`, dryRun.impact[kind]), { count: number(dryRun.impact[kind]), source: String(plan.sourceVersion) })}
              </li>
            ) : null)}
            {dryRun.conflicts.some((item) => item.code === 'TARGET_NOT_ACTIVE') ? <li className="outcome__item outcome__item--warn"><span aria-hidden="true">!</span> {t('migration.impact.notActive', { target: String(plan.targetVersion) })}</li> : null}
          </ul>
          <p className="outcome__meta">{t('migration.result.checked', { time: clock(dryRun.checkedAt, locale), until: clock(dryRun.validUntil, locale) })}</p>
        </>
      )}
      {status === 'PROPOSED' && execution.proposedBy !== null && execution.proposedBy !== undefined ? (
        <p className="outcome__line">{t('migration.proposal', { who: who(execution.proposedBy) })} <ReviewValue value={execution.reason} /></p>
      ) : null}
      {status === 'REJECTED' && execution.rejectedBy !== null && execution.rejectedBy !== undefined ? (
        <p className="outcome__line">{t('migration.rejection', { who: who(execution.rejectedBy) })} <ReviewValue value={execution.rejectionReason} /></p>
      ) : null}
    </article>
  );
}

/** The changed steps in words, the keyboard and reader path through the two diagrams (CKP-035). */
function StepsList({ rows, plan, selected, onSelect }: { rows: readonly StepRow[]; plan: MigrationPlan; selected: string | undefined; onSelect: (id: string | undefined) => void }) {
  const { t } = useI18n();
  if (rows.length === 0) return <p className="mdiagrams__none">{t('migration.steps.none')}</p>;
  return (
    <div className="msteps">
      <table className="qtable msteps__table" aria-label={t('migration.steps.caption')}>
        <thead>
          <tr>
            <th scope="col">{t('migration.steps.col.now')}</th>
            <th scope="col">{t('migration.steps.col.new', { version: String(plan.targetVersion) })}</th>
            <th scope="col">{t('migration.steps.col.cases')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className={selected === row.id ? 'is-selected' : undefined}>
              <th scope="row">
                <button type="button" className="msteps__pick hit" aria-pressed={selected === row.id} onClick={() => { onSelect(selected === row.id ? undefined : row.id); }}>{row.name}</button>
              </th>
              <td>
                {row.state === 'unmapped' ? (
                  <span className="msteps__flag"><span aria-hidden="true">!</span> {t('migration.steps.unmapped')} <span className="muted">{t('migration.steps.unmappedHelp')}</span></span>
                ) : (
                  <>
                    <span aria-hidden="true">→ </span><span className="sr-only">{t('diff.to')} </span><strong>{row.toName}</strong>
                    <span className="muted"> · {row.state === 'mapped' ? t('migration.steps.mapped') : t('migration.steps.cancelled', { reason: row.reason ?? '' })}</span>
                  </>
                )}
              </td>
              <td className="tnum">
                {row.cases === undefined ? <span className="muted" aria-label={t('rulesArea.none')}>—</span> : t(counted('migration.cases', row.cases), { count: String(row.cases) })}
                {row.inFlight > 0 ? <span className="muted"> · {t(counted('migration.steps.inFlight', row.inFlight), { count: String(row.inFlight) })}</span> : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="msteps__note">{t('migration.steps.same')}</p>
    </div>
  );
}

/** The case's reference, or a plain label; a masked reference stays masked. */
function CaseName({ instanceId }: { instanceId: string }) {
  const { t } = useI18n();
  const instance = useInstance(instanceId);
  if (instance.data === undefined) return instance.isError ? <span>{t('migration.case.unnamed')}</span> : <span className="muted">…</span>;
  return instance.data.businessKey !== null && instance.data.businessKey !== undefined && instance.data.businessKey !== ''
    ? <ReviewValue value={instance.data.businessKey} /> : <span>{t('migration.case.unnamed')}</span>;
}

/** The cases that stayed on the old version, each with its reason in words and no alarm: they can move in a later run. */
function NotMoved({ plan, pageLimit }: { plan: MigrationPlan; pageLimit: number }) {
  const { t } = useI18n();
  const outcomes = useNotMoved(plan.id, true, Math.min(pageLimit, 25));
  // The failed cases end, the skipped ones follow without a second click; a long list of either still loads on request.
  const { data, hasNextPage, isFetchingNextPage, fetchNextPage } = outcomes;
  const last = data?.pages.at(-1);
  const failedEnded = last?.outcome === 'FAILED' && (last.page.nextAfter === null || last.page.nextAfter === undefined);
  useEffect(() => {
    if (failedEnded && hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [failedEnded, hasNextPage, isFetchingNextPage, fetchNextPage]);
  if (outcomes.isPending) return <LoadingRows rows={4} />;
  if (outcomes.isError) return <AreaError error={outcomes.error} onRetry={() => { void outcomes.refetch(); }} />;
  const items = outcomes.data.pages.flatMap((page) => page.page.items);
  if (items.length === 0) return <div className="card__pad"><EmptyState icon="processes" text={t('migration.notMoved.empty')} /></div>;
  return (
    <div className="cases">
      <table className="qtable qtable--links" aria-label={t('migration.notMoved.caption', { source: String(plan.sourceVersion) })}>
        <thead>
          <tr>
            <th scope="col">{t('migration.notMoved.col.case')}</th>
            <th scope="col">{t('migration.notMoved.col.why')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.instanceId}>
              <th scope="row">
                <a className="qtable__link" href={formatRoute({ name: 'instance', instanceId: item.instanceId })}><CaseName instanceId={item.instanceId} /></a>
              </th>
              <td><span className="notmoved__mark" aria-hidden="true">◷</span> <span className="sr-only">{t('migration.notMoved.status')}. </span>{t(reasonKey(item.code))}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {outcomes.hasNextPage ? (
        <div className="cases__more">
          <button type="button" className="btn btn--secondary" disabled={outcomes.isFetchingNextPage} aria-busy={outcomes.isFetchingNextPage} onClick={() => { void outcomes.fetchNextPage(); }}>{t('cases.more')}</button>
        </div>
      ) : null}
    </div>
  );
}
