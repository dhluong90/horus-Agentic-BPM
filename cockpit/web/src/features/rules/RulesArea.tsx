import { useState } from 'react';
import type { RuleSetSummary, VersionStatus } from '../../api/rules/schemas';
import { suppliedName, usePersonName, type PersonRole } from '../../app/people';
import { formatRoute, type Route } from '../../app/route';
import { AreaError, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { useRetryRules } from '../overview/useRetryRules';
import { mapLoadable, useEveryRuleSet } from '../overview/useRuleOverview';
import { useRulesSession } from './api/rulesContext';
import { useImportRuleSet, useVersions } from './api/useRules';
import { ImportFileButton } from './components/ImportFileButton';
import { StatusBadge } from './components/StatusBadge';
import { formatDateTime } from './model/dates';
import { RuleSetTable } from './RuleSetTable';

/**
 * The Rules area of the menu: every route under #/rules renders here. This release shows the rule set list
 * and the version list read only; the rule editing screens replace the version view when they land.
 */
export function RulesArea({ route, pageLimit }: { route: Extract<Route, { name: 'rule-sets' | 'rule-set' }>; pageLimit: number }) {
  if (route.name === 'rule-set') return <RuleSetVersions name={route.ruleSet} highlight={route.version} />;
  return <RuleSetList pageLimit={pageLimit} />;
}

type Filter = 'all' | VersionStatus;
const FILTERS: readonly Filter[] = ['all', 'DRAFT', 'PENDING_APPROVAL', 'PUBLISHED'];

function RuleSetList({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  const { can, cockpitDrafts } = useRulesSession();
  const ruleSets = useEveryRuleSet(pageLimit);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const importFile = useImportRuleSet((version) => {
    window.location.hash = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' });
  });
  const mayImport = can('rules:author') && cockpitDrafts;
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
      <article className="card card--table">
        <RuleSetTable ruleSets={shown} emptyText={ruleSets.status === 'ready' && ruleSets.data.length > 0 ? t('rulesArea.noMatch') : t('rulesArea.empty')} />
      </article>
    </section>
  );
}

function RuleSetVersions({ name, highlight }: { name: string; highlight: number | undefined }) {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="rule-set-title">
      <PageHead id="rule-set-title" title={name} subtitle={t('rulesArea.versionsSubtitle')} />
      <article className="card card--table">
        <VersionRows name={name} highlight={highlight} />
      </article>
    </section>
  );
}

function VersionRows({ name, highlight }: { name: string; highlight: number | undefined }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  const retry = useRetryRules();
  const versions = useVersions(name);
  if (versions.isPending) return <div className="card__pad"><LoadingRows /></div>;
  if (versions.isError) return <div className="card__pad"><AreaError error={versions.error} onRetry={retry} /></div>;
  const person = (version: object, login: string | null | undefined, role: PersonRole) =>
    login === null || login === undefined ? <span className="muted" aria-label={t('rulesArea.none')}>—</span> : personName(login, suppliedName(version, role));
  return (
    <table className="qtable">
      <thead>
        <tr>
          <th scope="col">{t('rulesArea.col.version')}</th>
          <th scope="col">{t('rulesArea.col.status')}</th>
          <th scope="col">{t('rulesArea.col.createdBy')}</th>
          <th scope="col">{t('rulesArea.col.requestedBy')}</th>
          <th scope="col">{t('rulesArea.col.approvedBy')}</th>
          <th scope="col">{t('rulesArea.col.changed')}</th>
        </tr>
      </thead>
      <tbody>
        {versions.data.map((version) => (
          <tr key={version.version} className={version.version === highlight ? 'is-selected' : undefined} aria-current={version.version === highlight ? 'true' : undefined}>
            <th scope="row" className="tnum">{t('rules.version', { version: String(version.version) })}</th>
            <td><StatusBadge status={version.status} /></td>
            <td>{person(version, version.createdBy, 'createdBy')}</td>
            <td>{person(version, version.requestedBy, 'requestedBy')}</td>
            <td>{person(version, version.approvedBy, 'approvedBy')}</td>
            <td className="tnum muted">{formatDateTime(version.approvedAt ?? version.requestedAt ?? version.createdAt, locale)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
