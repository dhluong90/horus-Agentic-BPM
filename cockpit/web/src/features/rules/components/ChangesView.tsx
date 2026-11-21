import type { InputMasking, Rule, RuleSetDefinition, RuleSetVersion, VersionDiff } from '../../../api/rules/schemas';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../model/dates';
import { cellText, nonAsciiCharacters } from '../model/nonAscii';
import { clauseLabel, columnLabel, readCondition, readOutput } from '../model/reading';
import { changeNotes, flagged, maskRemoved, type ChangeNote } from '../model/masking';
import { countChanges, pairRows, structureNotes, type PairChange, type RowPair } from '../model/versionDiff';
import { CellValue } from './CellValue';

const GLYPH: Record<PairChange, string> = { ADDED: '＋', CHANGED: '✎', MOVED: '⇅', REMOVED: '−', UNCHANGED: '' };

type Side = 'base' | 'next';

/** The characters the engine flagged, named by code point; an escape or a hidden character has no visible glyph. */
function NonAsciiMark({ raw }: { raw: string }) {
  const { t } = useI18n();
  const characters = nonAsciiCharacters(raw);
  return (
    <span className="nonascii-mark">
      <span aria-hidden="true">▲</span> {characters.length > 0 ? t('changes.nonAscii', { chars: characters.join(', ') }) : t('changes.hiddenCharacter')}
    </span>
  );
}

/** Version number, effective date and content hash: the metadata of one side, never content changes (RLE-028). */
function SideHead({ version, effectiveFrom, hash, span, side }: { version: RuleSetVersion; effectiveFrom: string | null; hash: string; span: number; side: Side }) {
  const { t, locale } = useI18n();
  const when = formatDateTime(effectiveFrom, locale);
  let meta: string;
  if (version.status === 'PENDING_APPROVAL' || version.status === 'DRAFT') meta = when === undefined ? t('changes.sideProposed') : t('changes.sideProposedOn', { when });
  else if (when === undefined) meta = t('changes.sideNoDate');
  else meta = version.status === 'PUBLISHED' ? t('changes.sideInEffect', { when }) : t('changes.sideEffectiveFrom', { when });
  return (
    <th scope="colgroup" colSpan={span} className={`sbs__side sbs__side--${side} sbs__start--${side}`}>
      <span className="sbs__title">{t('changes.sideTitle', { version: String(version.version) })}</span>
      <span className="sbs__meta">{meta}</span>
      <span className="sbs__hash">{t('details.hash')} <span className="mono" title={hash}>{hash.slice(0, 12)}</span></span>
    </th>
  );
}

function ColumnGroups({ definition, side }: { definition: RuleSetDefinition; side: Side }) {
  const { t } = useI18n();
  return (
    <>
      {definition.inputs.length > 0 ? <th scope="colgroup" colSpan={definition.inputs.length} className={`dt__group sbs__start--${side}`}>{t('table.when')}</th> : null}
      {definition.outputs.length > 0 ? <th scope="colgroup" colSpan={definition.outputs.length} className="dt__group dt__group--then">{t('table.then')}</th> : null}
    </>
  );
}

/** The amber ▲ on a condition header whose masking changed to not masked; the sentence above the table says what. */
function MaskingFlag({ sentence }: { sentence: string }) {
  return (
    <span className="masking-flag" title={sentence}>
      <span aria-hidden="true">▲</span><span className="sr-only">{sentence}</span>
    </span>
  );
}

function ColumnNames({ definition, side, flags }: { definition: RuleSetDefinition; side: Side; flags?: ReadonlyMap<string, string> }) {
  const { locale } = useI18n();
  return (
    <>
      {definition.inputs.map((input, index) => {
        const sentence = flags?.get(input.name);
        return (
          <th key={`${side}-in-${input.name}`} scope="col" className={index === 0 ? `sbs__start--${side}` : undefined}>
            {clauseLabel(input, locale)}
            {sentence !== undefined ? <> <MaskingFlag sentence={sentence} /></> : null}
          </th>
        );
      })}
      {definition.outputs.map((output, index) => (
        <th key={`${side}-out-${output.name}`} scope="col" className={index === 0 ? 'dt__first-then' : undefined}>{clauseLabel(output, locale)}</th>
      ))}
    </>
  );
}

/** One side of a row: its cells, or one quiet cell saying the row is not on this side. */
function SideCells({ definition, rule, pair, side, absent }: { definition: RuleSetDefinition; rule: Rule | undefined; pair: RowPair; side: Side; absent: string }) {
  const { t, locale } = useI18n();
  const span = definition.inputs.length + definition.outputs.length;
  if (rule === undefined) return <td colSpan={span} className={`sbs__absent sbs__start--${side}`}>{absent}</td>;
  const cell = (part: 'when' | 'then', name: string, first: string | undefined) => {
    const key = `${part}.${name}`;
    const changed = pair.changed.has(key);
    const value = part === 'when' ? rule.when[name] : rule.then[name];
    const classes = [first, changed ? (side === 'next' ? 'diff-td--mod' : 'diff-td--old') : undefined].filter(Boolean).join(' ');
    return (
      <td key={`${side}-${key}`} className={classes === '' ? undefined : classes}>
        {changed ? <span className="sr-only">{side === 'next' ? t('change.CHANGED') : t('changes.was')} </span> : null}
        <CellValue reading={part === 'when' ? readCondition(rule.when[name], locale) : readOutput(rule.then[name], locale)} />
        {side === 'next' && pair.nonAscii.has(key) ? <NonAsciiMark raw={cellText(value)} /> : null}
      </td>
    );
  };
  return (
    <>
      {definition.inputs.map((input, index) => cell('when', input.name, index === 0 ? `sbs__start--${side}` : undefined))}
      {definition.outputs.map((output, index) => cell('then', output.name, index === 0 ? 'dt__first-then' : undefined))}
    </>
  );
}

function RowMarks({ pair }: { pair: RowPair }) {
  const { t } = useI18n();
  const marks: PairChange[] = pair.change === 'UNCHANGED' ? [] : pair.change === 'CHANGED' && pair.moved ? ['CHANGED', 'MOVED'] : [pair.change];
  return (
    <>
      {marks.map((mark) => (
        <span key={mark} className={`change-mark change-mark--${mark.toLowerCase()}`}>
          <span aria-hidden="true">{GLYPH[mark]}</span> {t(`change.${mark}`)}
        </span>
      ))}
    </>
  );
}

/** A column named in a note reads with the same label as its header: from the version that has the column. */
function noteColumn(note: { group: 'when' | 'then'; name: string }, from: RuleSetDefinition, locale: Locale): string {
  const clause = (note.group === 'when' ? from.inputs : from.outputs).find((c) => c.name === note.name);
  return clause ? clauseLabel(clause, locale) : columnLabel(note.name, undefined);
}

type Translate = ReturnType<typeof useI18n>['t'];

/** One sentence for a note above the table; also the tooltip of a flagged header. */
function noteText(t: Translate, note: ChangeNote, base: RuleSetDefinition, next: RuleSetDefinition, locale: Locale): string {
  const policy = (value: string) => {
    const upper = value.toUpperCase();
    return t(upper === 'UNIQUE' ? 'policy.UNIQUE' : upper === 'COLLECT' ? 'policy.COLLECT' : 'policy.FIRST');
  };
  switch (note.kind) {
    case 'hitPolicy':
      return t('changes.note.hitPolicy', { from: policy(note.from), to: policy(note.to) });
    case 'member':
      return note.member === 'description' ? t('changes.note.description') : t('changes.note.member', { member: note.member });
    case 'masking': {
      const values = { name: noteColumn({ group: 'when', name: note.name }, next, locale), from: t(`masking.${note.from}`), to: t(`masking.${note.to}`) };
      return t(note.removed ? 'changes.note.maskRemoved' : 'changes.note.maskChanged', values);
    }
    case 'columnAdded':
      if (note.group === 'when' && note.masking !== undefined) {
        return t('changes.note.columnAdded.whenMasking', { name: noteColumn(note, next, locale), status: t(`masking.${note.masking}`) });
      }
      return t(`changes.note.columnAdded.${note.group}`, { name: noteColumn(note, next, locale) });
    case 'columnRemoved':
    case 'columnChanged':
      return t(`changes.note.${note.kind}.${note.group}`, { name: noteColumn(note, note.kind === 'columnRemoved' ? base : next, locale) });
    case 'columnsReordered':
      return t(`changes.note.columnsReordered.${note.group}`);
  }
}

/**
 * The approver's comparison: the base version (usually the one in effect) and the proposed version side by
 * side in one table, so each rule id sits on one line. What changed comes from the engine's diff (RLE-028);
 * the cells use the editor's labels and formats. Effective dates and hashes are version metadata in the header.
 */
export function ChangesView({ diff, base, next }: { diff: VersionDiff; base: RuleSetVersion; next: RuleSetVersion }) {
  const { t, locale } = useI18n();
  const pairs = pairRows(diff, base.definition.rules, next.definition.rules);
  const counts = countChanges(diff);
  const notes = changeNotes(diff, structureNotes(diff));
  // Each flagged condition header says, in its tooltip and to a screen reader, the sentence that explains it.
  const flags = new Map(notes.flatMap((note): [string, string][] => {
    const name = note.kind === 'masking' || (note.kind === 'columnAdded' && note.group === 'when') ? note.name : undefined;
    const item: InputMasking | undefined = name === undefined ? undefined : diff.masking.find((m) => m.input === name);
    return name !== undefined && flagged(item) ? [[name, noteText(t, note, base.definition, next.definition, locale)]] : [];
  }));
  const removedMasks = diff.masking.filter(maskRemoved).length;
  const values = { added: String(counts.added), changed: String(counts.changed), moved: String(counts.moved), removed: String(counts.removed), unchanged: String(counts.unchanged) };
  const baseSpan = base.definition.inputs.length + base.definition.outputs.length;
  const nextSpan = next.definition.inputs.length + next.definition.outputs.length;
  const caption = t('changes.caption', { base: String(base.version), version: String(next.version), name: next.ruleSet });
  return (
    <div className="changes">
      <p className="changes__summary">
        <strong className="tnum">{t(counts.moved > 0 ? 'changes.summaryMoved' : 'changes.summaryLine', values)}</strong>
        {counts.nonAscii > 0 ? (
          <span className="nonascii-mark"><span aria-hidden="true">▲</span> {counts.nonAscii === 1 ? t('changes.nonAsciiCountOne') : t('changes.nonAsciiCount', { count: String(counts.nonAscii) })}</span>
        ) : null}
        {removedMasks > 0 ? (
          <span className="nonascii-mark"><span aria-hidden="true">▲</span> {removedMasks === 1 ? t('changes.masksRemovedOne') : t('changes.masksRemovedCount', { count: String(removedMasks) })}</span>
        ) : null}
      </p>
      {notes.length > 0 ? (
        <ul className="changes__notes" aria-label={t('changes.notesLabel')}>
          {notes.map((note, index) => <li key={index}>{noteText(t, note, base.definition, next.definition, locale)}</li>)}
        </ul>
      ) : null}
      <div className="dt-scroll" role="region" tabIndex={0} aria-label={caption}>
        <table className="dt dt--diff dt--sbs">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr className="sbs__versions">
              <th scope="col" rowSpan={3} className="dt__rowhead">{t('table.row')}</th>
              <SideHead version={base} effectiveFrom={diff.effectiveFrom.from} hash={diff.baseCanonicalHash} span={baseSpan} side="base" />
              <SideHead version={next} effectiveFrom={diff.effectiveFrom.to} hash={diff.canonicalHash} span={nextSpan} side="next" />
            </tr>
            <tr className="dt__groups">
              <ColumnGroups definition={base.definition} side="base" />
              <ColumnGroups definition={next.definition} side="next" />
            </tr>
            <tr>
              <ColumnNames definition={base.definition} side="base" />
              <ColumnNames definition={next.definition} side="next" flags={flags} />
            </tr>
          </thead>
          <tbody>
            {pairs.map((pair) => (
              <tr key={`${pair.change}-${pair.ruleId}`} className={`diff-row diff-row--${pair.change.toLowerCase()}`}>
                <th scope="row" className="dt__rowhead">
                  <span className="row-id mono">{pair.ruleId}</span>{' '}
                  <RowMarks pair={pair} />
                </th>
                <SideCells definition={base.definition} rule={pair.base} pair={pair} side="base" absent={t('changes.notInBase', { version: String(base.version) })} />
                <SideCells definition={next.definition} rule={pair.next} pair={pair} side="next" absent={t('changes.removedIn', { version: String(next.version) })} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
