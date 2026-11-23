import { useState } from 'react';
import type { RuleSetSummary, VersionStatus } from '../../api/rules/schemas';
import { formatRoute, type Route } from '../../app/route';
import { PageHead } from '../../components/PageParts';
import { RecentCard } from '../../components/RecentCard';
import { TintTiles } from '../../components/TintTiles';
import { useI18n } from '../../i18n/I18nProvider';
import { formatDateTime } from './model/dates';
import { mapLoadable, useEveryRuleSet, useRuleSetsCut } from '../overview/useRuleOverview';
import { useRulesSession } from './api/rulesContext';
import { useImportRuleSet } from './api/useRules';
import { ImportFileButton } from './components/ImportFileButton';
import { NewRuleSetDialog } from './components/NewRuleSetDialog';
import { ProblemNotice } from './components/ProblemNotice';
import { columnLabel } from './model/reading';
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
  const { t, locale } = useI18n();
  const { can, templates, cockpitDrafts } = useRulesSession();
  const ruleSets = useEveryRuleSet(pageLimit);
  const cut = useRuleSetsCut(pageLimit);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [pickedFile, setPickedFile] = useState<string | undefined>(undefined);
  const [creating, setCreating] = useState(false);
  const importFile = useImportRuleSet((version) => {
    window.location.hash = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' });
  });
  // Import from a file stays available when the host turns cockpit drafts off (PD-075, CKP-032).
  const mayImport = can('rules:author');
  // A new rule set from a template needs the host's templates and the right to author; it opens in the grid when the host allows cockpit drafts.
  const mayCreate = mayImport && templates;
  const counts = (items: readonly RuleSetSummary[]) => (status: Filter) => (status === 'all' ? items.length : items.filter((item) => item.latestStatus === status).length);
  const shown = mapLoadable(ruleSets, (items) => items.filter((item) => (filter === 'all' || item.latestStatus === filter) && `${item.ruleSet} ${item.displayName.toLowerCase()}`.includes(query.trim().toLowerCase())));
  const count = ruleSets.status === 'ready' ? counts(ruleSets.data) : undefined;
  return (
    <section className="workspace" aria-labelledby="rules-title">
      <PageHead
        id="rules-title"
        title={t('rulesArea.title')}
        subtitle={t('rulesArea.subtitle')}
        action={mayImport ? (
          <>
            <ImportFileButton label={t('rulesArea.import')} busyLabel={t('rulesArea.importing')} busy={importFile.isPending} primary={!mayCreate} onDocument={(document, fileName) => { setPickedFile(fileName); importFile.start(document); }} />
            {mayCreate ? <button type="button" className="btn btn--primary" onClick={() => { setCreating(true); }}><span aria-hidden="true">＋</span> {t('rulesArea.new')}</button> : null}
          </>
        ) : undefined}
      />
      {creating ? (
        <NewRuleSetDialog onClose={() => { setCreating(false); }}
          onCreated={(version) => {
            window.location.hash = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: cockpitDrafts ? 'edit' : 'table' });
          }} />
      ) : null}
      {importFile.isError ? (
        <div className="page-alert">
          <ProblemNotice error={importFile.error} located refusedFile={pickedFile !== undefined ? { fileName: pickedFile, columnLabel: (name) => columnLabel(name, undefined) } : undefined} />
        </div>
      ) : null}
      {count !== undefined ? (
        <TintTiles label={t('list.tiles')} tiles={[
          { key: 'live', value: String(count('PUBLISHED')), label: t('rulesArea.tile.live'), tint: 'lime' },
          { key: 'pending', value: String(count('PENDING_APPROVAL')), label: t('rulesArea.tile.pending'), tint: 'amber' },
          { key: 'draft', value: String(count('DRAFT')), label: t('rulesArea.tile.draft'), tint: 'mint' },
        ]} />
      ) : null}
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
      <div className="listing">
        <article className="card card--table dense">
          <RuleSetTable dense ruleSets={shown} emptyText={ruleSets.status === 'ready' && ruleSets.data.length > 0 ? t('rulesArea.noMatch') : t('rulesArea.empty')} />
        </article>
        {ruleSets.status === 'ready' && ruleSets.data.length > 0 ? (
          <RecentCard title={t('rulesArea.recent.title')} empty={t('list.recent.none')}
            items={[...ruleSets.data].sort((a, b) => Date.parse(b.lastChangedAt) - Date.parse(a.lastChangedAt)).slice(0, 4).map((item) => ({
              key: item.ruleSet, name: item.displayName,
              status: t(`status.${item.latestStatus}`), when: formatDateTime(item.lastChangedAt, locale) ?? '',
            }))} />
        ) : null}
      </div>
    </section>
  );
}
