import { useState } from 'react';
import type { RuleSetSummary, VersionStatus } from '../../api/rules/schemas';
import { formatRoute, type Route } from '../../app/route';
import { AreaError, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { mapLoadable, useEveryRuleSet, useRuleSetsCut } from '../overview/useRuleOverview';
import { useRulesSession } from './api/rulesContext';
import { useImportRuleSet } from './api/useRules';
import { ImportFileButton } from './components/ImportFileButton';
import { RuleSetPage } from './RuleSetPage';
import { RuleSetTable } from './RuleSetTable';

/**
 * The Rules area of the menu: every route under #/rules renders here. The list of rule sets, and for one
 * rule set its version view: table, changes, history, source, the draft editor, the approval and Try it.
 */
export function RulesArea({ route, pageLimit }: { route: Extract<Route, { name: 'rule-sets' | 'rule-set' }>; pageLimit: number }) {
  if (route.name === 'rule-set') return <RuleSetPage name={route.ruleSet} versionNumber={route.version} lens={route.lens} />;
  return <RuleSetList pageLimit={pageLimit} />;
}

type Filter = 'all' | VersionStatus;
const FILTERS: readonly Filter[] = ['all', 'DRAFT', 'PENDING_APPROVAL', 'PUBLISHED'];

function RuleSetList({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  const { can } = useRulesSession();
  const ruleSets = useEveryRuleSet(pageLimit);
  const cut = useRuleSetsCut(pageLimit);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const importFile = useImportRuleSet((version) => {
    window.location.hash = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' });
  });
  // Import from a file stays available when the host turns cockpit drafts off (PD-075, CKP-032).
  const mayImport = can('rules:author');
  const counts = (items: readonly RuleSetSummary[]) => (status: Filter) => (status === 'all' ? items.length : items.filter((item) => item.latestStatus === status).length);
  const shown = mapLoadable(ruleSets, (items) => items.filter((item) => (filter === 'all' || item.latestStatus === filter) && item.ruleSet.includes(query.trim().toLowerCase())));
  const count = ruleSets.status === 'ready' ? counts(ruleSets.data) : undefined;
  return (
    <section className="workspace" aria-labelledby="rules-title">
      <PageHead
        id="rules-title"
        title={t('rulesArea.title')}
        subtitle={t('rulesArea.subtitle')}
        action={mayImport ? <ImportFileButton label={t('rulesArea.import')} busyLabel={t('rulesArea.importing')} busy={importFile.isPending} primary onDocument={(document) => { importFile.start(document); }} /> : undefined}
      />
      {importFile.isError ? <div className="page-alert"><AreaError error={importFile.error} onRetry={() => { importFile.reset(); }} /></div> : null}
      <div className="toolbar">
        <label className="search">
          <span className="sr-only">{t('rulesArea.find')}</span>
          <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><circle cx="7" cy="7" r="5" /><path d="M11 11l3.5 3.5" /></svg>
          <input type="search" value={query} placeholder={t('rulesArea.find')} onChange={(event) => { setQuery(event.target.value); }} />
        </label>
        <div className="seg" role="group" aria-label={t('rulesArea.col.status')}>
          {FILTERS.map((option) => (
            <button key={option} type="button" aria-pressed={filter === option} onClick={() => { setFilter(option); }}>
              {option === 'all' ? t('rulesArea.all') : t(`statusShort.${option}`)} <span className="seg__count tnum">{count?.(option) ?? ''}</span>
            </button>
          ))}
        </div>
      </div>
      {cut !== undefined ? <p className="list-note" role="note">{t('rulesArea.cut', { count: String(cut) })}</p> : null}
      <article className="card card--table">
        <RuleSetTable ruleSets={shown} emptyText={ruleSets.status === 'ready' && ruleSets.data.length > 0 ? t('rulesArea.noMatch') : t('rulesArea.empty')} />
      </article>
    </section>
  );
}
