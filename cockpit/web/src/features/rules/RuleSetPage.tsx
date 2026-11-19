import { useEffect, useReducer, useRef, useState, type ReactNode } from 'react';
import { toHostError } from '../../api/hostError';
import type { RuleSetVersion, VersionSummary } from '../../api/rules/schemas';
import { useI18n } from '../../i18n/I18nProvider';
import { useRulesSession } from './api/rulesContext';
import { useApprove, useReject, useRequestPublish, useSaveDraft, useVersion, useVersions } from './api/useRules';
import { ActionDock } from './components/ActionDock';
import { ChangesView } from './components/ChangesView';
import { ImportFileButton } from './components/ImportFileButton';
import { DecisionTable } from './components/DecisionTable';
import { DraftEditor } from './components/DraftEditor';
import { ProblemNotice } from './components/ProblemNotice';
import { SourceView } from './components/SourceView';
import { StatusBadge } from './components/StatusBadge';
import { TryItPanel } from './components/TryItPanel';
import { draftReducer, initialDraft } from './model/draft';
import { LENSES, useRulesNavigation, type Lens, type RulesRoute } from './routes';
import { placeErrors } from './model/engineErrors';
import { sameRows, toDocument, toRows } from './model/table';

type Navigate = (route: RulesRoute) => void;

/** A confirmation from the engine, shown only on the version it is about. */
interface Notice {
  version: number;
  text: string;
}

/** The version the diff compares with: the newest earlier version that was published, else the one before. Versions arrive newest first. */
function baseOf(versions: readonly VersionSummary[], current: number): VersionSummary | undefined {
  const earlier = versions.filter((v) => v.version < current);
  return earlier.find((v) => v.status === 'PUBLISHED' || v.status === 'RETIRED') ?? earlier[0];
}

function Workbench({ version, latestVersion, base, lens, navigate, notice, setNotice }: {
  version: RuleSetVersion;
  latestVersion: number;
  base: VersionSummary | undefined;
  lens: Lens;
  navigate: Navigate;
  notice: Notice | undefined;
  setNotice: (notice: Notice) => void;
}) {
  const { t, locale } = useI18n();
  const [editing, setEditing] = useState(false);
  const [draft, dispatch] = useReducer(draftReducer, version.definition, (definition) => initialDraft(toRows(definition)));
  const [matched, setMatched] = useState<ReadonlySet<string>>(new Set());
  const say = (updated: RuleSetVersion, text: string) => { setNotice({ version: updated.version, text }); };
  const save = useSaveDraft(version.ruleSet, (saved) => {
    say(saved, t('editor.saved', { version: String(saved.version) }));
    navigate({ name: 'rule-set', ruleSet: saved.ruleSet, version: saved.version, lens: 'table' });
  });
  const requestPublish = useRequestPublish((updated) => { say(updated, t('dock.requested', { version: String(updated.version) })); });
  const approve = useApprove((updated) => { say(updated, t('dock.approved', { version: String(updated.version) })); });
  const reject = useReject((updated) => { say(updated, t('dock.rejected', { version: String(updated.version) })); });
  const dirty = !sameRows(draft.rows, toRows(version.definition));
  const errors = placeErrors(save.error ? toHostError(save.error) : undefined);
  const onlyLocated = errors.length > 0 && errors.every((e) => e.kind !== 'table');

  const reset = (next: boolean) => {
    dispatch({ type: 'reset', rows: toRows(version.definition) });
    save.reset();
    setEditing(next);
  };
  const saveDraft = () => {
    // JSON text is valid YAML 1.2; this engine release imports YAML only, so the document is sent as YAML.
    save.start({ contentType: 'application/yaml', text: JSON.stringify(toDocument(version.definition, draft.rows), null, 2) });
  };

  return (
    <div className="workbench">
      <div className="card workbench__main">
        {editing ? (
          <>
            <p className="editor-title">{t('editor.title', { version: String(version.version) })}</p>
            {save.error ? <ProblemNotice error={save.error} showDetail={locale !== 'en' || !onlyLocated} /> : null}
            <DraftEditor definition={version.definition} draft={draft} dispatch={dispatch} errors={errors} />
          </>
        ) : (
          <LensContent lens={lens} version={version} base={base} matched={matched} />
        )}
      </div>
      <aside className="workbench__side">
        <ActionDock
          version={version}
          latestVersion={latestVersion}
          editing={editing ? { dirty, saving: save.isPending, error: save.error, onSave: saveDraft, onCancel: () => { reset(false); } } : undefined}
          notice={notice?.version === version.version ? notice.text : undefined}
          onEdit={() => { reset(true); }}
          onOpen={(number) => { navigate({ name: 'rule-set', ruleSet: version.ruleSet, version: number, lens: undefined }); }}
          onReload={() => { window.location.reload(); }}
          requestPublish={{ start: () => { requestPublish.start(version); }, isPending: requestPublish.isPending, error: requestPublish.error }}
          approve={{ start: (comment) => { approve.start({ version, comment }); }, isPending: approve.isPending, error: approve.error }}
          reject={{ start: (reason) => { reject.start({ version, reason }); }, isPending: reject.isPending, error: reject.error }}
        />
        {!editing ? <TryItPanel version={version} onMatched={(ids) => { setMatched(new Set(ids)); }} /> : null}
      </aside>
    </div>
  );
}

/** The diff reads the base version in full only when the Changes view is open. */
function ChangesLens({ version, base }: { version: RuleSetVersion; base: VersionSummary | undefined }) {
  const { t } = useI18n();
  const baseVersion = useVersion(version.ruleSet, base?.version);
  if (base && baseVersion.isPending) return <p className="empty" role="status">{t('ruleSets.loading')}</p>;
  if (baseVersion.isError) return <ProblemNotice error={baseVersion.error} />;
  return <ChangesView definition={version.definition} base={baseVersion.data?.definition} baseVersion={base?.version} record={{ version: version.version, canonicalHash: version.canonicalHash }} />;
}

function LensContent({ lens, version, base, matched }: { lens: Lens; version: RuleSetVersion; base: VersionSummary | undefined; matched: ReadonlySet<string> }) {
  switch (lens) {
    case 'table':
      return <DecisionTable definition={version.definition} version={version.version} matched={matched} />;
    case 'changes':
      return <ChangesLens version={version} base={base} />;
    case 'source':
      return <SourceView version={version} />;
  }
}

export function RuleSetPage({ name, versionNumber, lens }: { name: string; versionNumber: number | undefined; lens: Lens | undefined }) {
  const { t } = useI18n();
  const { go: navigate, href } = useRulesNavigation();
  const { can } = useRulesSession();
  const versions = useVersions(name);
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  // Import of the next version from a file (CKP-030); it stays available when cockpit drafts are off (PD-075).
  const importVersion = useSaveDraft(name, (created) => {
    setNotice({ version: created.version, text: t('ruleSet.imported', { version: String(created.version) }) });
    navigate({ name: 'rule-set', ruleSet: created.ruleSet, version: created.version, lens: 'table' });
  });
  const latest = versions.data?.[0];
  const selected = versionNumber ?? latest?.version;
  const known = selected !== undefined && versions.data?.some((v) => v.version === selected) === true;
  const version = useVersion(name, known ? selected : undefined);
  // A link to a version newer than the cached list (someone else just saved it): read the list again, once.
  const refetchedFor = useRef<string | undefined>(undefined);
  const missing = versions.data !== undefined && selected !== undefined && !known;
  const { refetch } = versions;
  useEffect(() => {
    const wanted = `${name}/${String(selected)}`;
    if (missing && refetchedFor.current !== wanted) {
      refetchedFor.current = wanted;
      void refetch();
    }
  }, [missing, name, selected, refetch]);

  const back = (
    <a className="back-link hit" href={href({ name: 'list' })}>
      <span aria-hidden="true">←</span> {t('ruleSet.back')}
    </a>
  );
  const frame = (content: ReactNode) => <section className="workspace">{back}{content}</section>;
  if (versions.isPending || (missing && versions.isFetching)) return frame(<p className="empty" role="status">{t('ruleSets.loading')}</p>);
  if (versions.isError) return frame(<ProblemNotice error={versions.error} />);
  if (!latest || selected === undefined) return frame(<p className="empty" role="alert">{t('ruleSets.empty')}</p>);
  if (!known) return frame(<p className="empty" role="alert">{t('ruleSet.versionMissing', { version: String(selected) })}</p>);
  if (version.isPending) return frame(<p className="empty" role="status">{t('ruleSets.loading')}</p>);
  if (version.isError) return frame(<ProblemNotice error={version.error} />);
  const current = version.data;
  const activeLens = lens ?? (current.status === 'PENDING_APPROVAL' ? 'changes' : 'table');
  const go = (next: Partial<{ version: number; lens: Lens }>) => {
    navigate({ name: 'rule-set', ruleSet: name, version: next.version ?? current.version, lens: next.lens ?? (next.version !== undefined ? undefined : activeLens) });
  };

  return (
    <section className="workspace" aria-labelledby="rule-set-title">
      <div className="rs-head">
        {back}
        <div className="rs-head__line">
          <h1 id="rule-set-title" className="rs-head__title">
            {name} <span className="rs-head__version tnum">{t('ruleSet.versionLabel', { version: String(current.version) })}</span>
          </h1>
          <StatusBadge status={current.status} />
          <span className="rs-head__policy">{t('ruleSet.hitPolicy')}: {t(hitPolicyKey(current.hitPolicy))}</span>
        </div>
        <div className="rs-head__controls">
          <label className="inline-field">
            <span>{t('ruleSet.versions')}</span>
            <select className="field__input field__input--compact" value={current.version} onChange={(e) => { go({ version: Number(e.target.value) }); }}>
              {versions.data.map((v) => (
                <option key={v.version} value={v.version}>{`${t('ruleSets.versionShort', { version: String(v.version) })} · ${t(`status.${v.status}`)}`}</option>
              ))}
            </select>
          </label>
          <div className="seg" role="group" aria-label={t('lens.label')}>
            {LENSES.map((item) => (
              <button key={item} type="button" aria-pressed={item === activeLens} className="hit" onClick={() => { go({ lens: item }); }}>{t(`lens.${item}`)}</button>
            ))}
          </div>
          {can('rules:author') ? (
            <span className="rs-head__import">
              <ImportFileButton label={t('ruleSet.import')} busyLabel={t('ruleSets.importing')} busy={importVersion.isPending} primary={false} onDocument={(document) => { setNotice(undefined); importVersion.start(document); }} />
            </span>
          ) : null}
        </div>
        {importVersion.isError ? <ProblemNotice error={importVersion.error} located /> : null}
      </div>
      <Workbench
        key={`${name}-${String(current.version)}-${String(current.lockVersion)}`}
        version={current}
        latestVersion={latest.version}
        base={baseOf(versions.data, current.version)}
        lens={activeLens}
        navigate={navigate}
        notice={notice}
        setNotice={setNotice}
      />
    </section>
  );
}

function hitPolicyKey(policy: string): 'hitPolicy.FIRST' | 'hitPolicy.UNIQUE' | 'hitPolicy.COLLECT' {
  const upper = policy.toUpperCase();
  return upper === 'UNIQUE' ? 'hitPolicy.UNIQUE' : upper === 'COLLECT' ? 'hitPolicy.COLLECT' : 'hitPolicy.FIRST';
}
