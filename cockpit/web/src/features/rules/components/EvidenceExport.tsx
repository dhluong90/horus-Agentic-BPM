import { useEffect, useId, useRef, useState } from 'react';
import { MAX_EVIDENCE_DECISIONS, type EvidenceRequest } from '../../../api/rules/auditSchemas';
import type { EvidenceFile } from '../../../api/rules/rulesClient';
import type { VersionSummary } from '../../../api/rules/schemas';
import { saveFile } from '../../../app/saveFile';
import { CopyButton } from '../../../components/CopyButton';
import { useI18n } from '../../../i18n/I18nProvider';
import { useDecisionSearch, useExportEvidence } from '../../decisions/api/useAudit';
import { AuditError, codeOf, detailOf, LockIcon, statusOf } from '../../decisions/components/AuditParts';
import { MonthSelect } from '../../decisions/components/MonthSelect';
import { fileSize, monthOf, shortId } from '../../decisions/model/references';
import { formatDateTime } from '../model/dates';
import { useRulesSession } from '../api/rulesContext';

type Format = EvidenceRequest['format'];

/** Why Export is unavailable, in one line, or undefined when it is available. */
function blockedReason(versions: number, decisions: number): 'evidence.blocked.empty' | 'evidence.blocked.tooMany' | undefined {
  if (decisions > MAX_EVIDENCE_DECISIONS) return 'evidence.blocked.tooMany';
  if (versions === 0 && decisions === 0) return 'evidence.blocked.empty';
  return undefined;
}

/**
 * Export evidence (CKP-073, CKP-074, PD-116): choose versions, and decisions when the user may read the decision
 * log, then CSV or JSON. The engine writes, hashes and records the file; the cockpit saves its bytes unchanged and
 * shows what was exported with the SHA-256 the engine stated, in full with Copy. A refusal and the reason Export
 * is unavailable sit just above the button, so they stay in view beside the action.
 */
export function EvidenceExport({ ruleSet, versions }: { ruleSet: string; versions: readonly VersionSummary[] }) {
  const { t } = useI18n();
  const { can } = useRulesSession();
  const ids = useId();
  const [chosenVersions, setChosenVersions] = useState<ReadonlySet<number>>(new Set());
  const [chosenDecisions, setChosenDecisions] = useState<ReadonlySet<string>>(new Set());
  const [format, setFormat] = useState<Format>('csv');
  const [done, setDone] = useState<{ file: EvidenceFile; request: EvidenceRequest } | undefined>(undefined);
  const exporter = useExportEvidence(ruleSet, (file, request) => {
    saveFile(file.file, file.fileName);
    setDone({ file, request });
  });
  const problem = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = problem.current;
    // Brings a refusal into view beside Export; a browser without scrolling (a test DOM) simply shows it.
    if (exporter.isError && element !== null && 'scrollIntoView' in element) element.scrollIntoView({ block: 'nearest' });
  }, [exporter.isError]);
  const mayAddDecisions = can('rules:log:read');
  const reason = blockedReason(chosenVersions.size, mayAddDecisions ? chosenDecisions.size : 0);
  const flip = <T,>(set: ReadonlySet<T>, value: T): Set<T> => {
    const next = new Set(set);
    if (next.has(value)) next.delete(value);
    else next.add(value);
    return next;
  };
  const start = () => {
    if (reason !== undefined) return;
    exporter.start({
      format,
      versions: [...chosenVersions].sort((a, b) => a - b),
      decisionIds: mayAddDecisions ? [...chosenDecisions] : [],
    });
  };

  if (done !== undefined) {
    return <Exported ruleSet={ruleSet} done={done} onAgain={() => { exporter.reset(); setDone(undefined); }} />;
  }
  return (
    <section className="card evidence" aria-labelledby={`${ids}-title`}>
      <div className="evidence__head">
        <h2 id={`${ids}-title`} className="card__title">{t('evidence.title')}</h2>
        <p className="evidence__sub">{t('evidence.subtitle')}</p>
      </div>
      <fieldset className="evidence__group">
        <legend className="field__label">{t('evidence.versions')}</legend>
        <div className="evidence__versions">
          {versions.map((version) => (
            <label key={version.version} className="evidence__version">
              <input type="checkbox" checked={chosenVersions.has(version.version)} onChange={() => { setChosenVersions(flip(chosenVersions, version.version)); }} />
              <span>{t('evidence.versionChip', { version: String(version.version), status: t(`statusShort.${version.status}`) })}</span>
            </label>
          ))}
        </div>
      </fieldset>
      {mayAddDecisions ? (
        <DecisionPicker ruleSet={ruleSet} chosen={chosenDecisions} onChange={setChosenDecisions} />
      ) : (
        <p className="evidence__locked"><LockIcon /> {t('evidence.decisionsLocked')}</p>
      )}
      {exporter.isError ? <div ref={problem} className="evidence__problem"><EvidenceProblem error={exporter.error} chosen={[...chosenDecisions]} /></div> : null}
      {reason !== undefined ? <p id={`${ids}-reason`} className="gate__reason evidence__reason">{t(reason)}</p> : null}
      <div className="evidence__foot">
        <div className="seg" role="group" aria-label={t('evidence.format')}>
          {(['csv', 'json'] as const).map((option) => (
            <button key={option} type="button" aria-pressed={format === option} onClick={() => { setFormat(option); }}>{option.toUpperCase()}</button>
          ))}
        </div>
        <button type="button" className="btn btn--primary evidence__go" disabled={reason !== undefined || exporter.isPending} aria-busy={exporter.isPending}
          aria-describedby={reason !== undefined ? `${ids}-reason` : undefined} onClick={start}>
          {exporter.isPending ? t('evidence.exporting') : t('evidence.export')}
        </button>
      </div>
    </section>
  );
}

/**
 * The decisions of this rule set in one month, to tick. One file holds at most MAX_EVIDENCE_DECISIONS: when the
 * month has more, the bulk action chooses the first that fit, the count says how many are chosen and why, and the
 * other boxes are unavailable once the limit is reached.
 */
function DecisionPicker({ ruleSet, chosen, onChange }: { ruleSet: string; chosen: ReadonlySet<string>; onChange: (next: ReadonlySet<string>) => void }) {
  const { t, locale } = useI18n();
  const ids = useId();
  const [month, setMonth] = useState(monthOf(new Date()));
  const found = useDecisionSearch({ ruleSet, month, pageSize: 200 });
  const shown = found.data?.pages.flatMap((page) => page.data) ?? [];
  const full = chosen.size >= MAX_EVIDENCE_DECISIONS;
  const fitsAll = shown.length <= MAX_EVIDENCE_DECISIONS;
  const chooseFirst = () => {
    const next = new Set(chosen);
    for (const decision of shown) {
      if (next.size >= MAX_EVIDENCE_DECISIONS) break;
      next.add(decision.decisionId);
    }
    onChange(next);
  };
  const toggle = (id: string) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onChange(next);
  };
  return (
    <fieldset className="evidence__group">
      <legend className="field__label">{t('evidence.decisions')}</legend>
      <div className="evidence__month">
        <label className="sr-only" htmlFor={`${ids}-month`}>{t('evidence.month')}</label>
        <MonthSelect id={`${ids}-month`} className="field__input field__input--compact" value={month} onChange={setMonth} />
        <span className="muted tnum" role="status">{t('evidence.chosen', { count: String(chosen.size) })}</span>
      </div>
      {!fitsAll || full ? <p className="evidence__limit">{t('evidence.limit', { limit: String(MAX_EVIDENCE_DECISIONS) })}</p> : null}
      {found.isError ? <AuditError message={statusOf(found.error) === 403 ? t('dlog.error.forbidden') : t('area.error.generic')} error={found.error} /> : null}
      {found.isPending ? <p className="muted evidence__empty" role="status">{t('area.loading')}</p> : null}
      {found.isSuccess && shown.length === 0 ? <p className="muted evidence__empty">{t('evidence.noDecisions')}</p> : null}
      {shown.length > 0 ? (
        <>
          <ul className="evidence__decisions" aria-label={t('evidence.decisionsFound', { count: String(shown.length) })}>
            {shown.map((decision) => (
              <li key={decision.decisionId}>
                <label className="evidence__decision">
                  <input type="checkbox" checked={chosen.has(decision.decisionId)} disabled={full && !chosen.has(decision.decisionId)} onChange={() => { toggle(decision.decisionId); }} />
                  <span className="tnum">{formatDateTime(decision.evaluatedAt, locale)}</span>
                  <span className="ref-text">{shortId(decision.decisionId)}</span>
                </label>
              </li>
            ))}
          </ul>
          <div className="evidence__bulk">
            {fitsAll
              ? <button type="button" className="linkbtn" onClick={chooseFirst}>{t('evidence.chooseAll', { count: String(shown.length) })}</button>
              : <button type="button" className="linkbtn" disabled={full} onClick={chooseFirst}>{t('evidence.chooseFirst', { count: String(MAX_EVIDENCE_DECISIONS) })}</button>}
            {chosen.size > 0 ? <button type="button" className="linkbtn" onClick={() => { onChange(new Set()); }}>{t('evidence.clear')}</button> : null}
          </div>
        </>
      ) : null}
    </fieldset>
  );
}

/** What was exported, in words, and the file's SHA-256 as the engine stated it. */
function Exported({ ruleSet, done, onAgain }: { ruleSet: string; done: { file: EvidenceFile; request: EvidenceRequest }; onAgain: () => void }) {
  const { t, locale } = useI18n();
  const { file, request } = done;
  const size = fileSize(file.bytes, locale);
  const versions = request.versions.length > 0 ? t('evidence.versionList', { list: request.versions.join(', ') }) : t('evidence.noVersions');
  return (
    <section className="card evidence evidence--done" aria-labelledby="evidence-done-title">
      <div role="status" className="evidence__done">
        <h2 id="evidence-done-title" className="card__title"><span className="evidence__tick" aria-hidden="true">✓</span> {t('evidence.done')}</h2>
        <p className="evidence__sub">{t('evidence.recorded', { ruleSet })}</p>
      </div>
      <dl className="evidence__facts">
        <div><dt>{t('evidence.fact.contains')}</dt><dd>{versions} · {t('evidence.decisionCount', { count: String(request.decisionIds.length) })}</dd></div>
        <div><dt>{t('evidence.fact.file')}</dt><dd><span className="evidence__file">{file.fileName}</span> <span className="muted tnum">· {request.format.toUpperCase()} · {t(`evidence.size.${size.unit}`, { value: size.value })}</span></dd></div>
        <div>
          <dt>{t('evidence.sha')}</dt>
          <dd className="evidence__hash">
            <span className="ref-text" data-testid="evidence-sha">{file.contentSha256}</span>
            <CopyButton value={file.contentSha256} label={t('evidence.copySha')} copiedLabel={t('evidence.shaCopied')} />
          </dd>
        </div>
      </dl>
      <p className="dock__hint">{t('evidence.shaHint')}</p>
      <button type="button" className="btn btn--quiet evidence__again" onClick={onAgain}>{t('evidence.again')}</button>
    </section>
  );
}

/** A refused export in the user's words: too large, forbidden, not found, or a decision of another rule set. */
function EvidenceProblem({ error, chosen }: { error: unknown; chosen: readonly string[] }) {
  const { t } = useI18n();
  const { tenant } = useRulesSession();
  const status = statusOf(error);
  const code = codeOf(error);
  // The engine names the refused decision in its detail; the cockpit only looks for ids it sent.
  const named = chosen.find((id) => detailOf(error).includes(id));
  let message: string;
  if (code === 'EVIDENCE_TOO_LARGE') message = t('evidence.error.tooLarge');
  else if (status === 403) message = t('evidence.error.forbidden');
  else if (status === 404) message = named !== undefined ? t('evidence.error.decisionNotFound', { id: shortId(named), tenant }) : t('evidence.error.notFound');
  else if (status === 400 && named !== undefined) message = t('evidence.error.otherRuleSet', { id: shortId(named) });
  else if (status === 422 || status === 400 || status === 413) message = t('evidence.error.refused');
  else message = t('area.error.generic');
  return <AuditError message={message} error={error} />;
}
