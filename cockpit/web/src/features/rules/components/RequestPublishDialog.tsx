import { useEffect, useId, useRef, useState } from 'react';
import type { SimulationReport, VersionSummary } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { useCreateReport, useReports } from '../api/useRules';
import type { Action } from './ActionDock';
import { formatCount, ReportStatus, useRunText, useScopeText } from './ReportParts';
import { ProblemNotice } from './ProblemNotice';
import { AreaError, LoadingRows } from '../../../components/PageParts';

/** The most days one replay may cover (the engine's query.max-range is 31). */
const REPLAY_DAYS = 30;
const REPLAY_LIMIT = 100;

/**
 * Request publish when the host lists the rule set in lifecycle.require-simulation-report (PD-115): the author
 * attaches a report that was run on the content the version has now, or runs one here. Request publish stays disabled,
 * with its reason, until one is chosen; the engine refuses a request without a current report all the same.
 */
export function RequestPublishDialog({ version, request, onClose }: { version: VersionSummary; request: Action<string | undefined>; onClose: () => void }) {
  const { t } = useI18n();
  const { can } = useRulesSession();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const reasonId = useId();
  const reports = useReports(version.ruleSet, version.version);
  const [chosen, setChosen] = useState<string | undefined>(undefined);
  // Run once per click; the replay covers the last 30 days up to the moment of the click.
  const run = useCreateReport(version.ruleSet, version.version, (report) => { setChosen(report.id); });

  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);

  const current = (reports.data ?? []).filter((report) => report.status === 'CURRENT');
  const stale = (reports.data ?? []).filter((report) => report.status !== 'CURRENT');
  // The newest current report is offered first; the author never has to hunt for it.
  const picked = chosen ?? current[0]?.id;
  const mayRun = can('rules:simulate');
  const startRun = () => {
    const to = new Date();
    const from = new Date(to.getTime() - REPLAY_DAYS * 86_400_000);
    run.start({ source: 'REPLAY', from: from.toISOString(), to: to.toISOString(), limit: REPLAY_LIMIT });
  };

  return (
    <dialog ref={dialog} className="dialog dialog--wide" aria-labelledby={titleId} onClose={onClose}>
      <div className="dialog__body">
        <h2 id={titleId} className="dialog__title">{t('publish.title', { version: String(version.version) })}</h2>
        <p className="dialog__text">{t('publish.text')}</p>
        <fieldset className="report-pick">
          <legend className="field__label">{t('publish.pick')}</legend>
          {reports.isPending ? <LoadingRows rows={2} /> : null}
          {reports.isError ? <AreaError error={reports.error} onRetry={() => { void reports.refetch(); }} /> : null}
          {reports.isSuccess && reports.data.length === 0 ? <p className="muted" role="status">{t('publish.none')}</p> : null}
          {current.map((report) => <Choice key={report.id} report={report} checked={picked === report.id} onPick={() => { setChosen(report.id); }} />)}
          {stale.map((report) => <Choice key={report.id} report={report} checked={false} disabled onPick={() => undefined} />)}
        </fieldset>
        <div className="report-pick__run">
          <button type="button" className="btn" disabled={!mayRun || run.isPending} aria-busy={run.isPending} onClick={startRun}>
            {run.isPending ? t('publish.running') : t('publish.run')}
          </button>
          <span className="muted">{mayRun ? t('publish.runHint', { days: String(REPLAY_DAYS), limit: formatCount(REPLAY_LIMIT, 'en') }) : t('publish.cannotRun')}</span>
        </div>
        {run.isError ? <ProblemNotice error={run.error} /> : null}
        {request.error !== undefined && request.error !== null ? <ProblemNotice error={request.error} /> : null}
        <div className="dialog__actions">
          {picked === undefined ? <p id={reasonId} className="reason-line new-set__reason"><span aria-hidden="true">⊘</span> {t('publish.reason')}</p> : null}
          <button type="button" className="btn btn--quiet" onClick={onClose}>{t('action.cancel')}</button>
          <button type="button" className="btn btn--primary" disabled={picked === undefined || request.isPending} aria-busy={request.isPending}
            {...(picked === undefined ? { 'aria-describedby': reasonId } : {})} onClick={() => { request.start(picked); }}>
            {request.isPending ? t('dock.requesting') : t('dock.requestPublish')}
          </button>
        </div>
      </div>
    </dialog>
  );
}

function Choice({ report, checked, disabled = false, onPick }: { report: SimulationReport; checked: boolean; disabled?: boolean; onPick: () => void }) {
  const { t, locale } = useI18n();
  const scope = useScopeText(report);
  const run = useRunText(report);
  return (
    <label className={checked ? 'tpl tpl--picked tpl--row' : disabled ? 'tpl tpl--row tpl--off' : 'tpl tpl--row'}>
      <input type="radio" name="report" checked={checked} disabled={disabled} onChange={onPick} />
      <span className="tpl__label">{scope} <ReportStatus status={report.status} /></span>
      <span className="tpl__text">{run}</span>
      <span className="tpl__meta"><span>{t('publish.counts', { changed: formatCount(report.counts.changed, locale), total: formatCount(report.counts.total, locale) })}</span></span>
    </label>
  );
}
