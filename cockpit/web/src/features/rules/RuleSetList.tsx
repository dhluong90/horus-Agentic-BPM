import { useId, useRef, useState, type FormEvent } from 'react';
import { isRuleSetName, knownRuleSets, rememberRuleSet, type RuleSetSummary } from '../../api/rules/catalogue';
import type { RuleDocument } from '../../api/rules/rulesClient';
import type { Route } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import { useRulesSession } from './api/rulesContext';
import { useImportRuleSet, useSummaries } from './api/useRules';
import { ProblemNotice } from './components/ProblemNotice';
import { StatusBadge } from './components/StatusBadge';
import { formatDateTime } from './model/dates';

async function readDocument(file: File): Promise<RuleDocument> {
  const text = await file.text();
  // JSON is valid YAML 1.2, and this engine release imports YAML only, so every file is sent as YAML.
  return { contentType: 'application/yaml', text };
}

function OpenByName({ onOpen }: { onOpen: (name: string) => void }) {
  const { t } = useI18n();
  const id = useId();
  const [name, setName] = useState('');
  const [invalid, setInvalid] = useState(false);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = name.trim();
    if (!isRuleSetName(value)) {
      setInvalid(true);
      return;
    }
    onOpen(value);
  };
  return (
    <form className="open-form" onSubmit={submit} noValidate>
      <label htmlFor={id} className="field__label">{t('ruleSets.open.label')}</label>
      <div className="open-form__row">
        <input id={id} className="field__input mono" value={name} placeholder="card-fee" aria-invalid={invalid} aria-describedby={invalid ? `${id}-err` : undefined}
          onChange={(e) => { setName(e.target.value); setInvalid(false); }} />
        <button type="submit" className="btn">{t('ruleSets.open.action')}</button>
      </div>
      {invalid ? <span id={`${id}-err`} className="cell-error">{t('ruleSets.open.invalid')}</span> : null}
    </form>
  );
}

function SummaryRow({ name, summary, loaded, onOpen }: { name: string; summary: RuleSetSummary | undefined; loaded: boolean; onOpen: () => void }) {
  const { t, locale } = useI18n();
  return (
    <tr>
      <th scope="row">
        <a className="rs-link mono hit" href={`#/rule-sets/${encodeURIComponent(name)}`} onClick={onOpen}>{name}</a>
      </th>
      <td className="tnum">{summary ? t('ruleSets.versionShort', { version: String(summary.latest.version) }) : loaded ? t('ruleSets.missing') : '…'}</td>
      <td>{summary ? <StatusBadge status={summary.latest.status} /> : null}</td>
      <td className="tnum">{summary?.published ? t('ruleSets.versionShort', { version: String(summary.published.version) }) : <span className="cell-any" aria-hidden="true">—</span>}</td>
      <td className="tnum">{summary ? formatDateTime(summary.latest.createdAt, locale) : null}</td>
    </tr>
  );
}

export function RuleSetList({ navigate }: { navigate: (route: Route) => void }) {
  const { t } = useI18n();
  const { hostId, tenant, userId, can } = useRulesSession();
  const shelf = { hostId, tenant, userId };
  const [names, setNames] = useState(() => knownRuleSets(shelf));
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const fileInput = useRef<HTMLInputElement>(null);
  const summaries = useSummaries(names);
  const importFile = useImportRuleSet((version) => {
    setNotice(t('ruleSets.imported', { version: String(version.version), name: version.ruleSet }));
    navigate({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' });
  });
  const open = (name: string) => {
    rememberRuleSet(shelf, name);
    setNames(knownRuleSets(shelf));
    navigate({ name: 'rule-set', ruleSet: name, version: undefined, lens: undefined });
  };

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
      <div className="list-layout">
        <div className="card list-layout__main">
          {names.length === 0 ? (
            <p className="empty">{t('ruleSets.empty')}</p>
          ) : (
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
                {names.map((name, index) => (
                  <SummaryRow key={name} name={name} summary={summaries[index]?.data} loaded={summaries[index]?.isSuccess === true} onOpen={() => { rememberRuleSet(shelf, name); }} />
                ))}
              </tbody>
            </table>
          )}
        </div>
        <aside className="card list-layout__side">
          <OpenByName onOpen={open} />
        </aside>
      </div>
    </section>
  );
}
