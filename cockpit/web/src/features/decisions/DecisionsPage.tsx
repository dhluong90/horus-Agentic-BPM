import type { MessageKey } from '../../i18n/en';
import { useEffect, useId, useState, type FormEvent } from 'react';
import { CASE_REFERENCE, DECISION_OUTCOMES, type LoggedDecision } from '../../api/rules/auditSchemas';
import { formatRoute, type DecisionQuery } from '../../app/route';
import { EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import '../../components/audit.css';
import { useEveryRuleSet } from '../overview/useRuleOverview';
import { useRulesSession } from '../rules/api/rulesContext';
import { formatDateTime } from '../rules/model/dates';
import { useDecisionSearch, type DecisionSearchKey } from './api/useAudit';
import { AuditError, LockIcon, statusOf } from './components/AuditParts';
import { MonthSelect } from './components/MonthSelect';
import { OutcomeBadge } from './components/OutcomeBadge';
import { rememberDecisionQuery } from './lastQuery';
import { monthLabel, monthOf, shortId } from './model/references';

/** Decisions per page of the list; the engine allows up to 200. */
const PAGE_SIZE = 50;

type Mode = 'ruleSet' | 'case';

/** The search a URL names, when it is complete enough to send. */
function searchOf(query: DecisionQuery): DecisionSearchKey | undefined {
  if (query.month === undefined) return undefined;
  if (query.caseRef !== undefined) return CASE_REFERENCE.test(query.caseRef) ? { caseRef: query.caseRef, month: query.month, pageSize: PAGE_SIZE } : undefined;
  if (query.ruleSet !== undefined) return { ruleSet: query.ruleSet, month: query.month, pageSize: PAGE_SIZE };
  return undefined;
}

/** The page a user without the decision log permission sees: what is missing, in one line, and no request is sent. */
export function DecisionLogLocked({ titleId, message = 'dlog.locked' }: { titleId: string; message?: MessageKey }) {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby={titleId}>
      <PageHead id={titleId} title={t('decisions.title')} subtitle={t('decisions.subtitle')} preview />
      <article className="card locked-state" role="note">
        <span className="locked-state__icon"><LockIcon /></span>
        <p className="locked-state__text">{t(message)}</p>
        <a className="btn btn--quiet locked-state__action" href={formatRoute({ name: 'home' })}>{t('dlog.locked.goHome')}</a>
      </article>
    </section>
  );
}

/**
 * The decision explorer (CKP-071, RLE-029): find the decisions of one rule set, or of one case reference, in a
 * calendar month, newest first, and open one to read why it came out as it did.
 */
export function DecisionsPage({ query, pageLimit }: { query: DecisionQuery; pageLimit: number }) {
  const { can } = useRulesSession();
  if (!can('rules:log:read')) return <DecisionLogLocked titleId="decisions-title" />;
  return <Explorer query={query} pageLimit={pageLimit} />;
}

function Explorer({ query, pageLimit }: { query: DecisionQuery; pageLimit: number }) {
  const { t, locale } = useI18n();
  const { can } = useRulesSession();
  const ids = useId();
  const ruleSets = useEveryRuleSet(pageLimit, can('rules:read'));
  const [mode, setMode] = useState<Mode>(query.caseRef !== undefined ? 'case' : 'ruleSet');
  const [ruleSet, setRuleSet] = useState(query.ruleSet ?? '');
  const [caseRef, setCaseRef] = useState(query.caseRef ?? '');
  const [month, setMonth] = useState(query.month ?? monthOf(new Date()));
  const [caseError, setCaseError] = useState(false);
  const search = searchOf(query);
  const results = useDecisionSearch(search);
  useEffect(() => { rememberDecisionQuery(query); }, [query]);
  const names = ruleSets.status === 'ready' ? ruleSets.data.map((item) => item.ruleSet) : [];
  const chosenRuleSet = ruleSet !== '' ? ruleSet : (names[0] ?? '');

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (mode === 'case') {
      const reference = caseRef.trim();
      // A basic format check only; the engine decides everything else.
      if (!CASE_REFERENCE.test(reference)) { setCaseError(true); return; }
      setCaseError(false);
      window.location.hash = formatRoute({ name: 'decisions', query: { caseRef: reference, month } });
      return;
    }
    if (chosenRuleSet === '') return;
    window.location.hash = formatRoute({ name: 'decisions', query: { ruleSet: chosenRuleSet, month } });
  };

  const decisions = results.data?.pages.flatMap((page) => page.data) ?? [];
  const scope = search?.caseRef !== undefined ? t('dlog.scopeCase', { ref: search.caseRef }) : search?.ruleSet ?? '';
  return (
    <section className="workspace dlog" aria-labelledby="decisions-title">
      <PageHead id="decisions-title" title={t('decisions.title')} subtitle={t('decisions.subtitle')} preview />
      <form className="card dlog-search" onSubmit={submit} aria-label={t('dlog.searchLabel')}>
        <div className="seg dlog-search__mode" role="group" aria-label={t('dlog.findBy')}>
          <button type="button" aria-pressed={mode === 'ruleSet'} onClick={() => { setMode('ruleSet'); }}>{t('dlog.byRuleSet')}</button>
          <button type="button" aria-pressed={mode === 'case'} onClick={() => { setMode('case'); }}>{t('dlog.byCase')}</button>
        </div>
        {mode === 'ruleSet' ? (
          <label className="dlog-search__field">
            <span className="field__label">{t('dlog.ruleSet')}</span>
            {names.length > 0 ? (
              <select className="field__input" value={chosenRuleSet} onChange={(event) => { setRuleSet(event.target.value); }}>
                {names.map((name) => <option key={name} value={name}>{name}</option>)}
              </select>
            ) : (
              <input className="field__input" value={ruleSet} onChange={(event) => { setRuleSet(event.target.value.trim()); }} autoComplete="off" spellCheck={false} />
            )}
          </label>
        ) : (
          <label className="dlog-search__field dlog-search__field--wide">
            <span className="field__label">{t('dlog.caseRef')}</span>
            <input className={caseError ? 'field__input field__input--bad' : 'field__input'} value={caseRef} placeholder={t('dlog.caseRefHint')} autoComplete="off" spellCheck={false}
              aria-invalid={caseError} aria-describedby={caseError ? `${ids}-case-error` : undefined}
              onChange={(event) => { setCaseRef(event.target.value); setCaseError(false); }} />
          </label>
        )}
        <label className="dlog-search__field">
          <span className="field__label">{t('dlog.month')}</span>
          <MonthSelect value={month} onChange={setMonth} />
        </label>
        <button type="submit" className="btn btn--primary dlog-search__go" disabled={mode === 'ruleSet' && chosenRuleSet === ''}>{t('dlog.search')}</button>
        {caseError ? <p id={`${ids}-case-error`} className="field__error dlog-search__error"><span aria-hidden="true">✕</span> {t('dlog.caseRefInvalid')}</p> : null}
      </form>

      <article className="card card--table dlog-results" aria-labelledby="dlog-results-title">
        <div className="card__head card__head--padded">
          <h2 id="dlog-results-title" className="card__title">
            {search === undefined ? t('dlog.results') : t('dlog.resultsFor', { scope, month: monthLabel(search.month, locale) })}
          </h2>
          {results.isSuccess ? <span className="muted tnum" role="status">{t(results.hasNextPage ? 'dlog.countMore' : 'dlog.count', { count: String(decisions.length) })}</span> : null}
        </div>
        <Results search={search} results={results} decisions={decisions} />
      </article>
    </section>
  );
}

function Results({ search, results, decisions }: { search: DecisionSearchKey | undefined; results: ReturnType<typeof useDecisionSearch>; decisions: LoggedDecision[] }) {
  const { t, locale } = useI18n();
  if (search === undefined) return <EmptyState icon="decisions" text={t('dlog.start')} />;
  if (results.isPending) return <div className="card__pad"><LoadingRows rows={4} /></div>;
  if (results.isError) {
    const status = statusOf(results.error);
    const message = status === 403 ? t('dlog.error.forbidden') : status === 400 ? t('dlog.error.query') : t('area.error.generic');
    return <div className="card__pad"><AuditError message={message} error={results.error} onRetry={status === 403 || status === 400 ? undefined : () => { void results.refetch(); }} /></div>;
  }
  if (decisions.length === 0) return <EmptyState text={t('dlog.none')} />;
  return (
    <>
      <table className="qtable qtable--links dlog-table">
        <caption className="sr-only">{t('dlog.results')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('dlog.col.when')}</th>
            <th scope="col">{t('dlog.col.ruleSet')}</th>
            <th scope="col">{t('dlog.col.result')}</th>
            <th scope="col">{t('dlog.col.case')}</th>
            <th scope="col">{t('dlog.col.decision')}</th>
            <th scope="col"><span className="sr-only">{t('dlog.open')}</span></th>
          </tr>
        </thead>
        <tbody>
          {decisions.map((decision) => (
            <tr key={decision.decisionId}>
              <td className="tnum">
                <a className="qtable__link" href={formatRoute({ name: 'decision', decisionId: decision.decisionId })} aria-label={t('dlog.openOne', { id: shortId(decision.decisionId), when: formatDateTime(decision.evaluatedAt, locale) ?? '' })}>
                  {formatDateTime(decision.evaluatedAt, locale)}
                </a>
              </td>
              <td>{decision.ruleSet} <span className="muted">· {t('rules.version', { version: String(decision.version) })}</span></td>
              <td><OutcomeBadge outcome={knownOutcome(decision.outcome, decision.matchedRuleIds)} matched={decision.matchedRuleIds} /></td>
              <td className="ref-text">{decision.correlationId ?? <span className="muted">—</span>}</td>
              <td className="ref-text">{shortId(decision.decisionId)}</td>
              <td className="qtable__chevron" aria-hidden="true">›</td>
            </tr>
          ))}
        </tbody>
      </table>
      {results.hasNextPage ? (
        <div className="list-more">
          <button type="button" className="btn btn--quiet" disabled={results.isFetchingNextPage} aria-busy={results.isFetchingNextPage} onClick={() => { void results.fetchNextPage(); }}>
            {results.isFetchingNextPage ? t('area.loading') : t('dlog.more')}
          </button>
        </div>
      ) : null}
    </>
  );
}

/** A decision failed when the engine reported an error or several rules hit; a match or no match is a normal answer. */
export function isFailedOutcome(outcome: (typeof DECISION_OUTCOMES)[number] | 'OTHER'): boolean {
  return outcome !== 'MATCHED' && outcome !== 'NO_MATCH';
}

/**
 * One reading of an outcome for every screen. The list endpoint names MATCHED and NO_MATCH; the detail and
 * explanation endpoints return the stored value `OK`, which is a match when rules matched and a no-match when none did.
 */
export function knownOutcome(outcome: string, matched: readonly unknown[] = []): (typeof DECISION_OUTCOMES)[number] | 'OTHER' {
  if (outcome === 'OK') return matched.length === 0 ? 'NO_MATCH' : 'MATCHED';
  return (DECISION_OUTCOMES as readonly string[]).includes(outcome) ? (outcome as (typeof DECISION_OUTCOMES)[number]) : 'OTHER';
}
