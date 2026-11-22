import { useId, useState } from 'react';
import { toHostError, type HostError } from '../../api/hostError';
import type { Incident } from '../../api/processes/schemas';
import type { IncidentTab } from '../../app/route';
import { useHostLink } from '../federation/HostScope';
import { CopyButton } from '../../components/CopyButton';
import { AreaError, LoadingRows } from '../../components/PageParts';
import type { Translate } from '../../i18n/I18nProvider';
import { useI18n } from '../../i18n/I18nProvider';
import { useProcessesSession } from '../processes/api/processesContext';
import { useDefinitionVersion } from '../processes/api/useProcesses';
import { fieldLabel } from '../processes/model/review';
import { ReviewValue } from '../processes/review/ReviewValue';
import { formatDateTime } from '../rules/model/dates';
import { useIncident, useIncidentCase, useIncidentCommand, type IncidentCommand } from './api/useIncidents';
import { ReasonDialog, ResolveDialog, type DialogRefusal } from './IncidentDialogs';
import { IncidentState } from './parts';
import { approveBlock, causeKey, failingFields, isUndo, MANAGE_INCIDENTS, refusalOf, retryBlock } from './model/incidents';

/** A refusal shown above the list, with the engine's answer behind Details. */
export interface IncidentNotice {
  text: string;
  error?: unknown;
}

type Dialog = 'retry' | 'resolve' | 'approve' | 'reject';

/**
 * The selected incident: the step, the case, the cause in words, the retry count and, when a result is proposed,
 * who proposed what and why. The actions are the engine's `allowedActions` for this user, with one primary action;
 * an action the user cannot take is shown unavailable with the reason in words beside it.
 */
export function IncidentInspector({ summary, step, mode, onNotice, onDone }: {
  summary: Incident;
  /** The step's name, as the list shows it. */
  step: string;
  mode: 'four-eyes' | 'single';
  onNotice: (notice: IncidentNotice | undefined) => void;
  onDone: (text: string, next?: { view: IncidentTab; incidentId?: string | undefined }) => void;
}) {
  const detail = useIncident(summary.id);
  return (
    <aside className="card tasks__inspector incidents__inspector" aria-labelledby="incident-title">
      <h2 id="incident-title" className="card__title">{step}</h2>
      {detail.isPending ? <LoadingRows rows={4} /> : detail.isError ? (
        <AreaError error={detail.error} onRetry={() => { void detail.refetch(); }} />
      ) : (
        <Detail incident={detail.data} step={step} mode={mode} onNotice={onNotice} onDone={onDone} />
      )}
    </aside>
  );
}

function Detail({ incident, step, mode, onNotice, onDone }: {
  incident: Incident;
  step: string;
  mode: 'four-eyes' | 'single';
  onNotice: (notice: IncidentNotice | undefined) => void;
  onDone: (text: string, next?: { view: IncidentTab; incidentId?: string | undefined }) => void;
}) {
  const { t, locale, label } = useI18n();
  const { can, userId } = useProcessesSession();
  const hostLink = useHostLink();
  const command = useIncidentCommand(incident);
  const caseOf = useIncidentCase(incident.instanceId);
  const [dialog, setDialog] = useState<Dialog | undefined>(undefined);
  const whyId = useId();
  const manage = can(MANAGE_INCIDENTS);
  const allowed = new Set(incident.allowedActions);
  const pending = incident.status === 'RESOLUTION_PENDING' ? incident.pendingResolution : null;

  /** Sends a command; resolves to the refusal for the dialog, or undefined after the engine accepted it. */
  const send = async (next: IncidentCommand): Promise<DialogRefusal | undefined> => {
    onNotice(undefined);
    try {
      const answer = await command.mutateAsync(next);
      setDialog(undefined);
      done(next, answer, step, t, onDone);
      return undefined;
    } catch (error) {
      const host = toHostError(error);
      return { text: refusalText(host, t), error, fields: failingFields(host) };
    }
  };

  const why = pending !== null ? approveBlock(incident, userId, manage) : retryBlock(incident, manage);
  const showRetry = pending === null && (allowed.has('retry') || manage);
  const showResolve = pending === null && allowed.has('resolve');
  const showDecision = pending !== null && (allowed.has('approve-resolution') || manage);

  return (
    <>
      <div className="tasks__state"><IncidentState incident={incident} /></div>
      <dl className="facts-list tasks__facts">
        {caseOf.data?.businessKey ? (
          <div><dt>{t('incidents.fact.case')}</dt><dd><a href={hostLink({ name: 'instance', instanceId: incident.instanceId })}>{caseOf.data.businessKey}</a></dd></div>
        ) : null}
        {caseOf.data ? <div><dt>{t('incidents.fact.process')}</dt><dd><ProcessName definitionKey={caseOf.data.definitionKey} version={caseOf.data.definitionVersion} /></dd></div> : null}
        <div><dt>{t('incidents.fact.cause')}</dt><dd>{t(causeKey(incident.errorCode))}</dd></div>
        <div><dt>{t('incidents.fact.retries')}</dt><dd className="tnum">{incident.retryCount}</dd></div>
        <div><dt>{t('incidents.fact.raised')}</dt><dd className="tnum">{formatDateTime(incident.createdAt, locale)}</dd></div>
      </dl>
      {pending !== null ? (
        <section className="incidents__proposal" aria-labelledby="proposal-title">
          <h3 id="proposal-title" className="incidents__subtitle">{t('incidents.proposal.title')}</h3>
          <p className="tasks__line">{t('incidents.proposal.when', { when: formatDateTime(pending.proposedAt, locale) ?? '' })}</p>
          {pending.reason ? <q className="history__reason">{pending.reason}</q> : null}
          <dl className="facts-list tasks__facts incidents__output">
            {Object.entries(pending.output).map(([name, value]) => (
              <div key={name}><dt>{label(`label.field.${name}`) ?? fieldLabel(name)}</dt><dd><ReviewValue value={value} /></dd></div>
            ))}
          </dl>
        </section>
      ) : null}
      {manage || showRetry || showDecision ? (
        <div className="tasks__actions">
          {why !== undefined && (showRetry || showDecision) ? <p className="tasks__why" id={whyId}><span aria-hidden="true" className="tasks__line-icon">⊘</span>{t(why)}</p> : null}
          <div className="tasks__buttons">
            {showRetry ? (
              allowed.has('retry') ? (
                <button type="button" className="btn btn--primary" disabled={command.isPending} onClick={() => { setDialog('retry'); }}>{t('incidents.action.retry')}</button>
              ) : (
                <button type="button" className="btn btn--primary" aria-disabled="true" aria-describedby={whyId} onClick={(event) => { event.preventDefault(); }}>{t('incidents.action.retry')}</button>
              )
            ) : null}
            {showResolve ? (
              <button type="button" className="btn" disabled={command.isPending} onClick={() => { setDialog('resolve'); }}>{t('incidents.action.resolve')}</button>
            ) : null}
            {showDecision ? (
              allowed.has('approve-resolution') ? (
                <button type="button" className="btn btn--primary" disabled={command.isPending} onClick={() => { setDialog('approve'); }}>{t('incidents.action.approve')}</button>
              ) : (
                <button type="button" className="btn btn--primary" aria-disabled="true" aria-describedby={whyId} onClick={(event) => { event.preventDefault(); }}>{t('incidents.action.approve')}</button>
              )
            ) : null}
            {pending !== null && allowed.has('reject-resolution') ? (
              <button type="button" className="btn btn--danger-quiet" disabled={command.isPending} onClick={() => { setDialog('reject'); }}>{t('incidents.action.reject')}</button>
            ) : null}
          </div>
        </div>
      ) : (
        <p className="tasks__why incidents__readonly"><span aria-hidden="true" className="tasks__line-icon">◍</span>{t('incidents.why.permission')}</p>
      )}
      <details className="alert__details incidents__engine">
        <summary>{t('area.error.details')} <span aria-hidden="true">▾</span></summary>
        <dl>
          <div><dt>{t('incidents.fact.summary')}</dt><dd className="mono">{incident.causeSummary}</dd></div>
          <div><dt>{t('error.code')}</dt><dd className="mono">{incident.errorCode} <CopyButton value={incident.errorCode} iconOnly label={t('error.copyCode')} /></dd></div>
        </dl>
      </details>
      {dialog === 'retry' ? (
        <ReasonDialog title={t('incidents.retry.title', { step })} text={t('incidents.retry.text')} label={t('incidents.reason')} required
          submit={t('incidents.action.retry')} sending={command.isPending} sendingLabel={t('incidents.retry.sending')}
          onClose={() => { setDialog(undefined); }} onSubmit={(reason) => send({ kind: 'retry', reason })} />
      ) : null}
      {dialog === 'reject' ? (
        <ReasonDialog title={t('incidents.reject.title', { step })} text={t('incidents.reject.text')} label={t('incidents.reason')} required danger
          submit={t('incidents.action.reject')} sending={command.isPending} sendingLabel={t('incidents.reject.sending')}
          onClose={() => { setDialog(undefined); }} onSubmit={(reason) => send({ kind: 'reject', reason })} />
      ) : null}
      {dialog === 'approve' ? (
        <ReasonDialog title={t('incidents.approve.title', { step })} text={t('incidents.approve.text')} label={t('incidents.comment')} required={false}
          submit={t('incidents.action.approve')} sending={command.isPending} sendingLabel={t('incidents.approve.sending')}
          onClose={() => { setDialog(undefined); }} onSubmit={(comment) => send({ kind: 'approve', comment })} />
      ) : null}
      {dialog === 'resolve' ? (
        <ResolveDialog step={step} undo={isUndo(incident)} mode={mode} sending={command.isPending}
          onClose={() => { setDialog(undefined); }} onSubmit={(resolution) => send({ kind: 'resolve', resolution })} />
      ) : null}
    </>
  );
}

/** The process by its display name, as the engine names its version; its key in words until that is read. */
function ProcessName({ definitionKey, version }: { definitionKey: string; version: number }) {
  const definition = useDefinitionVersion(definitionKey, version);
  return <>{definition.data?.name ?? fieldLabel(definitionKey)}</>;
}

/** After the engine's answer: what happened, in one sentence, and the list to show next. */
function done(command: IncidentCommand, answer: Incident, step: string, t: Translate,
  onDone: (text: string, next?: { view: IncidentTab; incidentId?: string | undefined }) => void): void {
  switch (command.kind) {
    case 'retry':
      onDone(t('incidents.done.retry', { step }));
      return;
    case 'resolve':
      if (answer.status === 'RESOLUTION_PENDING') onDone(t('incidents.done.proposed', { step }), { view: 'pending', incidentId: answer.id });
      else onDone(t('incidents.done.resolved', { step }), { view: 'open' });
      return;
    case 'approve':
      onDone(t('incidents.done.approved', { step }), { view: 'pending' });
      return;
    case 'reject':
      onDone(t('incidents.done.rejected', { step }), { view: 'open', incidentId: answer.id });
      return;
  }
}

function refusalText(error: HostError, t: Translate): string {
  switch (refusalOf(error)) {
    case 'changed':
      return t('incidents.refused.changed');
    case 'moved':
      return t('incidents.refused.moved');
    case 'self':
      return t('incidents.why.self');
    case 'output': {
      const fields = failingFields(error).map(fieldLabel);
      return fields.length > 0 ? t('incidents.refused.outputFields', { fields: fields.join(', ') }) : t('incidents.refused.output');
    }
    case 'forbidden':
      return t('incidents.refused.forbidden');
    case 'not-found':
      return t('incidents.refused.notFound');
    case 'other':
      return t('area.error.generic');
  }
}
