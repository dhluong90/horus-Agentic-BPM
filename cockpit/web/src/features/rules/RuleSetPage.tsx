import { useEffect, useReducer, useRef, useState } from 'react';
import { toHostError } from '../../api/hostError';
import type { RuleSetVersion } from '../../api/rules/schemas';
import { LENSES, type Lens, type Route } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import { useApprove, useRequestPublish, useSaveDraft, useVersions } from './api/useRules';
import { ActionDock } from './components/ActionDock';
import { ChangesView } from './components/ChangesView';
import { DecisionTable } from './components/DecisionTable';
import { DraftEditor } from './components/DraftEditor';
import { ProblemNotice } from './components/ProblemNotice';
import { SourceView } from './components/SourceView';
import { StatusBadge } from './components/StatusBadge';
import { TryItPanel } from './components/TryItPanel';
import { draftReducer, initialDraft } from './model/draft';
import { placeError } from './model/engineErrors';
import { sameRows, toDocument, toRows } from './model/table';

type Navigate = (route: Route) => void;

/** A confirmation from the engine, shown only on the version it is about. */
interface Notice {
  version: number;
  text: string;
}

/** The version the diff compares with: the newest earlier version that was published, else the one before. */
function baseOf(versions: readonly RuleSetVersion[], current: RuleSetVersion): RuleSetVersion | undefined {
  const earlier = versions.filter((v) => v.version < current.version);
  return [...earlier].reverse().find((v) => v.status === 'PUBLISHED' || v.status === 'RETIRED') ?? earlier.at(-1);
}

function Workbench({ versions, version, lens, navigate, notice, setNotice }: {
  versions: RuleSetVersion[];
  version: RuleSetVersion;
  lens: Lens;
  navigate: Navigate;
  notice: Notice | undefined;
  setNotice: (notice: Notice) => void;
}) {
  const { t, locale } = useI18n();
  const [editing, setEditing] = useState(false);
  const [draft, dispatch] = useReducer(draftReducer, version.definition, (definition) => initialDraft(toRows(definition)));
  const [matched, setMatched] = useState<ReadonlySet<string>>(new Set());
  const open = (target: RuleSetVersion, nextLens?: Lens) => {
    navigate({ name: 'rule-set', ruleSet: target.ruleSet, version: target.version, lens: nextLens });
  };
  const save = useSaveDraft(version.ruleSet, (saved) => {
    setNotice({ version: saved.version, text: t('editor.saved', { version: String(saved.version) }) });
    open(saved, 'table');
  });
  const requestPublish = useRequestPublish((updated) => { setNotice({ version: updated.version, text: t('dock.requested', { version: String(updated.version) }) }); });
  const approve = useApprove((updated) => { setNotice({ version: updated.version, text: t('dock.approved', { version: String(updated.version) }) }); });
  const latest = versions.at(-1) ?? version;
  const base = baseOf(versions, version);
  const dirty = !sameRows(draft.rows, toRows(version.definition));
  const saveError = save.error ? toHostError(save.error) : undefined;
  const errorPlace = saveError?.kind === 'problem' && saveError.code === 'VALIDATION_FAILED' ? placeError(saveError.detail) : undefined;

  const startEdit = () => {
    dispatch({ type: 'reset', rows: toRows(version.definition) });
    save.reset();
    setEditing(true);
  };
  const cancelEdit = () => {
    dispatch({ type: 'reset', rows: toRows(version.definition) });
    save.reset();
    setEditing(false);
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
            {save.error ? <ProblemNotice error={save.error} showDetail={locale !== 'en' || errorPlace === undefined || errorPlace.kind === 'table'} /> : null}
            <DraftEditor definition={version.definition} draft={draft} dispatch={dispatch} error={errorPlace} />
          </>
        ) : (
          <LensContent lens={lens} version={version} base={base} matched={matched} />
        )}
      </div>
      <aside className="workbench__side">
        <ActionDock
          version={version}
          latestVersion={latest.version}
          editing={editing ? { dirty, saving: save.isPending, error: save.error, onSave: saveDraft, onCancel: cancelEdit } : undefined}
          notice={notice?.version === version.version ? notice.text : undefined}
          onEdit={startEdit}
          onOpen={(number) => { navigate({ name: 'rule-set', ruleSet: version.ruleSet, version: number, lens: undefined }); }}
          onReload={() => { window.location.reload(); }}
          requestPublish={{ start: () => { requestPublish.start(version); }, isPending: requestPublish.isPending, error: requestPublish.error }}
          approve={{ start: (comment) => { approve.start({ version, comment }); }, isPending: approve.isPending, error: approve.error }}
        />
        {!editing ? <TryItPanel version={version} onMatched={(ids) => { setMatched(new Set(ids)); }} /> : null}
      </aside>
    </div>
  );
}

function LensContent({ lens, version, base, matched }: { lens: Lens; version: RuleSetVersion; base: RuleSetVersion | undefined; matched: ReadonlySet<string> }) {
  switch (lens) {
    case 'table':
      return <DecisionTable definition={version.definition} version={version.version} matched={matched} />;
    case 'changes':
      return <ChangesView definition={version.definition} base={base?.definition} baseVersion={base?.version} />;
    case 'source':
      return <SourceView version={version} />;
  }
}

export function RuleSetPage({ name, versionNumber, lens, navigate }: { name: string; versionNumber: number | undefined; lens: Lens | undefined; navigate: Navigate }) {
  const { t } = useI18n();
  const query = useVersions(name);
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  // A link to a version newer than the cached list (someone else just saved it): read the list again, once.
  const refetchedFor = useRef<string | undefined>(undefined);
  const missing = versionNumber !== undefined && query.data !== undefined && !query.data.some((v) => v.version === versionNumber);
  const { refetch } = query;
  useEffect(() => {
    const wanted = `${name}/${String(versionNumber)}`;
    if (missing && refetchedFor.current !== wanted) {
      refetchedFor.current = wanted;
      void refetch();
    }
  }, [missing, name, versionNumber, refetch]);
  const back = (
    <a className="back-link hit" href="#/rule-sets">
      <span aria-hidden="true">←</span> {t('ruleSet.back')}
    </a>
  );
  if (query.isPending || (missing && query.isFetching)) return <section className="workspace">{back}<p className="empty" role="status">{t('ruleSets.loading')}</p></section>;
  if (query.isError) return <section className="workspace">{back}<ProblemNotice error={query.error} /></section>;
  const versions = query.data;
  const latest = versions.at(-1);
  if (!latest) return <section className="workspace">{back}<p className="empty" role="alert">{t('ruleSets.notFound', { name })}</p></section>;
  const version = versionNumber === undefined ? latest : versions.find((v) => v.version === versionNumber);
  if (!version) return <section className="workspace">{back}<p className="empty" role="alert">{t('ruleSet.versionMissing', { version: String(versionNumber) })}</p></section>;
  const activeLens = lens ?? (version.status === 'PENDING_APPROVAL' ? 'changes' : 'table');
  const go = (next: Partial<{ version: number; lens: Lens }>) => {
    navigate({ name: 'rule-set', ruleSet: name, version: next.version ?? version.version, lens: next.lens ?? (next.version !== undefined ? undefined : activeLens) });
  };

  return (
    <section className="workspace" aria-labelledby="rule-set-title">
      <div className="rs-head">
        {back}
        <div className="rs-head__line">
          <h1 id="rule-set-title" className="rs-head__title">
            {name} <span className="rs-head__version tnum">{t('ruleSet.versionLabel', { version: String(version.version) })}</span>
          </h1>
          <StatusBadge status={version.status} />
          <span className="rs-head__policy">{t('ruleSet.hitPolicy')}: {t(hitPolicyKey(version.hitPolicy))}</span>
        </div>
        <div className="rs-head__controls">
          <label className="inline-field">
            <span>{t('ruleSet.versions')}</span>
            <select className="field__input field__input--compact" value={version.version} onChange={(e) => { go({ version: Number(e.target.value) }); }}>
              {[...versions].reverse().map((v) => (
                <option key={v.version} value={v.version}>{`${t('ruleSets.versionShort', { version: String(v.version) })} · ${t(`status.${v.status}`)}`}</option>
              ))}
            </select>
          </label>
          <div className="seg" role="group" aria-label={t('lens.label')}>
            {LENSES.map((item) => (
              <button key={item} type="button" aria-pressed={item === activeLens} className="hit" onClick={() => { go({ lens: item }); }}>{t(`lens.${item}`)}</button>
            ))}
          </div>
        </div>
      </div>
      <Workbench key={`${name}-${version.version}`} versions={versions} version={version} lens={activeLens} navigate={navigate} notice={notice} setNotice={setNotice} />
    </section>
  );
}

function hitPolicyKey(policy: string): 'hitPolicy.FIRST' | 'hitPolicy.UNIQUE' | 'hitPolicy.COLLECT' {
  const upper = policy.toUpperCase();
  return upper === 'UNIQUE' ? 'hitPolicy.UNIQUE' : upper === 'COLLECT' ? 'hitPolicy.COLLECT' : 'hitPolicy.FIRST';
}
