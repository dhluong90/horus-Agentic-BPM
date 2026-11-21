import { useEffect, useId, useState } from 'react';
import type { ReviewContext, Task } from '../../../api/processes/schemas';
import { toHostError } from '../../../api/hostError';
import { setCrumbTrail } from '../../../app/crumbs';
import { formatRoute } from '../../../app/route';
import { CopyButton } from '../../../components/CopyButton';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { formatDateTime } from '../../rules/model/dates';
import { useProcessesSession } from '../api/processesContext';
import { useClaimTask, useCompleteTask, useDefinitionVersion, useInstance, useTask } from '../api/useProcesses';
import {
  buildAnswer,
  changesOf,
  containsMasked,
  fieldKind,
  fieldLabel,
  fourEyesBlock,
  initialText,
  refusalOf,
  schemaVerdict,
  shortReference,
  violationsByField,
  type FieldProblem,
} from '../model/review';
import { ReviewValue } from './ReviewValue';

/*
 * The review of an escalated agent step (CKP-007): what the agent received, its answer with the schema verdict, and
 * why the guardrail stopped it, on one screen; the decision docked at the bottom so it stays in view at 1440x900.
 * The reviewer approves the agent's answer or overrides it, always with a reason, which the engine records with
 * both identities (HUMAN_OVERRIDE). Four eyes: the identity that reported the answer and the starter of the case
 * cannot decide it; the screen says so and the engine refuses them anyway.
 */

type Mode = 'approve' | 'override';

const REFUSAL_KEY: Record<ReturnType<typeof refusalOf>, MessageKey> = {
  guardrail: 'review.refusal.guardrail',
  'guardrail-error': 'review.refusal.guardrailError',
  schema: 'review.refusal.schema',
  other: 'review.refusal.other',
};

const PROBLEM_KEY: Record<FieldProblem, MessageKey> = {
  required: 'review.problem.required',
  number: 'review.problem.number',
  'masked-fixed': 'review.problem.maskedFixed',
};

export function ReviewTaskPage({ taskId }: { taskId: string }) {
  const { t } = useI18n();
  const task = useTask(taskId);
  if (task.isPending) {
    return <section className="workspace" aria-busy="true"><LoadingRows rows={6} /></section>;
  }
  if (task.isError) {
    return (
      <section className="workspace" aria-labelledby="review-title">
        <PageHead id="review-title" title={t('review.crumb')} subtitle={t('review.subtitle')} />
        <article className="card"><AreaError error={task.error} onRetry={() => { void task.refetch(); }} /></article>
      </section>
    );
  }
  const review = task.data.review;
  if (review === undefined) {
    return (
      <section className="workspace" aria-labelledby="review-title">
        <PageHead id="review-title" title={t('review.crumb')} subtitle={t('review.notAgentSubtitle')} />
        <article className="card"><EmptyState icon="processes" text={t('review.notAgent')} /></article>
      </section>
    );
  }
  return <Review key={`${task.data.id}:${String(task.data.lockVersion)}`} task={task.data} review={review} />;
}

function Review({ task, review }: { task: Task; review: ReviewContext }) {
  const { t } = useI18n();
  // A masked answer cannot be sent back as is, so the review opens on the override.
  const [mode, setMode] = useState<Mode>(containsMasked(review.output) ? 'override' : 'approve');
  const [texts, setTexts] = useState<Record<string, string>>({});
  const [tried, setTried] = useState(false);
  const complete = useCompleteTask(task);
  const { userId } = useProcessesSession();
  const instance = useInstance(task.instanceId);
  const open = task.status === 'OPEN' || task.status === 'CLAIMED';
  const block = open ? fourEyesBlock(userId, review, instance.data) : undefined;
  const serverViolations = violationsOf(complete.error);
  const built = buildAnswer(review.output, texts);
  useReviewCrumbs(task);
  return (
    <section className="workspace review" aria-labelledby="review-title">
      <PageHead id="review-title" title={t('review.title')} subtitle={block !== undefined ? t('review.subtitleBlocked') : t('review.subtitle')} action={<TaskStatus task={task} />} />
      <ReviewMeta task={task} />
      <div className="review-grid">
        <article className="card review-card" aria-labelledby="review-inputs">
          <h2 id="review-inputs" className="card__title">{t('review.inputs')}</h2>
          <FieldList values={review.inputs} empty={t('review.noInputs')} />
        </article>
        <article className="card review-card review-card--answer" aria-labelledby="review-answer">
          <div className="review-card__head">
            <h2 id="review-answer" className="card__title">{mode === 'override' ? t('review.yourAnswer') : t('review.answer')}</h2>
            <SchemaVerdict code={review.code} />
          </div>
          {mode === 'override' ? (
            <AnswerEditor output={review.output} texts={texts} onChange={setTexts} problems={built.problems} showAll={tried} serverViolations={serverViolations.byField} />
          ) : <FieldList values={review.output} empty={t('review.noOutput')} />}
          {review.outputFields > Object.keys(review.output).length ? <p className="muted review-card__note">{t('review.moreFields', { count: String(review.outputFields) })}</p> : null}
        </article>
        <Guardrail review={review} />
      </div>
      <ReviewDock task={task} review={review} block={block} mode={mode} onMode={setMode} built={built} complete={complete} otherViolations={serverViolations.other} tried={tried} onTry={() => { setTried(true); }} />
    </section>
  );
}

/**
 * The way back from the review: Processes / the process / the case / Agent review. The process crumb waits for its
 * display name rather than show the key; the case crumb reads "Case APP-1042", or "Case" without a business key.
 */
function useReviewCrumbs(task: Task) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const definition = useDefinitionVersion(task.definitionKey, task.definitionVersion, can('processes:read'));
  const processName = definition.data?.name ?? undefined;
  const caseLabel = task.businessKey ? t('instance.title', { key: task.businessKey }) : t('instance.crumb');
  const reviewLabel = t('review.crumb');
  const readable = can('processes:read');
  useEffect(() => {
    setCrumbTrail([
      ...(processName !== undefined ? [{ label: processName, href: formatRoute({ name: 'process', key: task.definitionKey, version: task.definitionVersion, lens: 'cases' }) }] : []),
      ...(readable ? [{ label: caseLabel, href: formatRoute({ name: 'instance', instanceId: task.instanceId }) }] : []),
      { label: reviewLabel },
    ]);
  }, [processName, caseLabel, reviewLabel, readable, task.definitionKey, task.definitionVersion, task.instanceId]);
  useEffect(() => () => { setCrumbTrail(undefined); }, []);
}

/** Which case, step and process the review belongs to, by their names; a key is shown only when no name is known. */
function ReviewMeta({ task }: { task: Task }) {
  const { t, locale } = useI18n();
  const { can } = useProcessesSession();
  const definition = useDefinitionVersion(task.definitionKey, task.definitionVersion, can('processes:read'));
  const processName = definition.data?.name ?? undefined;
  return (
    <p className="review__meta">
      {task.businessKey ? <span>{t('review.case', { key: task.businessKey })}</span> : null}
      {task.name ? <span>{t('review.step', { name: task.name })}</span> : null}
      {processName !== undefined ? <span>{t('review.process', { name: processName, version: String(task.definitionVersion) })}</span> : null}
      <span>{t('review.created', { when: formatDateTime(task.createdAt, locale) ?? '' })}</span>
    </p>
  );
}

function violationsOf(error: unknown): { byField: Record<string, string>; other: string[] } {
  if (error === null || error === undefined) return { byField: {}, other: [] };
  const host = toHostError(error);
  const texts = host.kind === 'problem' && Array.isArray(host.members?.['violationTexts']) ? (host.members['violationTexts'] as string[]) : [];
  return violationsByField(texts);
}

function TaskStatus({ task }: { task: Task }) {
  const { t } = useI18n();
  const { userId } = useProcessesSession();
  if (task.status === 'COMPLETED') return <span className="badge badge--ok"><span aria-hidden="true">✓</span> {t('review.status.completed')}</span>;
  if (task.status === 'CANCELLED') return <span className="badge badge--muted"><span aria-hidden="true">■</span> {t('review.status.cancelled')}</span>;
  if (task.status === 'CLAIMED') {
    return <span className="badge badge--accent"><span aria-hidden="true">◷</span> {task.claimedBy === userId ? t('review.status.yours') : t('review.status.claimed', { who: task.claimedBy ?? '' })}</span>;
  }
  return <span className="badge badge--violet"><span aria-hidden="true">◇</span> {t('review.status.waiting')}</span>;
}

function FieldList({ values, empty }: { values: Readonly<Record<string, unknown>>; empty: string }) {
  const entries = Object.entries(values);
  if (entries.length === 0) return <p className="muted">{empty}</p>;
  return (
    <dl className="review-fields">
      {entries.map(([name, value]) => (
        <div key={name}>
          <dt>{fieldLabel(name)}</dt>
          <dd><ReviewValue value={value} copyable /></dd>
        </div>
      ))}
    </dl>
  );
}

function SchemaVerdict({ code }: { code: string }) {
  const { t } = useI18n();
  const verdict = schemaVerdict(code);
  if (verdict === 'unknown') return null;
  return verdict === 'passed'
    ? <span className="badge badge--ok"><span aria-hidden="true">✓</span> {t('review.schema.passed')}</span>
    : <span className="badge badge--bad"><span aria-hidden="true">✕</span> {t('review.schema.failed')}</span>;
}

function Guardrail({ review }: { review: ReviewContext }) {
  const { t } = useI18n();
  const engineNode = engineNodeOf(review.reportedBy);
  return (
    <article className="card review-card review-card--guardrail" aria-labelledby="review-why">
      <h2 id="review-why" className="card__title">{t('review.why')}</h2>
      <p className="review-why__sentence">{t(REFUSAL_KEY[refusalOf(review.code)])}</p>
      {review.ruleIds.length > 0 ? (
        <ul className="rule-pills" aria-label={t('review.rules')}>
          {review.ruleIds.map((id) => <li key={id} className="badge badge--violet">{t('review.rule', { id })}</li>)}
        </ul>
      ) : null}
      {review.reasons.length > 0 ? (
        <ul className="review-reasons">
          {review.reasons.map((reason, index) => <li key={index}>{reason}</li>)}
        </ul>
      ) : null}
      <dl className="review-why__facts">
        {review.reportedBy !== undefined ? <div><dt>{t('review.reportedBy')}</dt><dd>{reporterName(review.reportedBy, t)}</dd></div> : null}
        {review.guardrailDecisionId !== undefined ? (
          <div>
            <dt>{t('review.decisionRef')}</dt>
            <dd className="review-why__ref">
              <span className="mono" title={review.guardrailDecisionId}>{shortReference(review.guardrailDecisionId)}</span>
              <CopyButton value={review.guardrailDecisionId} label={t('review.copyRef')} />
            </dd>
          </div>
        ) : null}
      </dl>
      {engineNode !== undefined ? (
        <details className="alert__details review-why__details">
          <summary>{t('area.error.details')}</summary>
          <dl>
            <div><dt>{t('instance.fact.engineNode')}</dt><dd className="mono">{engineNode} <CopyButton value={engineNode} /></dd></div>
          </dl>
        </details>
      ) : null}
    </article>
  );
}

/** The engine node of an `engine:<node>` reporter, kept under Details; a person has none. */
function engineNodeOf(reportedBy: string | undefined): string | undefined {
  return reportedBy !== undefined ? /^engine:(.+)$/.exec(reportedBy)?.[1] : undefined;
}

/** Who reported the refused answer, in words: an engine node reads "Process Engine", never its id. */
function reporterName(reportedBy: string, t: (key: MessageKey, params?: Record<string, string>) => string): string {
  return engineNodeOf(reportedBy) !== undefined ? t('instance.engine') : reportedBy;
}

function AnswerEditor({ output, texts, onChange, problems, showAll, serverViolations }: {
  output: Readonly<Record<string, unknown>>;
  texts: Readonly<Record<string, string>>;
  onChange: (texts: Record<string, string>) => void;
  problems: Readonly<Record<string, FieldProblem>>;
  showAll: boolean;
  serverViolations: Readonly<Record<string, string>>;
}) {
  const { t } = useI18n();
  const base = useId();
  return (
    <div className="review-editor">
      {Object.entries(output).map(([field, original]) => {
        const kind = fieldKind(original);
        const id = `${base}-${field}`;
        const errorId = `${id}-error`;
        const problem = problems[field];
        const server = serverViolations[field];
        const message = problem !== undefined && (showAll || field in texts) ? t(PROBLEM_KEY[problem]) : server;
        const value = texts[field] ?? initialText(original);
        const set = (next: string) => { onChange({ ...texts, [field]: next }); };
        return (
          <div key={field} className="field review-editor__field">
            <label className="field__label" htmlFor={id}>{fieldLabel(field)}</label>
            {kind === 'fixed' ? (
              <div id={id} className="review-editor__fixed"><ReviewValue value={original} /> <span className="muted">{t('review.fixed')}</span></div>
            ) : kind === 'boolean' ? (
              <select id={id} className="field__input" value={value} onChange={(e) => { set(e.target.value); }}>
                <option value="true">{t('review.yes')}</option>
                <option value="false">{t('review.no')}</option>
              </select>
            ) : (
              <input id={id} className="field__input" value={value} inputMode={kind === 'number' ? 'decimal' : undefined}
                placeholder={kind === 'masked' ? t('review.maskedPlaceholder') : undefined}
                aria-invalid={message !== undefined} aria-describedby={message !== undefined ? errorId : undefined}
                onChange={(e) => { set(e.target.value); }} />
            )}
            {message !== undefined ? <p id={errorId} className="field__error">{message}</p> : null}
          </div>
        );
      })}
    </div>
  );
}

type Built = ReturnType<typeof buildAnswer>;

function ReviewDock({ task, review, block, mode, onMode, built, complete, otherViolations, tried, onTry }: {
  task: Task;
  /** Why four eyes keep this user from deciding, when they do. */
  block: ReturnType<typeof fourEyesBlock>;
  review: ReviewContext;
  mode: Mode;
  onMode: (mode: Mode) => void;
  built: Built;
  complete: ReturnType<typeof useCompleteTask>;
  otherViolations: readonly string[];
  tried: boolean;
  onTry: () => void;
}) {
  const { t, locale } = useI18n();
  const { userId } = useProcessesSession();
  const claim = useClaimTask(task);
  const [reason, setReason] = useState('');
  const reasonId = useId();
  const noteId = useId();
  const allowed = new Set(task.allowedActions);
  const masked = containsMasked(review.output);
  const changes = changesOf(review.output, built.result);

  const maskedId = useId();
  const blockedLine = block === 'reported' ? t('review.dock.selfReported') : block === 'started' ? t('review.dock.selfStarted') : undefined;
  let note: string | undefined;
  if (task.status === 'COMPLETED') note = t('review.dock.completed', { who: task.completedBy ?? '', when: formatDateTime(task.completedAt, locale) ?? '' });
  else if (task.status === 'CANCELLED') note = t('review.dock.cancelled');
  else if (task.status === 'CLAIMED' && task.claimedBy !== userId) note = t('review.dock.claimedByOther', { who: task.claimedBy ?? '' });
  else if (task.status === 'OPEN' && !allowed.has('claim')) note = t('review.dock.notAssigned', { assignee: task.assignee });
  else if (task.status === 'CLAIMED' && !allowed.has('complete')) note = t('review.dock.cannotComplete');

  const deciding = blockedLine === undefined && note === undefined && task.status === 'CLAIMED';
  const reasonMissing = reason.trim() === '';
  const formProblems = mode === 'override' ? Object.keys(built.problems).length : 0;
  const approveUnavailable = mode === 'approve' && masked;
  const nothingChanged = mode === 'override' && formProblems === 0 && changes.length === 0;
  const submit = () => {
    onTry();
    if (reasonMissing || approveUnavailable || formProblems > 0 || nothingChanged) return;
    complete.mutate({ result: mode === 'approve' ? review.output : built.result, reason: reason.trim() });
  };

  return (
    <section className="card review-dock" aria-labelledby="review-dock-title">
      <div className="review-dock__head">
        <h2 id="review-dock-title" className="rules-card-title">{t('review.dock.title')}</h2>
        {deciding ? (
          <div className="seg" role="group" aria-label={t('review.dock.modeLabel')}>
            <button type="button" className="hit" aria-pressed={mode === 'approve'} disabled={masked} aria-describedby={masked ? maskedId : undefined}
              onClick={() => { onMode('approve'); }}>
              {masked ? <span aria-hidden="true">◍ </span> : null}{t('review.dock.modeApprove')}
            </button>
            <button type="button" className="hit" aria-pressed={mode === 'override'} onClick={() => { onMode('override'); }}>{t('review.dock.modeOverride')}</button>
          </div>
        ) : null}
      </div>
      {blockedLine !== undefined ? (
        <div className="review-dock__row">
          <p className="review-dock__line review-dock__line--blocked" id={noteId}><span className="review-dock__icon" aria-hidden="true">⊘</span>{blockedLine}</p>
          {/* Kept in its place so the reason sits beside it, but not actionable: aria-disabled keeps it focusable for the reason. */}
          <button type="button" className="btn btn--primary" aria-disabled="true" aria-describedby={noteId} onClick={(e) => { e.preventDefault(); }}>{t('review.dock.start')}</button>
        </div>
      ) : note !== undefined ? (
        <p className="dock__note review-dock__line">{note}</p>
      ) : task.status === 'OPEN' ? (
        <div className="review-dock__row">
          <p className="dock__note review-dock__line">{t('review.dock.startHint')}</p>
          <button type="button" className="btn btn--primary" disabled={claim.isPending} aria-busy={claim.isPending} onClick={() => { claim.mutate(); }}>
            {claim.isPending ? t('review.dock.starting') : t('review.dock.start')}
          </button>
        </div>
      ) : (
        <>
          {masked ? <p className="review-dock__line review-dock__line--masked" id={maskedId}><span className="review-dock__icon" aria-hidden="true">◍</span>{t('review.dock.maskedApprove')}</p> : null}
          {mode === 'override' ? <ChangeSummary changes={changes} /> : null}
          <label className="field review-dock__reason" htmlFor={reasonId}>
            <span className="field__label">{t('review.dock.reason')}</span>
            <textarea id={reasonId} className="field__input" rows={2} value={reason} maxLength={2000} placeholder={t('review.dock.reasonHint')}
              aria-invalid={tried && reasonMissing} onChange={(e) => { setReason(e.target.value); }} />
          </label>
          <div className="review-dock__foot">
            <div className="review-dock__messages">
              {tried && reasonMissing ? <p className="field__error">{t('review.dock.reasonRequired')}</p> : null}
              {tried && nothingChanged ? <p className="field__error">{t('review.dock.nothingChanged')}</p> : null}
            </div>
            <button type="button" className={mode === 'approve' ? 'btn btn--primary btn--ok' : 'btn btn--primary'} disabled={complete.isPending || approveUnavailable}
              aria-busy={complete.isPending} onClick={submit}>
              {complete.isPending ? t('review.dock.sending') : mode === 'approve' ? t('review.dock.approve') : t('review.dock.override')}
            </button>
          </div>
        </>
      )}
      <DockError error={complete.error ?? claim.error} other={otherViolations} />
    </section>
  );
}

function ChangeSummary({ changes }: { changes: ReturnType<typeof changesOf> }) {
  const { t } = useI18n();
  if (changes.length === 0) return <p className="muted review-changes__none">{t('review.dock.noChanges')}</p>;
  return (
    <ul className="review-changes" aria-label={t('review.dock.changes')}>
      {changes.map((change) => (
        <li key={change.field}>
          <span className="review-changes__field">{fieldLabel(change.field)}</span>
          <span className="review-changes__from"><ReviewValue value={change.from} /></span>
          <span aria-hidden="true"> → </span><span className="sr-only">{t('review.dock.to')}</span>
          <span className="review-changes__to"><ReviewValue value={change.to} /></span>
        </li>
      ))}
    </ul>
  );
}

const CODE_KEY: Readonly<Record<string, MessageKey>> = {
  FORM_VALIDATION_FAILED: 'review.error.schema',
  SELF_APPROVAL_FORBIDDEN: 'review.error.fourEyes',
  TASK_NOT_ASSIGNED: 'review.error.notAssigned',
  VERSION_CONFLICT: 'review.error.changed',
  INVALID_STATE: 'review.error.changed',
};

function DockError({ error, other }: { error: unknown; other: readonly string[] }) {
  const { t } = useI18n();
  if (error === null || error === undefined) return null;
  const host = toHostError(error);
  const key = host.kind === 'problem' && host.code !== undefined ? CODE_KEY[host.code] : undefined;
  if (key === undefined) return <div className="review-dock__error"><AreaError error={error} onRetry={() => { window.location.reload(); }} /></div>;
  return (
    <div className="alert alert--error review-dock__error" role="alert">
      <span className="alert__icon" aria-hidden="true">✕</span>
      <div className="alert__body">
        <p>{t(key)}</p>
        {other.length > 0 ? <ul>{other.map((text) => <li key={text}>{text}</li>)}</ul> : null}
      </div>
    </div>
  );
}
