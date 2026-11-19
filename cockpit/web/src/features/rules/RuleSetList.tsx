import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toHostError } from '../../api/hostError';
import type { RuleSetSummary } from '../../api/rules/schemas';
import { useI18n } from '../../i18n/I18nProvider';
import { useRulesSession } from './api/rulesContext';
import { useImportRuleSet, useRuleSets } from './api/useRules';
import { ImportFileButton } from './components/ImportFileButton';
import { ProblemNotice } from './components/ProblemNotice';
import { StatusBadge } from './components/StatusBadge';
import { formatDateTime } from './model/dates';
import { useRulesNavigation } from './routes';


function SummaryRow({ summary }: { summary: RuleSetSummary }) {
  const { t, locale } = useI18n();
  const { href } = useRulesNavigation();
  return (
    <tr>
      <th scope="row">
        <a className="rs-link hit" href={href({ name: 'rule-set', ruleSet: summary.ruleSet, version: undefined, lens: undefined })}>{summary.ruleSet}</a>
      </th>
      <td className="tnum">{t('list.version', { version: String(summary.latestVersion) })}</td>
      <td><StatusBadge status={summary.latestStatus} /></td>
      <td className="tnum">
        {summary.publishedVersion !== null ? t('list.version', { version: String(summary.publishedVersion) }) : <span className="cell-any" aria-hidden="true">—</span>}
      </td>
      <td className="tnum">{formatDateTime(summary.lastChangedAt, locale)}</td>
    </tr>
  );
}

function ListBody() {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const { hostId, tenant } = useRulesSession();
  const list = useRuleSets();
  if (list.isPending) return <p className="empty" role="status">{t('ruleSets.loading')}</p>;
  if (list.isError) {
    const error = toHostError(list.error);
    // A cursor the engine no longer accepts: start the list again from the first page.
    const restart = error.kind === 'problem' && error.code === 'QUERY_INVALID'
      ? () => { void queryClient.resetQueries({ queryKey: [hostId, tenant, 'rules', 'list'] }); }
      : undefined;
    return <ProblemNotice error={list.error} {...(restart ? { onRestart: restart } : {})} />;
  }
  const items = list.data.pages.flatMap((page) => page.data);
  if (items.length === 0) return <p className="empty">{t('ruleSets.empty')}</p>;
  return (
    <>
      <table className="dt dt--list">
        <thead>
          <tr>
            <th scope="col">{t('ruleSets.col.name')}</th>
            <th scope="col">{t('ruleSets.col.latest')}</th>
            <th scope="col">{t('ruleSets.col.status')}</th>
            <th scope="col">{t('ruleSets.col.published')}</th>
            <th scope="col">{t('ruleSets.col.changed')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((summary) => <SummaryRow key={summary.ruleSet} summary={summary} />)}
        </tbody>
      </table>
      {list.hasNextPage ? (
        <div className="list-more">
          <button type="button" className="btn btn--quiet" disabled={list.isFetchingNextPage} onClick={() => { void list.fetchNextPage(); }}>
            {list.isFetchingNextPage ? t('ruleSets.loading') : t('ruleSets.more')}
          </button>
        </div>
      ) : null}
    </>
  );
}

export function RuleSetList() {
  const { go } = useRulesNavigation();
  const { t } = useI18n();
  const { can } = useRulesSession();
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const importFile = useImportRuleSet((version) => {
    setNotice(t('ruleSets.imported', { version: String(version.version), name: version.ruleSet }));
    go({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' });
  });

  return (
    <section className="workspace" aria-labelledby="rule-sets-title">
      <div className="page-head page-head--row">
        <div>
          <h1 id="rule-sets-title">{t('ruleSets.title')}</h1>
          <p className="page-head__sub">{t('ruleSets.subtitle')}</p>
        </div>
        {can('rules:author') ? (
          <div className="page-head__actions">
            <ImportFileButton label={t('ruleSets.import')} busyLabel={t('ruleSets.importing')} busy={importFile.isPending} primary onDocument={(document) => { setNotice(undefined); importFile.start(document); }} />
          </div>
        ) : null}
      </div>
      {importFile.isError ? <ProblemNotice error={importFile.error} located /> : null}
      <p className="sr-only" role="status" aria-live="polite">{notice ?? ''}</p>
      <div className="card list-card">
        <ListBody />
      </div>
    </section>
  );
}
