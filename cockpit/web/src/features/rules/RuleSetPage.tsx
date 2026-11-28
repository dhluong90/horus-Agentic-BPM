import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { setCrumbLabel, setCrumbRuleSetName } from '../../app/crumbs';
import { nameOf } from '../../app/names';
import { CopyButton } from '../../components/CopyButton';
import { isUnclassified, type RuleSetVersion, type VersionSummary } from '../../api/rules/schemas';
import { UnclassifiedNote } from '../../components/UnclassifiedNote';
import { useI18n } from '../../i18n/I18nProvider';
import { useNames, useWantNames } from '../processes/api/displayNames';
import { useRulesSession } from './api/rulesContext';
import { useApprove, useCopyVersion, useExportVersion, useReject, useRequestPublish, useSaveDraft, useVersion, useVersionDiff, useVersions } from './api/useRules';
import { saveFile } from '../../app/saveFile';
import { DecisionCard, DetailsPanel, DraftsOffNote, editBlock, HeaderActions, showsEdit, type Review } from './components/ActionDock';
import { ChangesView, type SeenVersion } from './components/ChangesView';
import { DecisionTable } from './components/DecisionTable';
import { DraftWorkspace } from './components/DraftWorkspace';
import { EvidenceExport } from './components/EvidenceExport';
import { RuleSetHistoryView } from './components/RuleSetHistoryView';
import { useFilePicker } from './components/ImportFileButton';
import { ImportSpreadsheetDialog } from './components/ImportSpreadsheetDialog';
import type { PickedSheet } from './api/useRules';
import { refusedWith } from './model/csvRefusals';
import { MenuButton } from './components/MenuButton';
import { PersonalDataWarnings } from './components/PersonalDataWarnings';
import { ProblemNotice } from './components/ProblemNotice';
import { SourceView } from './components/SourceView';
import { StatusBadge } from './components/StatusBadge';
import { Tabs } from './components/Tabs';
import { Toast } from './components/Toast';
import { TryItPanel } from './components/TryItPanel';
import { ReadingsView } from './components/ReadingsView';
import { ReadingsDiffView } from './components/ReadingsDiffView';
import { ReadingStyleSwitch } from './components/ReadingParts';
import { SimulatePanel } from './components/SimulatePanel';
import { SimulationScreen } from './components/SimulationScreen';
import { ReplayScreen } from './components/ReplayScreen';
import { ReportView } from './components/ReportView';
import { formatDateTime } from './model/dates';
import { columnLabel } from './model/reading';
import { formatRoute, type Lens, type Route } from '../../app/route';
import type { RuleDocument } from '../../api/rules/rulesClient';

type Navigate = (route: Route) => void;

const navigate: Navigate = (route) => { window.location.hash = formatRoute(route); };

/** The version the diff compares with: the newest earlier version that was published, else the one before. Versions arrive newest first. */
function baseOf(versions: readonly VersionSummary[], current: number): VersionSummary | undefined {
  const earlier = versions.filter((v) => v.version < current);
  return earlier.find((v) => v.status === 'PUBLISHED' || v.status === 'RETIRED') ?? earlier[0];
}

function policyKey(policy: string): 'policy.FIRST' | 'policy.UNIQUE' | 'policy.COLLECT' {
  const upper = policy.toUpperCase();
  return upper === 'UNIQUE' ? 'policy.UNIQUE' : upper === 'COLLECT' ? 'policy.COLLECT' : 'policy.FIRST';
}

/** One line under the name: the version and what state it is in, in words. */
function Subtitle({ version, editing }: { version: RuleSetVersion; editing: boolean }) {
  const { t, locale } = useI18n();
  const { user } = useNames();
  useWantNames({ users: version.requestedBy ? [version.requestedBy] : [] });
  const n = String(version.version);
  let text: string;
  if (editing) text = t('editor.editing', { version: n });
  else if (version.status === 'PUBLISHED') text = t('head.inEffect', { version: n, policy: t(policyKey(version.hitPolicy)) });
  else if (version.status === 'PENDING_APPROVAL') text = t('head.pending', { version: n, who: version.requestedBy ? user(version.requestedBy) : '', when: formatDateTime(version.requestedAt, locale) ?? '' });
  else if (version.status === 'DRAFT') text = t('head.draft', { version: n });
  else text = t('head.retired', { version: n });
  return <p className="rs-head__sub">{text}</p>;
}

function Inspector({ version, importedFrom, onMatched }: { version: RuleSetVersion; importedFrom: string | undefined; onMatched: (ids: string[]) => void }) {
  const { t } = useI18n();
  const [tab, setTab] = useState<'details' | 'try' | 'simulate'>('details');
  // A draft has its own Simulate view with several samples; the inspector keeps the one-sample check for the others.
  const items = version.status === 'DRAFT'
    ? [{ id: 'details', label: t('inspector.details') }, { id: 'try', label: t('inspector.tryIt') }] as const
    : [{ id: 'details', label: t('inspector.details') }, { id: 'try', label: t('inspector.tryIt') }, { id: 'simulate', label: t('inspector.simulate') }] as const;
  let panel: ReactNode;
  if (tab === 'details') panel = <DetailsPanel version={version} importedFrom={importedFrom} />;
  else if (tab === 'try') panel = <TryItPanel version={version} onMatched={onMatched} />;
  else panel = <SimulatePanel version={version} />;
  return (
    <section className="card inspector" aria-label={t('inspector.label')}>
      <Tabs label={t('inspector.label')} items={items} selected={tab} onSelect={setTab}>
        {panel}
      </Tabs>
    </section>
  );
}

/**
 * The approver's comparison. The engine computes the diff (RLE-028); both versions are read in full so every
 * row shows beside its counterpart. Nothing is fetched until the Changes view is open.
 */
function ChangesLens({ version, base, seen }: { version: RuleSetVersion; base: VersionSummary | undefined; seen: SeenVersion | undefined }) {
  const { t } = useI18n();
  const [view, setView] = useState<'table' | 'readings'>('table');
  const baseVersion = useVersion(version.ruleSet, base?.version);
  const diff = useVersionDiff(version, view === 'table' ? base?.version : undefined);
  if (!base) {
    return (
      <div className="changes">
        <p className="changes__summary"><strong>{t('changes.first')}</strong></p>
        <DecisionTable definition={version.definition} version={version.version} matched={new Set()} />
      </div>
    );
  }
  // Table or readings: one view of the change at a time.
  const switcher = (
    <div className="changes__switch">
      <div className="seg" role="group" aria-label={t('changes.view')}>
        {(['table', 'readings'] as const).map((option) => (
          <button key={option} type="button" className="hit" aria-pressed={view === option} onClick={() => { setView(option); }}>
            {t(option === 'table' ? 'changes.view.table' : 'changes.view.readings')}
          </button>
        ))}
      </div>
      {view === 'readings' ? <ReadingStyleSwitch /> : null}
    </div>
  );
  let body: ReactNode;
  if (baseVersion.isError) body = <ProblemNotice error={baseVersion.error} />;
  else if (view === 'table' && diff.isError) body = <ProblemNotice error={diff.error} />;
  else if (baseVersion.isPending || (view === 'table' && diff.isPending)) body = <p className="empty" role="status">{t('ruleSets.loading')}</p>;
  else if (view === 'readings') body = <div className="changes readings"><ReadingsDiffView base={baseVersion.data} next={version} onCompareTable={() => { setView('table'); }} /></div>;
  else if (diff.data !== undefined) body = <ChangesView diff={diff.data} base={baseVersion.data} next={version} seen={seen} />;
  return <>{switcher}{body}</>;
}

function LensContent({ lens, version, base, matched, onOpenTable, onEdit, seen }: { lens: Lens; version: RuleSetVersion; base: VersionSummary | undefined; matched: ReadonlySet<string>; onOpenTable: () => void; onEdit: () => void; seen: SeenVersion | undefined }) {
  switch (lens) {
    case 'table':
    case 'edit':
      return <DecisionTable definition={version.definition} version={version.version} matched={matched} />;
    case 'readings':
      return <ReadingsView version={version} matched={matched} onOpenTable={onOpenTable} />;
    case 'changes':
      return <ChangesLens version={version} base={base} seen={seen} />;
    case 'history':
      return <RuleSetHistoryView ruleSet={version.ruleSet} openVersion={version.version} />;
    case 'source':
      return <SourceView version={version} />;
    case 'simulate':
      return <SimulationScreen version={version} onEdit={onEdit} />;
    case 'report':
      return <ReportView version={version} />;
    case 'replay':
      return <ReplayScreen version={version} />;
  }
}

interface WorkbenchProps {
  version: RuleSetVersion;
  versions: readonly VersionSummary[];
  lens: Lens;
  navigate: Navigate;
  /** A success message; `sticky` keeps it until dismissed and moves the focus to the title, for a saved draft. */
  say: (text: string, sticky?: boolean) => void;
  importVersion: { start: (document: RuleDocument, fileName: string) => void; isPending: boolean; error: unknown; fileName: string | undefined };
  /** A spreadsheet was picked: the page asks to confirm before importing it as the next draft. */
  onSheet: (sheet: PickedSheet) => void;
  /** The file each version was imported from on this page, by version number. */
  importedFrom: Readonly<Record<number, string>>;
  /** The version whose title takes the focus when it opens: after an import, so the import button no longer holds it. */
  focusTitle: number | undefined;
  onTitleFocused: () => void;
  /** The content hash of this version when the user opened it; a different current hash means it changed under them. */
  openedHash: string;
  onReviewAgain: () => void;
  /** The version as the approver first had it on screen, to mark what changed since. */
  seen: SeenVersion | undefined;
  /** The engine refused the last decision with 409 VERSION_CONFLICT and the version was read again. */
  conflicted: boolean;
  onConflict: () => void;
  /** Read the version and its list again after the engine said the content moved on; the new content is then the one reviewed. */
  onReloadChanges: () => Promise<void>;
}

function Workbench({ version, versions, lens, navigate, say, importVersion, onSheet, importedFrom, focusTitle, onTitleFocused, openedHash, onReviewAgain, onReloadChanges, seen, conflicted, onConflict }: WorkbenchProps) {
  useEffect(() => {
    // The editor closes on save before the table opens: the title of the table takes the focus, not the editor's.
    if (focusTitle !== version.version || lens === 'edit') return;
    // After the frame has moved the focus to the main region for the new route, so the title keeps it.
    const timer = window.setTimeout(() => {
      window.document.getElementById('rule-set-title')?.focus();
      onTitleFocused();
    }, 0);
    return () => { window.clearTimeout(timer); };
  }, [focusTitle, onTitleFocused, version.version, lens]);
  const { t } = useI18n();
  const session = useRulesSession();
  const { can } = session;
  // The grid edits a stored draft only, and only for the user the engine lets replace it (its author).
  const block = editBlock(version, session);
  const editing = lens === 'edit' && block === undefined;
  const [matched, setMatched] = useState<ReadonlySet<string>>(new Set());
  const newDraft = useCopyVersion(version.ruleSet, (created) => {
    say(t('editor.createdFrom', { version: String(created.version), from: String(version.version) }));
    navigate({ name: 'rule-set', ruleSet: created.ruleSet, version: created.version, lens: 'edit' });
  });
  const requestPublish = useRequestPublish((updated) => {
    say(t('dock.requested', { version: String(updated.version) }));
    // Simulate and Replay run on drafts only: the request moves the author to the table, never to an empty view.
    if (lens === 'simulate' || lens === 'replay') navigate({ name: 'rule-set', ruleSet: updated.ruleSet, version: updated.version, lens: 'table' });
  });
  const approve = useApprove((updated) => { say(t('dock.approved', { version: String(updated.version) })); }, onConflict);

  const reject = useReject((updated) => { say(t('dock.rejected', { version: String(updated.version) })); }, onConflict);
  // Export hands the engine's bytes to the browser unchanged, so the file imports back with the same hash (RLE-032).
  const exportFile = useExportVersion((exported) => {
    saveFile(exported.file, exported.fileName);
    say(t('export.done', { file: exported.fileName }));
  });
  const picker = useFilePicker((document, fileName) => { exportFile.reset(); importVersion.start(document, fileName); }, onSheet);
  const label = (name: string) => {
    const column = [...version.definition.inputs, ...version.definition.outputs].find((c) => c.name === name);
    return columnLabel(name, column?.description);
  };
  // Reminders about unclassified inputs are a calm note, apart from the personal-data warnings an approver accepts.
  const unclassified = [...new Set(version.warnings.filter(isUnclassified).map((warning) => label(warning.input)))];
  const personalData = version.warnings.filter((warning) => !isUnclassified(warning));
  const base = baseOf(versions, version.version);
  const go = (next: Partial<{ version: number; lens: Lens }>) => {
    navigate({ name: 'rule-set', ruleSet: version.ruleSet, version: next.version ?? version.version, lens: next.lens ?? (next.version !== undefined ? undefined : lens) });
  };
  const drafting = version.status === 'DRAFT';
  // Simulation runs on drafts only: on another version no tab is active, as the body is not any of them.
  const simulateOnOther = lens === 'simulate' && !drafting;
  // Replay, like simulation, is for drafts on a host that announces it.
  const replayOnOther = lens === 'replay' && !(drafting && session.historyReadings);
  const tabLens: 'table' | 'readings' | 'changes' | 'report' | 'history' | 'simulate' | 'replay' | undefined = simulateOnOther || replayOnOther ? undefined : lens === 'source' || lens === 'edit' ? 'table' : lens;
  // Two versions side by side need the full width: one view at a time, and the decision docks below the diff.
  const comparing = !editing && (lens === 'changes' || lens === 'report');
  // The samples and their results need the full width too; the inspector steps aside.
  const wide = comparing || (!editing && (lens === 'simulate' || lens === 'replay'));
  // Approve only what is on screen: the diff shown must be of this version's content, and the content must be what
  // the user opened. The same query as the Changes view, so nothing is fetched twice.
  const diff = useVersionDiff(version, comparing && version.status === 'PENDING_APPROVAL' ? base?.version : undefined);
  let review: Review = 'ok';
  if (conflicted || openedHash !== version.canonicalHash || (diff.data !== undefined && diff.data.canonicalHash !== version.canonicalHash)) review = 'changed';
  else if (diff.isFetching) review = 'checking';
  const decision = (docked: boolean) => (
    <DecisionCard version={version} docked={docked} review={review} onReload={() => { approve.reset(); reject.reset(); void onReloadChanges(); }}
      onReviewAgain={() => { onReviewAgain(); go({ lens: 'changes' }); }}
      approve={{ start: ({ comment, acknowledgedWarnings }) => { approve.start({ version, reviewedHash: openedHash, comment, acknowledgedWarnings }); }, isPending: approve.isPending, error: approve.error }}
      reject={{ start: (reason) => { reject.start({ version, reviewedHash: openedHash, reason }); }, isPending: reject.isPending, error: reject.error }} />
  );
  // The breadcrumb names what is on screen: "Version N draft" for a draft, while editing too.
  const crumb = version.status === 'DRAFT' ? t('crumb.versionDraft', { version: String(version.version) }) : undefined;
  useEffect(() => {
    setCrumbLabel(crumb);
    return () => { setCrumbLabel(undefined); };
  }, [crumb]);
  const title = nameOf('rule set', version.ruleSet, version);
  useEffect(() => {
    setCrumbRuleSetName(title);
    return () => { setCrumbRuleSetName(undefined); };
  }, [title]);

  const main: ReactNode = (
    <div className="card workbench__main">
      <LensContent lens={lens} version={version} base={base} matched={matched} onOpenTable={() => { go({ lens: 'table' }); }} onEdit={() => { go({ lens: 'edit' }); }} seen={seen} />
    </div>
  );

  return (
    <>
      <div className="rs-head">
        <div className="rs-head__row">
          <div className="rs-head__titles">
            <h1 id="rule-set-title" className="rs-head__title" tabIndex={-1}>
              {title} {editing ? <StatusBadge status="DRAFT" /> : <StatusBadge status={version.status} />}
            </h1>
            <Subtitle version={version} editing={editing} />
            <p className="rule-key"><span className="rule-key__term">{t('ruleSet.key')}</span> <code className="rule-key__value">{version.ruleSet}</code> <CopyButton value={version.ruleSet} label={t('ruleSet.copyKey')} iconOnly /></p>
          </div>
          {!editing ? (
            <div className="rs-head__actions">
              <label className="sr-only" htmlFor="version-picker">{t('ruleSet.versions')}</label>
              <select id="version-picker" className="field__input field__input--compact" value={version.version} onChange={(e) => { go({ version: Number(e.target.value) }); }}>
                {versions.map((v) => <option key={v.version} value={v.version}>{t(`versionState.${v.status}`, { version: String(v.version) })}</option>)}
              </select>
              <HeaderActions version={version} demoteRequest={lens === 'simulate' || lens === 'replay'} onEdit={() => { go({ lens: 'edit' }); }}
                onDiscarded={(discarded) => {
                  say(t('discard.done', { version: String(discarded.version) }));
                  // Back to where the author came from: the rule set's version in front, or the list when none is left.
                  navigate(versions.some((v) => v.version !== discarded.version) ? { name: 'rule-set', ruleSet: discarded.ruleSet, version: undefined, lens: undefined } : { name: 'rule-sets' });
                }}
                onWithdrawn={(message) => { say(message); }}
                newDraft={{ start: () => { newDraft.start(version.version); }, isPending: newDraft.isPending, error: newDraft.error }}
                requestPublish={{ start: ({ report, effectiveFrom }) => { requestPublish.start({ version, report, effectiveFrom }); }, isPending: requestPublish.isPending, error: requestPublish.error }} />
            </div>
          ) : null}
        </div>
        <DraftsOffNote />
        {/* The reason sits beside Edit draft when the header shows it; otherwise it is the one line under the title. */}
        {lens === 'edit' && block !== undefined && block !== 'dock.draftsOff' && !showsEdit(version, session) ? (
          <p className="reason-line rs-head__note"><span aria-hidden="true">⊘</span> {t(block, { who: version.createdBy ?? '' })}</p>
        ) : null}
        {newDraft.error ? <ProblemNotice error={newDraft.error} /> : null}
        {picker.input}
        {importVersion.error !== undefined && importVersion.error !== null ? <ProblemNotice error={importVersion.error} located refusedFile={importVersion.fileName !== undefined ? { fileName: importVersion.fileName, columnLabel: label } : undefined} /> : null}
        {exportFile.error ? <ProblemNotice error={exportFile.error} located columnLabel={label} message={refusedWith(exportFile.error, 'CSV_NO_TABLE_FORM') ? t('export.noTable') : undefined} /> : null}
        {requestPublish.error && !(session.simulationReports && session.requiresReport(version.ruleSet)) ? <ProblemNotice error={requestPublish.error} onReload={() => { window.location.reload(); }} /> : null}
        {!editing ? (
          <div className="rs-head__tabs">
            <Tabs label={t('lens.label')} className="rs-tabs" selected={tabLens} onSelect={(id) => { go({ lens: id }); }}
              items={[
                { id: 'table', label: t('lens.table') }, { id: 'readings', label: t('lens.readings') }, { id: 'changes', label: t('lens.changes') },
                // The report attached to a publication request opens beside the diff, once the version was requested (PD-115).
                ...(session.simulationReports && version.status !== 'DRAFT' ? [{ id: 'report', label: t('lens.report') } as const] : []),
                { id: 'history', label: t('lens.history') },
                // Simulation runs on drafts only (CKP-004).
                ...(drafting ? [{ id: 'simulate', label: t('lens.simulate') } as const] : []),
                // Replay of logged decisions, where the host announces it (/meta feature rules.history-readings).
                ...(drafting && session.historyReadings ? [{ id: 'replay', label: t('lens.replay') } as const] : []),
              ]}>
              {null}
            </Tabs>
            <div className="rs-head__files">
              {can('rules:author') ? (
                <button type="button" className="btn btn--ghost" disabled={importVersion.isPending} aria-busy={importVersion.isPending} onClick={picker.open}>
                  {importVersion.isPending ? t('rulesArea.importing') : t('ruleSet.import')}
                </button>
              ) : null}
              <MenuButton label={t('export.label')} className="btn btn--ghost" trigger={<>{exportFile.isPending ? t('export.busy') : t('export.label')} <span aria-hidden="true">⌄</span></>}
                items={[
                  { label: t('export.json'), onSelect: () => { exportFile.start({ name: version.ruleSet, version: version.version, format: 'json' }); }, disabled: exportFile.isPending },
                  { label: t('export.dmn'), onSelect: () => { exportFile.start({ name: version.ruleSet, version: version.version, format: 'dmn' }); }, disabled: exportFile.isPending },
                  { label: t('export.csv'), onSelect: () => { exportFile.start({ name: version.ruleSet, version: version.version, format: 'csv' }); }, disabled: exportFile.isPending },
                ]} />
              <MenuButton label={t('more.label')} className="btn btn--ghost" trigger={<>{t('more.label')} <span aria-hidden="true">⌄</span></>}
                items={[{ label: t('more.source'), onSelect: () => { go({ lens: 'source' }); } }]} />
            </div>
          </div>
        ) : null}
      </div>
      {editing ? (
        <DraftWorkspace latest={version}
          onSaved={(saved) => {
            say(t('editor.replaced', { version: String(saved.version) }), true);
            navigate({ name: 'rule-set', ruleSet: saved.ruleSet, version: saved.version, lens: 'table' });
          }}
          onDiscard={() => { go({ lens: 'table' }); }} />
      ) : null}
      {!editing && unclassified.length > 0 && (version.status === 'DRAFT' || version.status === 'PENDING_APPROVAL') ? <UnclassifiedNote fields={unclassified} /> : null}
      {editing ? null : <div className={wide ? 'workbench workbench--wide' : 'workbench'}>
        {main}
        {wide ? null : <aside className="workbench__side">
          {version.status === 'PENDING_APPROVAL' ? decision(false) : null}
          {version.status === 'DRAFT' && personalData.length > 0 ? (
            // The author sees what the approver will see, before requesting publication.
            <section aria-labelledby="draft-pii-title">
              <h2 id="draft-pii-title" className="sr-only">{t('pii.title')}</h2>
              <PersonalDataWarnings warnings={personalData} definition={version.definition} />
            </section>
          ) : null}
          {lens === 'history' ? <EvidenceExport ruleSet={version.ruleSet} versions={versions} /> : null}
          {lens !== 'history' ? (
            <Inspector version={version} importedFrom={importedFrom[version.version]} onMatched={(ids) => {
              setMatched(new Set(ids));
              // The matched row is marked in the table, so the table is what the user sees next.
              if (lens !== 'table' && lens !== 'readings' && ids.length > 0) go({ lens: 'table' });
            }} />
          ) : null}
        </aside>}
      </div>}
      {comparing && version.status === 'PENDING_APPROVAL' ? decision(true) : null}
    </>
  );
}

/** Notes the content of a version when it first reaches the screen, so a later change under the approver can be marked. */
function SeenRecorder({ version, onSeen }: { version: RuleSetVersion; onSeen: (seen: SeenVersion) => void }) {
  const { definition, version: number } = version;
  useEffect(() => { onSeen({ definition, at: Date.now() }); }, [number]); // eslint-disable-line react-hooks/exhaustive-deps -- once per version
  return null;
}

export function RuleSetPage({ name, versionNumber, lens }: { name: string; versionNumber: number | undefined; lens: Lens | undefined }) {
  const { t } = useI18n();
  const versions = useVersions(name);
  const [toast, setToast] = useState<{ text: string; sticky: boolean } | undefined>(undefined);
  // The content hash of each version as the user first saw it on this page (four eyes: approve what you reviewed).
  const [opened, setOpened] = useState<Readonly<Record<number, string>>>({});
  // The content of each version as the user first had it on screen: what changed since is marked once in the diff.
  const [seen, setSeen] = useState<Readonly<Record<number, SeenVersion>>>({});
  // Versions where the engine refused a decision with 409 VERSION_CONFLICT; the approver checks the new content first.
  const [conflicted, setConflicted] = useState<ReadonlySet<number>>(new Set());
  const closeToast = useCallback(() => { setToast(undefined); }, []);
  // Import of the next version from a file (CKP-030); it stays available when cockpit drafts are off (PD-075).
  const [pickedFile, setPickedFile] = useState<string | undefined>(undefined);
  // The success callback is fixed when the import starts, so it reads the picked file from a ref.
  const picked = useRef<string | undefined>(undefined);
  const [focusTitle, setFocusTitle] = useState<number | undefined>(undefined);
  const titleFocused = useCallback(() => { setFocusTitle(undefined); }, []);
  const [importedFrom, setImportedFrom] = useState<Readonly<Record<number, string>>>({});
  const importVersion = useSaveDraft(name, (created) => {
    const file = picked.current;
    if (file !== undefined) setImportedFrom((known) => ({ ...known, [created.version]: file }));
    setToast({ text: t('ruleSet.imported', { version: String(created.version) }), sticky: false });
    setFocusTitle(created.version);
    navigate({ name: 'rule-set', ruleSet: created.ruleSet, version: created.version, lens: 'table' });
  });
  // A picked spreadsheet waits here for the user's confirmation; nothing is sent before.
  const [sheet, setSheet] = useState<PickedSheet | undefined>(undefined);
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

  // The shell's breadcrumb (Rules / card-fee / Version N) leads back to the list.
  const frame = (content: ReactNode) => <section className="workspace">{content}</section>;
  if (versions.isPending || (missing && versions.isFetching)) return frame(<p className="empty" role="status">{t('ruleSets.loading')}</p>);
  if (versions.isError) return frame(<ProblemNotice error={versions.error} />);
  if (!latest || selected === undefined) return frame(<p className="empty" role="alert">{t('ruleSets.empty')}</p>);
  if (!known) return frame(<p className="empty" role="alert">{t('ruleSet.versionMissing', { version: String(selected) })}</p>);
  if (version.isPending) return frame(<p className="empty" role="status">{t('ruleSets.loading')}</p>);
  if (version.isError) return frame(<ProblemNotice error={version.error} />);
  const current = version.data;
  const activeLens = lens ?? (current.status === 'PENDING_APPROVAL' ? 'changes' : 'table');
  const openedHash = opened[current.version];
  if (openedHash === undefined) setOpened({ ...opened, [current.version]: current.canonicalHash });

  return (
    <section className="workspace rs" aria-labelledby="rule-set-title">
      <SeenRecorder version={current} onSeen={(first) => { setSeen((known) => (known[current.version] === undefined ? { ...known, [current.version]: first } : known)); }} />
      <Workbench
        // While the grid is open a newer lock version must not remount it: the edits stay, and the editor offers the
        // newer version (DraftWorkspace). Elsewhere a new lock version is a fresh view.
        key={`${name}-${String(current.version)}-${activeLens === 'edit' ? 'edit' : String(current.lockVersion)}`}
        version={current}
        versions={versions.data}
        openedHash={openedHash ?? current.canonicalHash}
        seen={seen[current.version]}
        conflicted={conflicted.has(current.version)}
        onConflict={() => { setConflicted((known) => new Set(known).add(current.version)); }}
        onReviewAgain={() => {
          setOpened({ ...opened, [current.version]: current.canonicalHash });
          setConflicted((known) => { const next = new Set(known); next.delete(current.version); return next; });
        }}
        onReloadChanges={async () => {
          const [fresh] = await Promise.all([version.refetch(), versions.refetch()]);
          const hash = fresh.data?.canonicalHash;
          if (hash !== undefined) setOpened((known) => ({ ...known, [current.version]: hash }));
          setConflicted((known) => { const next = new Set(known); next.delete(current.version); return next; });
        }}
        lens={activeLens}
        navigate={navigate}
        say={(text, sticky = false) => {
          setToast({ text, sticky });
          // After a save the page returns to the table: the focus goes to the title, beside the saved status.
          if (sticky) setFocusTitle(current.version);
        }}
        importedFrom={importedFrom}
        focusTitle={focusTitle}
        onTitleFocused={titleFocused}
        onSheet={(picked) => { setToast(undefined); setSheet(picked); }}
        importVersion={{ start: (document, fileName) => { setToast(undefined); picked.current = fileName; setPickedFile(fileName); importVersion.start(document); }, isPending: importVersion.isPending, error: importVersion.error, fileName: pickedFile }}
      />
      {sheet !== undefined ? (
        <ImportSpreadsheetDialog ruleSet={name} base={current.version} first={sheet}
          columnLabel={(column) => { const c = [...current.definition.inputs, ...current.definition.outputs].find((x) => x.name === column); return columnLabel(column, c?.description); }}
          onClose={() => { setSheet(undefined); }}
          onCreated={(created, fileName) => {
            setSheet(undefined);
            setImportedFrom((known) => ({ ...known, [created.version]: fileName }));
            setToast({ text: t('ruleSet.imported', { version: String(created.version) }), sticky: false });
            setFocusTitle(created.version);
            navigate({ name: 'rule-set', ruleSet: created.ruleSet, version: created.version, lens: 'table' });
          }} />
      ) : null}
      <Toast text={toast?.text} sticky={toast?.sticky ?? false} onClose={closeToast} />
    </section>
  );
}
