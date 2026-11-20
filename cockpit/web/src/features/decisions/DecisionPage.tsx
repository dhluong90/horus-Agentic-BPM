import { useState, type ReactNode } from 'react';
import { DECISION_ID, type DecisionExplanation, type ExplainedRow, type LoggedDecision } from '../../api/rules/auditSchemas';
import type { RuleSetDefinition } from '../../api/rules/schemas';
import { formatRoute } from '../../app/route';
import { CopyButton } from '../../components/CopyButton';
import { LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import '../../components/audit.css';
import { useRulesSession } from '../rules/api/rulesContext';
import { useVersion } from '../rules/api/useRules';
import { CellValue } from '../rules/components/CellValue';
import { DecisionTable } from '../rules/components/DecisionTable';
import { Tabs } from '../rules/components/Tabs';
import { formatDateTime } from '../rules/model/dates';
import { columnLabel, readCondition } from '../rules/model/reading';
import { useDecision, useExplanation } from './api/useAudit';
import { AuditError, LoggedValue, statusOf } from './components/AuditParts';
import { OutcomeBadge } from './components/OutcomeBadge';
import { DecisionLogLocked, knownOutcome } from './DecisionsPage';
import { lastDecisionQuery } from './lastQuery';
import { shortId } from './model/references';

type View = 'conditions' | 'inputs' | 'table';

/**
 * One logged decision (CKP-005, CKP-072): the engine's plain-language explanation first, then the matched rows'
 * conditions with the recorded values, every logged input, and the rule table with the matched row marked.
 * Masked inputs stay masked; the cockpit writes no sentence of its own about why (PD-027).
 */
export function DecisionPage({ decisionId }: { decisionId: string }) {
  const { can } = useRulesSession();
  if (!can('rules:log:read')) return <DecisionLogLocked titleId="decision-title" />;
  return <DecisionView decisionId={decisionId} />;
}

function BackLink() {
  const { t } = useI18n();
  return <a className="back-link" href={formatRoute({ name: 'decisions', query: lastDecisionQuery() })}><span aria-hidden="true">←</span> {t('dlog.back')}</a>;
}

function Frame({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <section className="workspace dlog-detail" aria-labelledby="decision-title">
      <BackLink />
      <PageHead id="decision-title" title={title} subtitle={subtitle} />
      {children}
    </section>
  );
}

function DecisionView({ decisionId }: { decisionId: string }) {
  const { t, locale } = useI18n();
  const { can, tenant } = useRulesSession();
  // PD-108: an id that is not a decision id is refused before any request.
  const wellFormed = DECISION_ID.test(decisionId);
  const decision = useDecision(decisionId, wellFormed);
  const explanation = useExplanation(decisionId, locale, wellFormed);
  const readsRules = can('rules:read');
  const version = useVersion(decision.data?.ruleSet ?? '', readsRules ? decision.data?.version : undefined);
  const heading = t('dlog.detailTitle', { id: shortId(decisionId) });

  if (!wellFormed) {
    return <Frame title={heading} subtitle={t('dlog.detailSubtitle')}><article className="card"><p className="notice-line">{t('dlog.error.notAnId')}</p></article></Frame>;
  }
  const failed = decision.error ?? explanation.error;
  if (failed !== null) {
    const status = statusOf(failed);
    const message = status === 404 ? t('dlog.error.notFound', { tenant }) : status === 403 ? t('dlog.error.forbidden') : status === 422 ? t('dlog.error.cannotExplain') : t('area.error.generic');
    const retry = status === 404 || status === 403 || status === 422 ? undefined : () => { void decision.refetch(); void explanation.refetch(); };
    return <Frame title={heading} subtitle={t('dlog.detailSubtitle')}><AuditError message={message} error={failed} onRetry={retry} /></Frame>;
  }
  if (decision.data === undefined || explanation.data === undefined) {
    return <Frame title={heading} subtitle={t('dlog.detailSubtitle')}><article className="card"><LoadingRows rows={3} /></article></Frame>;
  }
  const definition = version.data?.definition;
  const subtitle = t('dlog.decidedAt', { when: formatDateTime(decision.data.evaluatedAt, locale) ?? '' });
  return (
    <Frame title={t('dlog.ruleSetDecision', { ruleSet: decision.data.ruleSet })} subtitle={subtitle}>
      <Explanation decision={decision.data} explanation={explanation.data} />
      <Facts decision={decision.data} />
      <Views decision={decision.data} explanation={explanation.data} definition={definition} readsRules={readsRules} versionError={version.error} />
    </Frame>
  );
}

/** The focal card: the outcome and the engine's sentence, exactly as written by the engine. */
function Explanation({ decision, explanation }: { decision: LoggedDecision; explanation: DecisionExplanation }) {
  const { t } = useI18n();
  return (
    <article className="card why" aria-labelledby="why-title">
      <div className="why__head">
        <h2 id="why-title" className="card__title">{t('dlog.why')}</h2>
        <OutcomeBadge outcome={knownOutcome(decision.outcome)} matched={decision.matchedRuleIds} />
      </div>
      <p className="why__text" lang={explanation.language}>{explanation.text}</p>
    </article>
  );
}

function Facts({ decision }: { decision: LoggedDecision }) {
  const { t, locale } = useI18n();
  const { can } = useRulesSession();
  const versionLabel = t('rules.version', { version: String(decision.version) });
  return (
    <dl className="card dlog-facts">
      <div>
        <dt>{t('dlog.fact.version')}</dt>
        <dd>
          {can('rules:read')
            ? <a href={formatRoute({ name: 'rule-set', ruleSet: decision.ruleSet, version: decision.version, lens: 'table' })}>{decision.ruleSet} · {versionLabel}</a>
            : <>{decision.ruleSet} · {versionLabel}</>}
        </dd>
      </div>
      <div><dt>{t('dlog.fact.case')}</dt><dd className="ref-text">{decision.correlationId ?? <span className="muted">—</span>}</dd></div>
      <div><dt>{t('dlog.fact.clock')}</dt><dd className="tnum">{formatDateTime(decision.evalClock, locale)}{decision.clockChosen ? <span className="badge badge--warn dlog-facts__note">{t('dlog.clockChosen')}</span> : null}{decision.pinned ? <span className="badge badge--neutral dlog-facts__note">{t('dlog.pinned')}</span> : null}</dd></div>
      <div>
        <dt>{t('dlog.fact.id')}</dt>
        <dd className="dlog-facts__ref"><span className="ref-text" title={decision.decisionId}>{shortId(decision.decisionId)}</span> <CopyButton value={decision.decisionId} label={t('dlog.copyId')} copiedLabel={t('dlog.idCopied')} /></dd>
      </div>
    </dl>
  );
}

function Views({ decision, explanation, definition, readsRules, versionError }: {
  decision: LoggedDecision;
  explanation: DecisionExplanation;
  definition: RuleSetDefinition | undefined;
  readsRules: boolean;
  versionError: unknown;
}) {
  const { t } = useI18n();
  const [view, setView] = useState<View>('conditions');
  const items = [
    { id: 'conditions' as const, label: t('dlog.view.conditions') },
    { id: 'inputs' as const, label: t('dlog.view.inputs') },
    ...(readsRules ? [{ id: 'table' as const, label: t('dlog.view.table') }] : []),
  ];
  const label = (name: string) => columnLabel(name, [...(definition?.inputs ?? []), ...(definition?.outputs ?? [])].find((column) => column.name === name)?.description);
  return (
    <article className="card dlog-views">
      <Tabs label={t('dlog.views')} items={items} selected={view} onSelect={setView} className="dlog-views__tabs">
        {view === 'conditions' ? <Conditions explanation={explanation} label={label} /> : null}
        {view === 'inputs' ? <Inputs decision={decision} label={label} /> : null}
        {view === 'table' ? (
          definition !== undefined
            ? <div className="dlog-views__table"><DecisionTable definition={definition} version={decision.version} matched={new Set(decision.matchedRuleIds)} /></div>
            : versionError !== null && versionError !== undefined ? <AuditError message={t('dlog.error.table')} error={versionError} /> : <LoadingRows rows={3} />
        ) : null}
      </Tabs>
    </article>
  );
}

function Conditions({ explanation, label }: { explanation: DecisionExplanation; label: (name: string) => string }) {
  const { t } = useI18n();
  if (explanation.matched.length === 0) {
    return <p className="dlog-views__empty">{explanation.outcome === 'OK' ? t('dlog.noRowMatched') : t('dlog.failedNothing')}</p>;
  }
  return (
    <div className="dlog-rows">
      {explanation.matched.map((row) => <MatchedRow key={row.ruleId} row={row} label={label} />)}
      {explanation.outputs !== null && Object.keys(explanation.outputs).length > 0 ? (
        <section className="dlog-result" aria-labelledby="dlog-result-title">
          <h3 id="dlog-result-title" className="dlog-rows__title">{t('dlog.result')}</h3>
          <dl className="dlog-pairs">
            {Object.entries(explanation.outputs).map(([name, value]) => (
              <div key={name}><dt>{label(name)}</dt><dd><LoggedValue value={value} /></dd></div>
            ))}
          </dl>
        </section>
      ) : null}
    </div>
  );
}

function MatchedRow({ row, label }: { row: ExplainedRow; label: (name: string) => string }) {
  const { t, locale } = useI18n();
  const title = t('dlog.rowTitle', { rule: row.ruleId, position: String(row.position) });
  if (row.conditions.length === 0) {
    return <section className="dlog-row"><h3 className="dlog-rows__title">{title}</h3><p className="muted">{t('dlog.noConditions')}</p></section>;
  }
  return (
    <section className="dlog-row" aria-label={title}>
      <h3 className="dlog-rows__title">{title}</h3>
      <table className="qtable dlog-conditions">
        <thead>
          <tr>
            <th scope="col">{t('dlog.col.input')}</th>
            <th scope="col">{t('dlog.col.condition')}</th>
            <th scope="col">{t('dlog.col.recorded')}</th>
            <th scope="col">{t('dlog.col.held')}</th>
          </tr>
        </thead>
        <tbody>
          {row.conditions.map((condition) => (
            <tr key={condition.path}>
              <th scope="row">{label(condition.input)}</th>
              <td><CellValue reading={readCondition(condition.test, locale)} /></td>
              <td><LoggedValue value={condition.value} missing={condition.missing} /></td>
              <td>{condition.result === null
                ? <span className="muted">{t('dlog.heldUnknown')}</span>
                : condition.result ? <span className="held"><span aria-hidden="true">✓</span> {t('dlog.held')}</span> : <span className="muted">{t('dlog.notHeld')}</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function Inputs({ decision, label }: { decision: LoggedDecision; label: (name: string) => string }) {
  const { t } = useI18n();
  const entries = Object.entries(decision.inputs);
  if (entries.length === 0) return <p className="dlog-views__empty">{t('dlog.noInputs')}</p>;
  return (
    <table className="qtable dlog-inputs">
      <caption className="sr-only">{t('dlog.view.inputs')}</caption>
      <thead><tr><th scope="col">{t('dlog.col.input')}</th><th scope="col">{t('dlog.col.recorded')}</th></tr></thead>
      <tbody>
        {entries.map(([name, value]) => <tr key={name}><th scope="row">{label(name)}</th><td><LoggedValue value={value} /></td></tr>)}
      </tbody>
    </table>
  );
}
