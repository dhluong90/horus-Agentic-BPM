import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { toHostError } from '../../api/hostError';
import type { RuleDocument } from '../../api/rules/rulesClient';
import type { RuleSetSummary } from '../../api/rules/schemas';
import type { Route } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import { useRulesSession } from './api/rulesContext';
import { useImportRuleSet, useRuleSets } from './api/useRules';
import { ProblemNotice } from './components/ProblemNotice';
import { StatusBadge } from './components/StatusBadge';
import { formatDateTime } from './model/dates';

async function readDocument(file: File): Promise<RuleDocument> {
  const text = await file.text();
  // JSON is valid YAML 1.2, and this engine release imports YAML only, so every file is sent as YAML.
  return { contentType: 'application/yaml', text };
}

function SummaryRow({ summary }: { summary: RuleSetSummary }) {
  const { t, locale } = useI18n();
  return (
    <tr>
      <th scope="row">
        <a className="rs-link mono hit" href={`#/rule-sets/${encodeURIComponent(summary.ruleSet)}`}>{summary.ruleSet}</a>
      </th>
      <td className="tnum">{t('ruleSets.versionShort', { version: String(summary.latestVersion) })}</td>
      <td><StatusBadge status={summary.latestStatus} /></td>
      <td className="tnum">
        {summary.publishedVersion !== null ? t('ruleSets.versionShort', { version: String(summary.publishedVersion) }) : <span className="cell-any" aria-hidden="true">—</span>}
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

export function RuleSetList({ navigate }: { navigate: (route: Route) => void }) {
  const { t } = useI18n();
  const { can } = useRulesSession();
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const fileInput = useRef<HTMLInputElement>(null);
  const importFile = useImportRuleSet((version) => {
    setNotice(t('ruleSets.imported', { version: String(version.version), name: version.ruleSet }));
    navigate({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' });
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
            <input ref={fileInput} type="file" accept=".yaml,.yml,.json" className="sr-only" tabIndex={-1} aria-hidden="true"
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = '';
                if (file) void readDocument(file).then((document) => { importFile.start(document); });
              }} />
            <button type="button" className="btn btn--primary" disabled={importFile.isPending} onClick={() => fileInput.current?.click()}>
              {importFile.isPending ? t('ruleSets.importing') : t('ruleSets.import')}
            </button>
          </div>
        ) : null}
      </div>
      {importFile.isError ? <ProblemNotice error={importFile.error} /> : null}
      <p className="sr-only" role="status" aria-live="polite">{notice ?? ''}</p>
      <div className="card list-card">
        <ListBody />
      </div>
    </section>
  );
}
