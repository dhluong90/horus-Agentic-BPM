import { useMemo, useReducer, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { RuleDocument } from '../../../api/rules/rulesClient';
import type { Rule, RuleSetVersion } from '../../../api/rules/schemas';
import type { MessageKey } from '../../../i18n/en';
import { useI18n, type Locale, type Translate } from '../../../i18n/I18nProvider';
import { useDraftCheck, useReplaceDraft } from '../api/useRules';
import { cellSentence } from '../model/cellMessages';
import { diffRules } from '../model/diff';
import { draftReducer, initialDraft } from '../model/draft';
import { placeErrors, placeProblems, type ErrorPlace } from '../model/engineErrors';
import { clauseLabel } from '../model/reading';
import { toDocument, toRows, type EditableRow } from '../model/table';
import { EditDock } from './ActionDock';
import { cellKey, DraftChanges, type Refused } from './DraftChanges';
import { DraftEditor } from './DraftEditor';
import { ProblemNotice } from './ProblemNotice';

/** The engine's text of the table: JSON text, which is YAML 1.2, so it goes as YAML (the engine imports YAML). */
function documentOf(version: RuleSetVersion, rows: readonly EditableRow[]): RuleDocument {
  return { contentType: 'application/yaml', text: JSON.stringify(toDocument(version.definition, rows), null, 2) };
}

const isConflict = (error: unknown): boolean => {
  if (error === null || error === undefined) return false;
  const hostError = toHostError(error);
  return hostError.kind === 'problem' && hostError.code === 'VERSION_CONFLICT';
};

/** The version the author loaded, and its table as the engine's text: the edit is bound to both. */
interface Loaded { version: RuleSetVersion; text: string }

const load = (version: RuleSetVersion): Loaded => ({ version, text: documentOf(version, toRows(version.definition)).text });

const rulesOf = (version: RuleSetVersion, rows: readonly EditableRow[]): Rule[] => (toDocument(version.definition, rows)['rules'] ?? []) as Rule[];

/** What the Changes card shows: the committed table the engine last answered for, and what it refused in it. */
interface Shown { text: string; rules: readonly Rule[]; refused: Refused }

const NOTHING_REFUSED: Refused = { cells: new Set(), rows: new Set() };

/** At most this many cells are named in the conflict line; the rest are counted. */
const CONFLICT_CELLS = 4;

/**
 * The cells someone else changed between the version the author opened and the latest one, as short names
 * ("R3 Fee", "R7 new row"); empty while the latest version is not read yet or nothing in the table differs.
 */
function theirChanges(opened: RuleSetVersion, latest: RuleSetVersion, t: Translate, locale: Locale): string {
  if (latest.lockVersion === opened.lockVersion) return '';
  const clauses = [...opened.definition.inputs, ...opened.definition.outputs, ...latest.definition.inputs, ...latest.definition.outputs];
  const label = (cell: string) => {
    const name = cell.slice(cell.indexOf('.') + 1);
    const clause = clauses.find((c) => c.name === name);
    return clause ? clauseLabel(clause, locale) : name;
  };
  const names = diffRules(opened.definition.rules, latest.definition.rules).flatMap((row): string[] => {
    if (row.change === 'ADDED') return [t('editor.conflict.rowAdded', { row: row.ruleId })];
    if (row.change === 'REMOVED') return [t('editor.conflict.rowRemoved', { row: row.ruleId })];
    return [...row.changedCells].map((cell) => `${row.ruleId} ${label(cell)}`);
  });
  if (names.length <= CONFLICT_CELLS) return names.join(', ');
  return t('editor.conflict.more', { cells: names.slice(0, CONFLICT_CELLS).join(', '), count: String(names.length - CONFLICT_CELLS) });
}

/**
 * The grid editor of one stored draft with its side card and save dock. Every committed cell is checked by the
 * engine (RLE-090); its problems show on their cells in the reader's language. Save draft replaces the draft in
 * place with the lock version the author loaded, so a save never overwrites a change the author has not seen:
 * the engine answers 409 and the edits stay on screen until the author loads the latest version.
 *
 * `latest` is the version as the cockpit reads it now; it differs from the loaded one once someone else saved.
 */
export function DraftWorkspace({ latest, onSaved, onDiscard }: { latest: RuleSetVersion; onSaved: (saved: RuleSetVersion) => void; onDiscard: () => void }) {
  const { t, locale } = useI18n();
  const [loaded, setLoaded] = useState<Loaded>(() => load(latest));
  const [draft, dispatch] = useReducer(draftReducer, loaded.version.definition, (definition) => initialDraft(toRows(definition)));
  const save = useReplaceDraft(onSaved);
  const { version } = loaded;

  const current = useMemo(() => documentOf(version, draft.rows), [version, draft.rows]);
  const committed = useMemo(() => documentOf(version, draft.committed), [version, draft.committed]);
  // The stored draft passed the engine's checks when it was saved, so only a changed table is checked.
  const changed = committed.text !== loaded.text;
  const check = useDraftCheck(version.ruleSet, version.version, changed ? committed : undefined);
  const answer = changed ? check.data : undefined;
  const fresh = answer !== undefined && !check.isPlaceholderData;

  const newer = latest.lockVersion !== version.lockVersion;
  const conflict = isConflict(save.error) || newer;

  const rowIds = new Set(draft.rows.map((row) => row.id));
  const columns = new Set([...version.definition.inputs, ...version.definition.outputs].map((c) => c.name));
  const checkErrors: ErrorPlace[] = answer ? placeProblems(answer.problems, rowIds, columns, locale, t('cellError.other')) : [];
  // A save refused with 422 names its cells in the engine's English: each becomes one plain sentence here.
  const saveErrors = placeErrors(save.error ? toHostError(save.error) : undefined).map((e) => ({ ...e, message: t(cellSentence(e.message)) }));
  const errors = checkErrors.length > 0 ? checkErrors : saveErrors;
  const tableErrors = errors.filter((e) => e.kind === 'table');
  const located = errors.length > 0 && tableErrors.length === 0;

  const draftRules = rulesOf(version, draft.rows);
  const unsaved = diffRules(version.definition.rules, draftRules).filter((row) => row.change !== 'UNCHANGED').length;

  // The Changes card follows the committed table, and only once the engine has answered for it: a cell being typed,
  // or one the engine refused, never shows there. While a check runs the card keeps what it showed.
  const [shown, setShown] = useState<Shown>(() => ({ text: loaded.text, rules: version.definition.rules, refused: NOTHING_REFUSED }));
  if (shown.text !== committed.text) {
    if (!changed) setShown({ text: committed.text, rules: version.definition.rules, refused: NOTHING_REFUSED });
    else if (fresh) {
      setShown({
        text: committed.text,
        rules: rulesOf(version, draft.committed),
        refused: {
          cells: new Set(checkErrors.flatMap((e) => (e.kind === 'cell' ? [cellKey(e.ruleId, e.column)] : []))),
          rows: new Set(checkErrors.flatMap((e) => (e.kind === 'row' ? [e.ruleId] : []))),
        },
      });
    }
  }
  const known = fresh && current.text === committed.text && !answer.valid;
  let blocked: MessageKey | undefined;
  if (conflict) blocked = 'editor.block.conflict';
  else if (current.text === loaded.text) blocked = 'editor.block.nothing';
  else if (known) blocked = 'editor.block.fix';

  const reload = () => {
    setLoaded(load(latest));
    dispatch({ type: 'reset', rows: toRows(latest.definition) });
    save.reset();
  };

  return (
    <>
      <div className="workbench workbench--editing">
        <div className="card workbench__main">
          {check.isError ? <ProblemNotice error={check.error} /> : null}
          {save.error && !conflict ? <ProblemNotice error={save.error} showDetail={locale !== 'en' || !located} /> : null}
          {tableErrors.length > 0 && checkErrors.length > 0 ? (
            <ul className="notice notice--bad table-problems" aria-label={t('editor.tableProblems')}>
              {tableErrors.map((e, index) => <li key={index}><span aria-hidden="true">✕</span> {e.message}</li>)}
            </ul>
          ) : null}
          {answer?.problemsTruncated === true ? <p className="dock__note">{t('editor.truncated', { count: String(answer.problems.length) })}</p> : null}
          <DraftEditor definition={version.definition} draft={draft} dispatch={dispatch} errors={errors} />
        </div>
        <aside className="workbench__side">
          <DraftChanges definition={version.definition} base={version.definition.rules} next={shown.rules} refused={shown.refused} />
        </aside>
      </div>
      <EditDock unsaved={unsaved} toFix={errors.length} checking={changed && check.isFetching} saving={save.isPending} blocked={blocked}
        conflict={conflict ? { cells: theirChanges(version, latest, t, locale), ready: newer, onLoad: reload } : undefined}
        onSave={() => { save.start({ ruleSet: version.ruleSet, version: version.version, lockVersion: version.lockVersion, document: current }); }}
        onDiscard={onDiscard}
        onViewChanges={() => {
          const card = window.document.getElementById('draft-changes');
          card?.scrollIntoView({ block: 'center' });
          card?.focus({ preventScroll: true });
        }} />
    </>
  );
}
