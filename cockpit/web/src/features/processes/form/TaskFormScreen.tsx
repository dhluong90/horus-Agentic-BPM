import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { Task, TaskForm } from '../../../api/processes/schemas';
import { toHostError } from '../../../api/hostError';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { useHostLink } from '../../federation/HostScope';
import { StatusMark, taskTitle } from '../../tasks/parts';
import { formatDateTime } from '../../rules/model/dates';
import { groupWhileTyping } from '../../rules/model/money';
import { useProcessesSession } from '../api/processesContext';
import { useClaimTask, useCompleteForm, useTaskForm } from '../api/useProcesses';
import { ReviewMeta, useReviewCrumbs } from '../review/ReviewParts';
import { apiErrorsOf, build, currencyKey, fieldsOf, initialDrafts, isRequired, recordedRows, type Drafts, type FormField, type Problem } from './model';

/*
 * A task completed through the form its process declares (CKP-021). The engine owns the form and checks the result;
 * this screen draws the fields, checks only their format, keeps masked values masked, and shows the engine's
 * refusal under the field it names. A task declared with a link opens the bank's own screen instead.
 */

const PROBLEM_KEY: Record<Problem, MessageKey> = {
  required: 'form.problem.required',
  number: 'form.problem.number',
  integer: 'form.problem.integer',
  money: 'form.problem.money',
  moneyScale: 'form.problem.moneyScale',
  moneyRange: 'form.problem.moneyRange',
  moneyCurrency: 'form.problem.moneyCurrency',
  date: 'form.problem.date',
  datetime: 'form.problem.datetime',
  choice: 'form.problem.choice',
  tooLong: 'form.problem.tooLong',
};

export function TaskFormScreen({ task }: { task: Task }) {
  const { t } = useI18n();
  const title = taskTitle(task, t);
  const form = useTaskForm(task.id, true);
  useReviewCrumbs(task, title);
  const host = form.isError ? toHostError(form.error) : undefined;
  const none = host?.kind === 'problem' && host.code === 'FORM_NOT_DECLARED';
  return (
    <section className="workspace review taskform" aria-labelledby="taskform-title" aria-busy={form.isPending}>
      <PageHead id="taskform-title" title={title} subtitle={t(task.status === 'COMPLETED' ? 'form.subtitleDone' : 'form.subtitle')} action={<StatusMark status={task.status} claimedBy={task.claimedBy} />} />
      <ReviewMeta task={task} createdKey="form.created" />
      {form.isPending ? (
        <article className="card"><LoadingRows rows={4} /></article>
      ) : none ? (
        <article className="card"><EmptyState icon="processes" text={t('form.none')} /></article>
      ) : form.isError ? (
        <article className="card"><AreaError error={form.error} onRetry={() => { void form.refetch(); }} /></article>
      ) : form.data.link !== undefined ? (
        <LinkOut task={task} link={form.data.link} title={title} />
      ) : task.status === 'COMPLETED' ? (
        <RecordedDecision task={task} form={form.data} title={title} />
      ) : (
        <FormBody task={task} form={form.data} title={title} />
      )}
    </section>
  );
}

/** Why this user cannot work the task now, in words; undefined when they can. */
function useWhyNot(task: Task): string | undefined {
  const { t, locale } = useI18n();
  const { userId } = useProcessesSession();
  const allowed = new Set(task.allowedActions);
  if (task.status === 'COMPLETED') return t('form.dock.completed', { who: task.completedBy ?? '', when: formatDateTime(task.completedAt, locale) ?? '' });
  if (task.status === 'CANCELLED') return t('form.dock.cancelled');
  if (task.status === 'CLAIMED' && task.claimedBy !== userId) return t('form.dock.claimedByOther', { who: task.claimedBy ?? '' });
  if (task.status === 'OPEN' && !allowed.has('claim')) return task.fourEyes ? t('form.dock.fourEyes', { who: task.assignee.replace(/^(role|group|user):/, '').replace(/[_-]/g, ' ').toLowerCase() }) : t('form.dock.notAssigned');
  if (task.status === 'CLAIMED' && !allowed.has('complete')) return t('form.dock.cannotComplete');
  return undefined;
}

function LinkOut({ task, link, title }: { task: Task; link: string; title: string }) {
  const { t } = useI18n();
  const why = useWhyNot(task);
  const claim = useClaimTask(task);
  const open = task.status === 'CLAIMED' && why === undefined;
  return (
    <>
      <article className="card taskform__card" aria-labelledby="taskform-link-title">
        <h2 id="taskform-link-title" className="card__title">{t('form.link.title')}</h2>
        <p>{t('form.link.text', { task: title })}</p>
      </article>
      <section className="card review-dock" aria-labelledby="taskform-dock-title">
        <h2 id="taskform-dock-title" className="rules-card-title">{t('form.dock.title')}</h2>
        <div className="review-dock__row">
          {why !== undefined ? <p className="dock__note review-dock__line">{why}</p> : task.status === 'OPEN' ? <p className="dock__note review-dock__line">{t('form.dock.claimHint')}</p> : <span />}
          {open ? (
            <a className="btn btn--primary" href={link} target="_blank" rel="noopener noreferrer">{t('form.link.open')} <span aria-hidden="true">↗</span><span className="sr-only"> {t('form.link.newTab')}</span></a>
          ) : task.status === 'OPEN' && why === undefined ? (
            <button type="button" className="btn btn--primary" disabled={claim.isPending} aria-busy={claim.isPending} onClick={() => { claim.mutate(); }}>
              {claim.isPending ? t('form.dock.claiming') : t('form.dock.claim')}
            </button>
          ) : null}
        </div>
        {claim.isError ? <div className="review-dock__error"><AreaError error={claim.error} onRetry={() => { claim.reset(); }} /></div> : null}
      </section>
    </>
  );
}

/** A completed task: what was submitted, by whom and when, read-only. The values are the engine's, masked fields stay masked. */
function RecordedDecision({ task, form, title }: { task: Task; form: TaskForm; title: string }) {
  const { t, locale } = useI18n();
  const hostLink = useHostLink();
  const content = form.form;
  const rows = useMemo(() => {
    if (content === undefined) return [];
    return recordedRows(fieldsOf(content, locale), content.data, locale, { yes: t('review.yes'), no: t('review.no'), when: (iso) => formatDateTime(iso, locale) });
  }, [content, locale, t]);
  return (
    <article className="card taskform__card taskform__recorded" aria-labelledby="taskform-recorded">
      <h2 id="taskform-recorded" className="card__title">{t('form.recorded')}</h2>
      <p className="taskform__signed">{t('form.dock.completed', { who: task.completedBy ?? '', when: formatDateTime(task.completedAt, locale) ?? '' })}</p>
      {rows.length === 0 ? <p className="muted">{t('form.noFields')}</p> : (
        <dl className="taskform__grid taskform__values" aria-label={title}>
          {rows.map((row) => (
            <div key={row.name} className={row.wide ? 'taskform__value taskform__field--wide' : 'taskform__value'}>
              <dt className="field__label">{row.label}</dt>
              <dd>
                {row.masked ? <span className="masked__tag"><span aria-hidden="true">◍</span> {t('form.masked')}</span>
                  : row.text !== undefined ? row.text : <span className="muted"><span aria-hidden="true">—</span><span className="sr-only">{t('form.notRecorded')}</span></span>}
              </dd>
            </div>
          ))}
        </dl>
      )}
      <a className="link-action" href={hostLink({ name: 'tasks', view: 'mine' })}>{t('form.dock.back')} <span aria-hidden="true">→</span></a>
    </article>
  );
}

function FormBody({ task, form, title }: { task: Task; form: TaskForm; title: string }) {
  const { t, locale } = useI18n();
  const hostLink = useHostLink();
  const formId = useId();
  const formRef = useRef<HTMLFormElement>(null);
  const content = form.form;
  const fields = useMemo(() => (content === undefined ? [] : fieldsOf(content, locale)), [content, locale]);
  const data = useMemo(() => content?.data ?? {}, [content]);
  const [drafts, setDrafts] = useState<Drafts>(() => initialDrafts(fields, data, locale));
  const [replaced, setReplaced] = useState<ReadonlySet<string>>(new Set());
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const [tried, setTried] = useState(false);
  // Fields edited since the engine last refused them: its sentence about the old value no longer applies.
  const [edited, setEdited] = useState<ReadonlySet<string>>(new Set());
  const claim = useClaimTask(task);
  const complete = useCompleteForm(task.id, form.lockVersion);
  const why = useWhyNot(task);
  const working = task.status === 'CLAIMED' && why === undefined;
  const refusedByFourEyes = task.fourEyes && task.status === 'OPEN' && !task.allowedActions.includes('claim');
  const built = useMemo(() => build(fields, drafts, data, locale, replaced), [fields, drafts, data, locale, replaced]);
  // What each choice leads to, beside the action: the chosen one stands out.
  const effectsOf = fields.flatMap((field) => Object.entries(field.effects).map(([code, text]) => ({
    code, text, label: `${field.optionLabels[code] ?? code}:`, chosen: drafts[field.name] === code,
  })));
  const refused = complete.error === null ? undefined : toHostError(complete.error);
  const api = apiErrorsOf(refused?.kind === 'problem' ? refused.members?.['errors'] : undefined);

  // After the engine's refusal, the focus goes to the first field it names.
  useEffect(() => {
    if (complete.isError) formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
  }, [complete.isError, complete.error]);

  const change = (name: string, value: string) => {
    setDrafts((current) => ({ ...current, [name]: value }));
    setTouched((current) => new Set(current).add(name));
    setEdited((current) => new Set(current).add(name));
  };
  const problemsCount = Object.keys(built.problems).length;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setTried(true);
    if (!working || complete.isPending) return;
    if (problemsCount > 0) {
      requestAnimationFrame(() => { formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(); });
      return;
    }
    setEdited(new Set());
    complete.mutate(built.result);
  };

  const messageOf = (field: FormField): string | undefined => {
    const problem = built.problems[field.name];
    if (problem !== undefined && (tried || touched.has(field.name))) return t(PROBLEM_KEY[problem]);
    const server = api.byField[field.name];
    if (server !== undefined && !edited.has(field.name)) return serverText(server, t);
    return undefined;
  };

  return (
    <>
      <article className="card taskform__card" aria-labelledby="taskform-fields">
        <h2 id="taskform-fields" className="card__title">{t('form.fields')}</h2>
        {fields.length === 0 ? <p className="muted">{t('form.noFields')}</p> : (
          <form id={formId} ref={formRef} className="taskform__grid" noValidate onSubmit={submit} aria-label={title}>
            {fields.map((field) => (
              <FieldRow key={field.name} field={field} drafts={drafts} locale={locale} message={messageOf(field)}
                disabled={!working || complete.isPending} replaced={replaced.has(field.name)}
                onChange={change}
                onReplace={(on) => {
                  setReplaced((current) => { const next = new Set(current); if (on) next.add(field.name); else next.delete(field.name); return next; });
                  if (!on) setDrafts((current) => ({ ...current, [field.name]: '' }));
                }} />
            ))}
          </form>
        )}
      </article>
      <section className="card review-dock" aria-labelledby="taskform-dock-title">
        <h2 id="taskform-dock-title" className="rules-card-title">{t('form.dock.title')}</h2>
        {working && effectsOf.length > 0 ? (
          <ul className="taskform__effects" aria-label={t('form.effects')}>
            {effectsOf.map((item) => <li key={item.code} className={item.chosen ? 'is-chosen' : undefined}><strong>{item.label}</strong> {item.text}</li>)}
          </ul>
        ) : null}
        {working ? (
          <div className="review-dock__foot">
            <div className="review-dock__messages" role="status">
              {tried && problemsCount > 0 ? <p className="field__error">{t(problemsCount === 1 ? 'form.dock.fix.one' : 'form.dock.fix.other', { count: String(problemsCount) })}</p> : null}
              {fields.some((field) => field.masked) ? <p className="muted">{t('form.dock.masked')}</p> : null}
            </div>
            <button type="submit" form={formId} className="btn btn--primary" disabled={complete.isPending} aria-busy={complete.isPending}>
              {complete.isPending ? t('form.dock.sending') : t('form.dock.complete')}
            </button>
          </div>
        ) : (
          <div className="review-dock__row">
            <p className={refusedByFourEyes ? 'dock__self review-dock__line' : 'dock__note review-dock__line'}>{why ?? t('form.dock.claimHint')}</p>
            {task.status === 'OPEN' && why === undefined ? (
              <button type="button" className="btn btn--primary" disabled={claim.isPending} aria-busy={claim.isPending} onClick={() => { claim.mutate(); }}>
                {claim.isPending ? t('form.dock.claiming') : t('form.dock.claim')}
              </button>
            ) : task.status === 'COMPLETED' ? (
              <a className="link-action" href={hostLink({ name: 'tasks', view: 'mine' })}>{t('form.dock.back')} <span aria-hidden="true">→</span></a>
            ) : null}
          </div>
        )}
        <DockError error={complete.error ?? claim.error} other={api.other} />
      </section>
    </>
  );
}

/** The engine's own words for the rule it names; `required` is the one it says most. */
function serverText(rule: string, t: (key: MessageKey) => string): string {
  return rule === 'required' ? t('form.problem.required') : rule;
}

function FieldRow({ field, drafts, locale, message, disabled, replaced, onChange, onReplace }: {
  field: FormField;
  drafts: Drafts;
  locale: Locale;
  message: string | undefined;
  disabled: boolean;
  replaced: boolean;
  onChange: (key: string, value: string) => void;
  onReplace: (on: boolean) => void;
}) {
  const { t } = useI18n();
  const base = useId();
  const id = `${base}-${field.name}`;
  const errorId = `${id}-error`;
  const value = drafts[field.name] ?? '';
  const kind = field.kind;
  const common = {
    id,
    disabled: disabled || field.readOnly,
    'aria-invalid': message !== undefined,
    'aria-required': isRequired(field, drafts),
    'aria-describedby': message !== undefined ? errorId : undefined,
  } as const;
  const wide = kind.kind === 'text' && kind.multiline;
  const label = (
    <>
      {field.label}
      {isRequired(field, drafts) ? <span className="taskform__star" aria-hidden="true"> *</span> : null}
    </>
  );
  let control: React.ReactNode;
  if (field.masked && !replaced) {
    control = (
      <div className="taskform__masked">
        <span id={id} className="masked__tag"><span aria-hidden="true">◍</span> {t('form.masked')}</span>
        {!field.readOnly ? <button type="button" className="btn btn--quiet btn--small" disabled={disabled} onClick={() => { onReplace(true); }}>{t('form.replace')}</button> : null}
      </div>
    );
  } else if (kind.kind === 'choice' && kind.radio || kind.kind === 'yesno') {
    const options = kind.kind === 'yesno' ? ['true', 'false'] : kind.options;
    return (
      <fieldset className="field taskform__group" disabled={disabled || field.readOnly} aria-describedby={message !== undefined ? errorId : undefined}>
        <legend className="field__label">{label}</legend>
        <div className="taskform__options">
          {options.map((option) => (
            <label key={option} className="taskform__option">
              <input type="radio" name={id} value={option} checked={value === option} aria-invalid={message !== undefined} onChange={() => { onChange(field.name, option); }} />
              <span>{kind.kind === 'yesno' ? t(option === 'true' ? 'review.yes' : 'review.no') : field.optionLabels[option] ?? option}</span>
            </label>
          ))}
        </div>
        {message !== undefined ? <p id={errorId} className="field__error">{message}</p> : null}
      </fieldset>
    );
  } else if (kind.kind === 'choice') {
    control = (
      <select {...common} className="field__input" value={value} onChange={(e) => { onChange(field.name, e.target.value); }}>
        <option value="">{t('form.choose')}</option>
        {kind.options.map((option) => <option key={option} value={option}>{field.optionLabels[option] ?? option}</option>)}
      </select>
    );
  } else if (kind.kind === 'text' && kind.multiline) {
    control = <textarea {...common} className="field__input" rows={2} value={value} onChange={(e) => { onChange(field.name, e.target.value); }} />;
  } else if (kind.kind === 'date') {
    control = <input {...common} className="field__input" type="date" value={value} onChange={(e) => { onChange(field.name, e.target.value); }} />;
  } else if (kind.kind === 'datetime') {
    control = <input {...common} className="field__input" type="datetime-local" value={value} onChange={(e) => { onChange(field.name, e.target.value); }} />;
  } else if (kind.kind === 'money') {
    const currency = drafts[currencyKey(field.name)] ?? '';
    control = (
      <div className="taskform__money">
        {kind.currencies.length === 1 ? (
          <span className="taskform__currency" aria-label={t('form.currency')}>{kind.currencies[0]}</span>
        ) : kind.currencies.length > 1 ? (
          <select className="field__input taskform__currency" aria-label={t('form.currency')} disabled={common.disabled} value={currency}
            onChange={(e) => { onChange(currencyKey(field.name), e.target.value); }}>
            {kind.currencies.map((option) => <option key={option} value={option}>{option}</option>)}
          </select>
        ) : (
          <input className="field__input taskform__currency" aria-label={t('form.currency')} disabled={common.disabled} maxLength={3} placeholder="VND" value={currency}
            onChange={(e) => { onChange(currencyKey(field.name), e.target.value.toUpperCase()); }} />
        )}
        <input {...common} className="field__input tnum taskform__amount" inputMode="decimal" autoComplete="off" value={value}
          onChange={(e) => { onChange(field.name, groupWhileTyping(e.target.value, locale)); }} />
      </div>
    );
  } else {
    const grouped = kind.kind === 'number' && kind.grouped;
    const input = (
      <input {...common} className={grouped ? 'field__input tnum' : 'field__input'} type="text" autoComplete="off" value={value}
        inputMode={kind.kind === 'number' ? (kind.integer ? 'numeric' : 'decimal') : undefined}
        onChange={(e) => { onChange(field.name, grouped ? groupWhileTyping(e.target.value, locale) : e.target.value); }} />
    );
    control = field.unit !== undefined ? <div className="taskform__money">{input}<span className="taskform__unit">{field.unit}</span></div> : input;
  }
  return (
    <div className={wide ? 'field taskform__field taskform__field--wide' : 'field taskform__field'}>
      {field.masked && !replaced ? <span className="field__label">{label}</span> : <label className="field__label" htmlFor={id}>{label}</label>}
      {control}
      {field.masked && replaced ? <button type="button" className="btn btn--quiet btn--small taskform__keep" onClick={() => { onReplace(false); }}>{t('form.keepMasked')}</button> : null}
      {message !== undefined ? <p id={errorId} className="field__error">{message}</p> : null}
    </div>
  );
}

const CODE_KEY: Readonly<Record<string, MessageKey>> = {
  FORM_VALIDATION_FAILED: 'form.error.invalid',
  SELF_APPROVAL_FORBIDDEN: 'form.error.fourEyes',
  TASK_NOT_ASSIGNED: 'form.error.notAssigned',
  VERSION_CONFLICT: 'form.error.changed',
  INVALID_STATE: 'form.error.changed',
  FORM_NOT_DECLARED: 'form.none',
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
