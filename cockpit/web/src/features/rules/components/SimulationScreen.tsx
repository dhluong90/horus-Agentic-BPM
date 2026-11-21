import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { toHostError } from '../../../api/hostError';
import type { InputClause, RuleSetDefinition, RuleSetVersion, SimulationReadingResult, SimulationReadings, SimulationSample } from '../../../api/rules/schemas';
import { AreaError, LoadingRows } from '../../../components/PageParts';
import type { MessageKey } from '../../../i18n/en';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { useSimulateSamples } from '../api/useRules';
import { valueLabel } from '../model/cells';
import { clauseLabel } from '../model/reading';
import { emptyRow, isBlank, readPastedRows, referenceOf, type PasteProblem, type SampleRow } from '../model/samples';
import { editBlock } from './ActionDock';
import { ProblemNotice } from './ProblemNotice';
import { EmptyNote, ForbiddenNote, MaskedNote, ReadingStyleSwitch, TooLargeNote, maskedInputs, readingIn, useViewerReadingStyle } from './ReadingParts';
import { SampleControl, buildSample, currencyOfInput, sampleCurrencies } from './TryItPanel';

/*
 * The simulation screen of a draft (CKP-004, CK-F-05): the author types or pastes a few samples, runs them against
 * the draft and the version in effect in one `:simulate-readings` call, and reads each outcome as the engine wrote
 * it (RLE-093). Nothing is logged. One view at a time: the samples, the paste box, or the results.
 */

type View = 'samples' | 'paste' | 'results';

/** What was sent, kept so each result can name its sample and a failed run can be tried again. */
interface Run {
  rows: readonly SampleRow[];
  currencies: Readonly<Record<string, string>>;
  samples: readonly SimulationSample[];
}

type Failure = 'forbidden' | 'too-large' | 'refused' | 'other';

function failureOf(error: unknown): Failure {
  const host = toHostError(error);
  if (host.kind !== 'problem') return 'other';
  if (host.status === 403) return 'forbidden';
  if (host.code === 'READINGS_TOO_LARGE' || host.status === 413) return 'too-large';
  // The engine refused the request itself (a sample it does not accept, a draft that no longer compiles): its reason, no retry.
  if (host.status >= 400 && host.status < 500) return 'refused';
  return 'other';
}

/** One sample in words, for naming it beside its result: masked values as dots, never their text. */
function describe(definition: RuleSetDefinition, row: SampleRow, currencies: Readonly<Record<string, string>>, locale: Locale, yes: string, no: string): string {
  return definition.inputs
    .filter((input) => (row.values[input.name] ?? '').trim() !== '')
    .map((input) => {
      const text = (row.values[input.name] ?? '').trim();
      let shown: string;
      if (input.mask === true) shown = '••••';
      else if (input.type === 'boolean') shown = text === 'true' ? yes : no;
      else if (input.allowed) shown = valueLabel(text);
      else if (input.type === 'money') shown = `${text}\u00a0${currencies[input.name] ?? ''}`.trim();
      else shown = text;
      return `${clauseLabel(input, locale)} ${shown}`;
    })
    .join(' · ');
}

function pasteMessage(problem: PasteProblem, t: (key: MessageKey, values?: Record<string, string>) => string, columns: string): string {
  switch (problem.kind) {
    case 'empty': return t('sim.paste.empty');
    case 'columns': return t('sim.paste.columns', { line: String(problem.line), columns });
    case 'choice': return t('sim.paste.choice', { line: String(problem.line), input: problem.input, choices: problem.choices.join(', ') });
    case 'yes-no': return t('sim.paste.yesNo', { line: String(problem.line), input: problem.input });
    case 'amount': return t('sim.paste.amount', { line: String(problem.line), input: problem.input });
    case 'currency': return t('sim.paste.currency', { line: String(problem.line), input: problem.input, currency: problem.currency });
  }
}

function SamplesGrid({ definition, rows, invalid, invalidId, onRow, onCurrency, onRemove }: {
  definition: RuleSetDefinition;
  rows: readonly SampleRow[];
  invalid: { key: number; field: string } | undefined;
  /** The message that says what is wrong with the marked cell. */
  invalidId: string;
  onRow: (key: number, change: Partial<Pick<SampleRow, 'label'>> & { field?: [string, string] }) => void;
  onCurrency: (input: string, code: string) => void;
  onRemove: (key: number) => void;
}) {
  const { t, locale } = useI18n();
  return (
    <div className="dt-scroll">
      <table className="dt simrun__grid">
        <caption className="sr-only">{t('sim.samples.title')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('sim.col.sample')}</th>
            <th scope="col">{t('sim.col.name')}</th>
            {definition.inputs.map((input) => (
              <th key={input.name} scope="col">
                {clauseLabel(input, locale)}
                {input.mask === true ? <span className="simrun__masked"><span aria-hidden="true"> ◐</span><span className="sr-only">, {t('sim.col.masked')}</span></span> : null}
              </th>
            ))}
            <th scope="col"><span className="sr-only">{t('sim.col.actions')}</span></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const reference = referenceOf(index);
            return (
              <tr key={row.key}>
                <th scope="row" className="dt__rowhead simrun__ref">{reference}</th>
                <td className="simrun__name">
                  <input className="field__input" maxLength={100} aria-label={t('sim.cell', { sample: reference, column: t('sim.col.name') })} placeholder={t('sim.namePlaceholder')}
                    value={row.label} onChange={(e) => { onRow(row.key, { label: e.target.value }); }} />
                </td>
                {definition.inputs.map((input) => (
                  <td key={input.name}>
                    <SampleControl input={input} value={row.values[input.name] ?? ''} currency={currencyOfInput(definition, input.name)}
                      invalid={invalid?.key === row.key && invalid.field === input.name}
                      describedBy={invalid?.key === row.key && invalid.field === input.name ? invalidId : undefined}
                      ariaLabel={t('sim.cell', { sample: reference, column: clauseLabel(input, locale) })}
                      onChange={(text) => { onRow(row.key, { field: [input.name, text] }); }}
                      onCurrency={(code) => { onCurrency(input.name, code); }} />
                  </td>
                ))}
                <td className="dt__actions">
                  {rows.length > 1 ? (
                    <button type="button" className="btn btn--ghost btn--icon hit" aria-label={t('sim.remove', { sample: reference })} onClick={() => { onRemove(row.key); }}>
                      <span aria-hidden="true">✕</span>
                    </button>
                  ) : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function Outcome({ outcome }: { outcome: SimulationReadingResult['simulated'] }) {
  const { locale } = useI18n();
  const [style] = useViewerReadingStyle();
  return <p className="reading__text tnum">{readingIn(outcome.readings, locale, style) ?? ''}</p>;
}

function Results({ answer, run, definition }: { answer: SimulationReadings; run: Run; definition: RuleSetDefinition }) {
  const { t, locale } = useI18n();
  if (!answer.languages.includes(locale)) return <EmptyNote text="readings.noLanguage" />;
  return (
    <div className="dt-scroll">
      <table className="dt simrun__results">
        <caption className="sr-only">{answer.results.length === 1 ? t('sim.results.captionOne') : t('sim.results.caption', { count: String(answer.results.length) })}</caption>
        <thead>
          <tr>
            <th scope="col">{t('sim.col.sample')}</th>
            <th scope="col">{t('sim.col.draft', { version: String(answer.version) })}</th>
            <th scope="col">{answer.baselineVersion !== null ? t('sim.inEffect', { version: String(answer.baselineVersion) }) : t('sim.col.noBaseline')}</th>
          </tr>
        </thead>
        <tbody>
          {answer.results.map((result) => {
            const row = run.rows[result.index];
            const said = row !== undefined ? describe(definition, row, run.currencies, locale, t('table.yes'), t('table.no')) : '';
            return (
              <tr key={result.index} className={result.changed ? 'simrun__row simrun__row--changed' : 'simrun__row'}>
                <th scope="row" className="simrun__sample">
                  <span className="simrun__title">
                    <span className="simrun__ref">{result.reference}</span>
                    {result.label !== null ? <span className="simrun__label">{result.label}</span> : null}
                  </span>
                  {said !== '' ? <span className="simrun__inputs tnum">{said}</span> : null}
                  <span className={result.changed ? 'change-mark change-mark--changed simrun__verdict' : 'badge badge--neutral simrun__verdict'}>
                    <span aria-hidden="true">{result.changed ? '✎' : '='}</span> {result.changed ? t('sim.changed') : t('sim.same')}
                  </span>
                </th>
                <td><Outcome outcome={result.simulated} /></td>
                <td>{result.baseline !== null ? <Outcome outcome={result.baseline} /> : <p className="reading__note">{t('sim.noBaseline')}</p>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The Simulate view of a draft. Without the permissions the samples can still be typed; Run stays visible,
 * disabled, with the reason beside it.
 */
export function SimulationScreen({ version, onEdit }: { version: RuleSetVersion; onEdit: () => void }) {
  const { t, locale } = useI18n();
  const session = useRulesSession();
  const { can } = session;
  const definition = version.definition;
  const simulate = useSimulateSamples(version);
  const [view, setView] = useState<View>('samples');
  const nextKey = useRef(1);
  const [rows, setRows] = useState<readonly SampleRow[]>([emptyRow(0)]);
  const [typedCurrency, setTypedCurrency] = useState<Readonly<Record<string, string>>>({});
  const [invalid, setInvalid] = useState<{ key: number; field: string } | undefined>(undefined);
  const [run, setRun] = useState<Run | undefined>(undefined);
  const [pasted, setPasted] = useState('');
  const [pasteProblem, setPasteProblem] = useState<string | undefined>(undefined);
  const heading = useRef<HTMLHeadingElement>(null);
  const reasonId = useId();
  const invalidId = useId();
  const pasteId = useId();
  const pasteErrorId = useId();
  const pasteReasonId = useId();
  const currencies = sampleCurrencies(definition, typedCurrency);
  const allowed = can('rules:simulate') && can('rules:read');
  const masked = maskedInputs(definition).size > 0;
  const columns = [t('sim.col.name'), ...definition.inputs.map((input) => clauseLabel(input, locale))].join(', ');

  // Each view change moves the focus to its title, so a keyboard user starts where the new view starts.
  const focusTitle = useRef(false);
  useEffect(() => {
    if (!focusTitle.current) return;
    focusTitle.current = false;
    heading.current?.focus();
  }, [view]);
  const show = (next: View) => { focusTitle.current = true; setView(next); };

  if (version.status !== 'DRAFT') return <div className="simrun"><EmptyNote text="sim.draftsOnly" /></div>;
  if (definition.rules.length === 0) {
    // The way out of an empty draft is to add rows to it; shown only to someone who may edit it.
    const mayEdit = editBlock(version, session) === undefined;
    return (
      <div className="simrun">
        <EmptyNote text="sim.noRows" />
        {mayEdit ? <div className="simrun__actions simrun__actions--center"><button type="button" className="btn" onClick={onEdit}><span aria-hidden="true">✎</span> {t('editor.edit')}</button></div> : null}
      </div>
    );
  }

  const send = (samples: Run) => { setRun(samples); simulate.mutate(samples.samples); };
  const start = (event: FormEvent) => {
    event.preventDefault();
    if (!allowed || simulate.isPending || rows.every(isBlank)) return;
    const samples: SimulationSample[] = [];
    for (const row of rows) {
      const built = buildSample(definition, row.values, currencies, locale);
      if (!built.ok) {
        setInvalid({ key: row.key, field: built.field });
        return;
      }
      const label = row.label.trim();
      samples.push(label === '' ? { inputs: built.inputs } : { label, inputs: built.inputs });
    }
    setInvalid(undefined);
    send({ rows, currencies, samples });
    show('results');
  };
  const addPasted = (event: FormEvent) => {
    event.preventDefault();
    const read = readPastedRows(pasted, definition, currencies, locale, (input: InputClause) => clauseLabel(input, locale), t('sim.col.name'));
    if (!read.ok) {
      setPasteProblem(pasteMessage(read.problem, t, columns));
      return;
    }
    const kept = rows.filter((row) => !isBlank(row));
    setRows([...kept, ...read.rows.map((row) => ({ key: nextKey.current++, label: row.label, values: row.values }))]);
    setPasted('');
    setPasteProblem(undefined);
    show('samples');
  };

  if (view === 'paste') {
    return (
      <form className="simrun" onSubmit={addPasted} aria-labelledby="simrun-title">
        <div className="simrun__head">
          <div className="simrun__titles">
            <h2 id="simrun-title" ref={heading} tabIndex={-1} className="simrun__title-text">{t('sim.paste.title')}</h2>
            <p className="simrun__sub">{t('sim.paste.sub')}</p>
          </div>
          <div className="simrun__actions">
            <button type="button" className="btn btn--ghost" onClick={() => { setPasteProblem(undefined); show('samples'); }}>{t('sim.paste.cancel')}</button>
            <button type="submit" className="btn btn--primary simrun__run" disabled={pasted.trim() === ''} aria-describedby={pasted.trim() === '' ? pasteReasonId : undefined}>{t('sim.paste.add')}</button>
          </div>
        </div>
        {pasted.trim() === '' ? <p id={pasteReasonId} className="simrun__reason">{t('sim.paste.reason')}</p> : null}
        <div className="field">
          <label className="field__label" htmlFor={pasteId}>{t('sim.paste.label')}</label>
          <textarea id={pasteId} className="field__input simrun__paste tnum" rows={8} spellCheck={false} value={pasted}
            aria-invalid={pasteProblem !== undefined} aria-describedby={pasteProblem !== undefined ? pasteErrorId : undefined}
            onChange={(e) => { setPasted(e.target.value); setPasteProblem(undefined); }} />
          <p className="simrun__hint">{t('sim.paste.columnsHint', { columns })}</p>
          {pasteProblem !== undefined ? <p id={pasteErrorId} className="cell-error" role="alert"><span aria-hidden="true">✕</span> {pasteProblem}</p> : null}
        </div>
      </form>
    );
  }

  if (view === 'results' && run !== undefined) {
    const failure = simulate.isError ? failureOf(simulate.error) : undefined;
    const answer = simulate.data;
    const changed = answer?.results.filter((result) => result.changed).length ?? 0;
    let title = failure !== undefined ? t('sim.results.failed') : t('sim.results.title');
    if (answer !== undefined && answer.results.length === 1) title = changed === 1 ? t('sim.results.oneChanged') : t('sim.results.oneSame');
    else if (answer !== undefined) title = changed === 0 ? t('sim.results.none', { count: String(answer.results.length) }) : t('sim.results.summary', { changed: String(changed), count: String(answer.results.length) });
    return (
      <section className="simrun" aria-labelledby="simrun-title">
        <div className="simrun__head">
          <div className="simrun__titles">
            <h2 id="simrun-title" ref={heading} tabIndex={-1} className="simrun__title-text tnum">{title}</h2>
            <p className="simrun__sub">{t('sim.results.sub')}</p>
          </div>
          <div className="simrun__actions">
            {answer !== undefined ? <ReadingStyleSwitch /> : null}
            {failure !== 'forbidden' ? (
              <button type="button" className="btn" onClick={() => { simulate.reset(); show('samples'); }}>
                <span aria-hidden="true">←</span> {t('sim.changeSamples')}
              </button>
            ) : null}
          </div>
        </div>
        <div role="status" aria-live="polite" className="simrun__body">
          {simulate.isPending ? <LoadingRows rows={Math.min(run.samples.length, 5)} /> : null}
          {failure === 'forbidden' ? <ForbiddenNote text="sim.forbidden" /> : null}
          {failure === 'too-large' ? <TooLargeNote text="readings.tooLarge.sim" /> : null}
          {failure === 'refused' ? <ProblemNotice error={simulate.error} /> : null}
          {failure === 'other' ? <AreaError error={simulate.error} onRetry={() => { send(run); }} /> : null}
          {answer !== undefined ? <Results answer={answer} run={run} definition={definition} /> : null}
          {answer !== undefined && masked ? <MaskedNote /> : null}
        </div>
      </section>
    );
  }

  const blank = rows.every(isBlank);
  const reason: MessageKey | undefined = !allowed ? 'sim.noPermission' : blank ? 'sim.fillOne' : undefined;
  const invalidRow = invalid !== undefined ? rows.findIndex((row) => row.key === invalid.key) : -1;
  const invalidInput = invalid !== undefined ? definition.inputs.find((input) => input.name === invalid.field) : undefined;
  return (
    <form className="simrun" onSubmit={start} aria-labelledby="simrun-title" noValidate>
      <div className="simrun__head">
        <div className="simrun__titles">
          <h2 id="simrun-title" ref={heading} tabIndex={-1} className="simrun__title-text">{t('sim.samples.title')}</h2>
          <p className="simrun__sub">{t('sim.samples.sub')}</p>
        </div>
        <div className="simrun__actions">
          <button type="button" className="btn btn--ghost" onClick={() => { show('paste'); }}>{t('sim.paste.open')}</button>
          <button type="button" className="btn btn--ghost" onClick={() => { setRows((current) => [...current, emptyRow(nextKey.current++)]); }}>
            <span aria-hidden="true">+</span> {t('sim.add')}
          </button>
          <button type="submit" className="btn btn--primary simrun__run" disabled={reason !== undefined || simulate.isPending} aria-busy={simulate.isPending}
            aria-describedby={reason !== undefined ? reasonId : undefined}>
            {t('sim.runAll')}
          </button>
        </div>
      </div>
      {reason !== undefined ? <p id={reasonId} className="simrun__reason"><span aria-hidden="true">⊘</span> {t(reason)}</p> : null}
      <SamplesGrid definition={definition} rows={rows} invalid={invalid} invalidId={invalidId}
        onRow={(key, change) => {
          setRows((current) => current.map((row) => {
            if (row.key !== key) return row;
            if (change.field !== undefined) return { ...row, values: { ...row.values, [change.field[0]]: change.field[1] } };
            return { ...row, label: change.label ?? row.label };
          }));
          if (invalid?.key === key) setInvalid(undefined);
        }}
        onCurrency={(input, code) => { setTypedCurrency((current) => ({ ...current, [input]: code.toUpperCase() })); }}
        onRemove={(key) => { setRows((current) => current.filter((row) => row.key !== key)); if (invalid?.key === key) setInvalid(undefined); }} />
      {invalid !== undefined && invalidInput !== undefined ? (
        <p id={invalidId} className="cell-error simrun__invalid" role="alert">
          <span aria-hidden="true">✕</span> {t(invalidInput.type === 'money' ? 'sim.invalid.amount' : 'sim.invalid.number', { sample: referenceOf(invalidRow), column: clauseLabel(invalidInput, locale), example: locale === 'vi' ? '60.000.000 VND' : '60,000,000 VND' })}
        </p>
      ) : null}
      <div className="simrun__foot">
        <p className="simrun__hint">{t('sim.nameHint')}</p>
        {masked ? <p className="simrun__hint"><span aria-hidden="true">◐</span> {t('sim.maskedHint')}</p> : null}
      </div>
    </form>
  );
}
