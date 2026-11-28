import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { toHostError } from '../../../api/hostError';
import type { HistoryReadingResult, HistoryReadings, RuleSetDefinition, RuleSetVersion } from '../../../api/rules/schemas';
import { AreaError, LoadingRows } from '../../../components/PageParts';
import { TintTiles } from '../../../components/TintTiles';
import type { MessageKey } from '../../../i18n/en';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { useReplay } from '../api/useRules';
import { formatDateTime } from '../model/dates';
import { clauseLabel, columnLabel } from '../model/reading';
import { countGroups, DEFAULT_CAP, groupOf, parseDecisionIds, presetDays, replayRequest, shortId, validDecisionIds, type Preset, type ReplayForm, type ReplayGroup } from '../model/replay';
import { EmptyNote, ForbiddenNote, MaskedNote, ReadingStyleSwitch, TooLargeNote, readingIn, useViewerReadingStyle } from './ReadingParts';
import { ProblemNotice } from './ProblemNotice';
import { formatCount } from './ReportParts';

/*
 * The replay screen of a draft (CK-F-05, RLE-202): the author picks a period and how many decisions, replays what the
 * service decided then against the draft, and reads which outcomes would change, each in the engine's own words. The
 * answer holds no logged input: a masked input is named, never shown. When the service cannot replay, its reason is
 * said in plain words. One view at a time: the period, or the result.
 */

const PRESETS: readonly Preset[] = ['week', 'thirty', 'lastMonth'];

type Failure = 'cancelled' | 'forbidden' | 'too-large' | 'period' | 'missing' | 'refused' | 'other';

function failureOf(error: unknown): Failure {
  if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled';
  const host = toHostError(error);
  if (host.kind !== 'problem') return 'other';
  if (host.status === 403) return 'forbidden';
  if (host.code === 'READINGS_TOO_LARGE' || host.status === 413) return 'too-large';
  if (host.status === 400 && (host.code === 'QUERY_INVALID' || host.code === 'INPUT_INVALID')) return 'period';
  if (host.status === 404) return 'missing';
  if (host.status >= 400 && host.status < 500) return 'refused';
  return 'other';
}

/** A day of the form ("2026-11-19") as the reader's date, without a time zone shift. */
function formatDay(day: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { day: 'numeric', month: locale === 'vi' ? 'numeric' : 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${day}T00:00:00Z`));
}

function Reading({ outcome }: { outcome: NonNullable<HistoryReadingResult['simulated']> }) {
  const { locale } = useI18n();
  const [style] = useViewerReadingStyle();
  return <p className="reading__text tnum">{readingIn(outcome.readings, locale, style) ?? ''}</p>;
}

/** Why a decision was not replayed, in words: the masked inputs by their names, never a value. */
function useNotReplayed(definition: RuleSetDefinition) {
  const { t, locale } = useI18n();
  return (result: HistoryReadingResult): string => {
    if (result.maskedInputs.length > 0) {
      const names = result.maskedInputs.map((name) => {
        const input = definition.inputs.find((candidate) => candidate.name === name);
        return input !== undefined ? clauseLabel(input, locale) : columnLabel(name, undefined);
      });
      return t('replay.notPossible.masked', { inputs: names.join(', ') });
    }
    return t('replay.notPossible.other', { version: String(result.loggedVersion) });
  };
}

function Results({ answer, definition, from, to }: { answer: HistoryReadings; definition: RuleSetDefinition; from: string; to: string }) {
  const { t, locale } = useI18n();
  const reasonOf = useNotReplayed(definition);
  const counts = countGroups(answer.results);
  // Start on what changed; when nothing did, on every decision, so the first view is never an empty group.
  const [filter, setFilter] = useState<ReplayGroup | 'all'>(counts.changed > 0 ? 'changed' : 'all');
  const total = answer.results.length;
  const replayed = counts.changed + counts.unchanged;
  const shown = answer.results.filter((result) => filter === 'all' || groupOf(result) === filter);
  const masked = answer.results.some((result) => result.maskedInputs.length > 0);
  const tiles = [
    { key: 'changed', value: formatCount(counts.changed, locale), label: t('replay.tile.changed'), tint: 'lime' as const },
    { key: 'unchanged', value: formatCount(counts.unchanged, locale), label: t('replay.tile.unchanged'), tint: 'mint' as const },
    { key: 'notReplayable', value: formatCount(counts.notReplayable, locale), label: t('replay.tile.notReplayable'), tint: counts.notReplayable > 0 ? 'amber' as const : 'neutral' as const },
  ];
  const share = replayed === 0 ? 0 : (counts.changed / replayed) * 100;
  const filters: readonly (ReplayGroup | 'all')[] = ['changed', 'unchanged', 'notReplayable', 'all'];
  const count = (group: ReplayGroup | 'all') => (group === 'all' ? total : counts[group]);
  if (!answer.languages.includes(locale)) return <EmptyNote text="readings.noLanguage" />;
  if (total === 0) return <EmptyNote text="replay.empty" />;
  return (
    <>
      <TintTiles label={t('replay.tiles')} tiles={tiles} />
      <svg className="replay__bar" viewBox="0 0 100 8" preserveAspectRatio="none" role="img" aria-label={t('replay.bar', { changed: formatCount(counts.changed, locale), total: formatCount(replayed, locale) })}>
        <rect className="replay__bar-same" x="0" y="0" width="100" height="8" rx="4" />
        <rect className="replay__bar-changed" x="0" y="0" width={share} height="8" />
      </svg>
      <p className="simrun__hint">{t('replay.meta', { from: formatDay(from, locale), to: formatDay(to, locale) })}</p>
      {answer.truncated ? <p className="list-note" role="note">{t('replay.truncated', { count: formatCount(total, locale) })}</p> : null}
      <div className="replay__tools">
        <div className="seg" role="group" aria-label={t('replay.filter')}>
          {filters.map((option) => (
            <button key={option} type="button" aria-pressed={filter === option} onClick={() => { setFilter(option); }}>
              {t(`replay.filter.${option}`)} <span className="seg__count tnum">{formatCount(count(option), locale)}</span>
            </button>
          ))}
        </div>
        <ReadingStyleSwitch />
      </div>
      {shown.length === 0 ? <p className="reading__note" role="status">{t('replay.noMatch')}</p> : (
        <div className="dt-scroll">
          <table className="dt replay__table">
            <caption className="sr-only">{t('replay.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('replay.col.decision')}</th>
                <th scope="col">{t('replay.col.then')}</th>
                <th scope="col">{t('replay.col.now')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((result) => {
                const group = groupOf(result);
                return (
                  <tr key={result.decisionId} className={group === 'changed' ? 'replay__row replay__row--changed' : 'replay__row'}>
                    <th scope="row" className="replay__id">
                      <span className="tnum" title={result.decisionId}>{shortId(result.decisionId)}</span>
                      <span className="replay__when tnum">{formatDateTime(result.evalClock, locale, 'UTC')} · {t('replay.row.version', { version: String(result.loggedVersion) })}</span>
                      {group === 'changed' ? <span className="change-mark change-mark--changed"><span aria-hidden="true">✎</span> {t('replay.changed')}</span> : null}
                      {group === 'unchanged' ? <span className="status status--neutral"><span aria-hidden="true">=</span> {t('replay.same')}</span> : null}
                      {group === 'notReplayable' ? <span className="status status--warn"><span aria-hidden="true">◐</span> {t('replay.notPossible')}</span> : null}
                    </th>
                    {group === 'notReplayable' ? (
                      <td colSpan={2}><p className="reading__note replay__why">{reasonOf(result)}</p></td>
                    ) : (
                      <>
                        <td>{result.logged !== null ? <Reading outcome={result.logged} /> : null}</td>
                        <td>{group === 'unchanged' ? <p className="reading__note">{t('replay.sameResult')}</p> : result.simulated !== null ? <Reading outcome={result.simulated} /> : null}</td>
                      </>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {masked ? <MaskedNote /> : null}
    </>
  );
}

/** The Replay view of a draft. Without the permissions the period can still be set; Run stays visible, disabled, with the reason. */
export function ReplayScreen({ version }: { version: RuleSetVersion }) {
  const { t, locale } = useI18n();
  const session = useRulesSession();
  const replay = useReplay(version);
  const [view, setView] = useState<'period' | 'result'>('period');
  const [form, setForm] = useState<ReplayForm>(() => ({ ...presetDays('thirty', Date.now()), cap: String(DEFAULT_CAP), ids: '' }));
  const [preset, setPreset] = useState<Preset | undefined>('thirty');
  const [sent, setSent] = useState<{ from: string; to: string } | undefined>(undefined);
  const heading = useRef<HTMLHeadingElement>(null);
  const focusTitle = useRef(false);
  const reasonId = useId();
  const fromId = useId();
  const toId = useId();
  const capId = useId();
  const capHintId = useId();
  const idsId = useId();
  const idsHintId = useId();
  const allowed = session.can('rules:simulate') && session.can('rules:log:read') && session.can('rules:read');
  const request = replayRequest(form);

  // Each view change moves the focus to its title, so a keyboard user starts where the new view starts.
  useEffect(() => {
    if (!focusTitle.current) return;
    focusTitle.current = false;
    heading.current?.focus();
  }, [view]);
  const show = (next: 'period' | 'result') => { focusTitle.current = true; setView(next); };

  if (version.status !== 'DRAFT' || !session.historyReadings) return <div className="simrun"><EmptyNote text="replay.draftsOnly" /></div>;

  const run = () => {
    if (request === undefined) return;
    setSent({ from: form.from, to: form.to });
    replay.mutate(request);
    show('result');
  };
  const start = (event: FormEvent) => {
    event.preventDefault();
    if (allowed && !replay.isPending) run();
  };
  const reason: MessageKey | undefined = !allowed ? 'replay.noPermission' : request === undefined ? 'replay.fillPeriod' : undefined;

  if (view === 'result' && sent !== undefined) {
    const failure = replay.isError ? failureOf(replay.error) : undefined;
    // A cancelled replay keeps the steps already read: they are real answers, shown as partial.
    const partial = failure === 'cancelled' ? replay.progress?.partial : undefined;
    const answer = replay.data ?? partial;
    const counts = answer !== undefined ? countGroups(answer.results) : undefined;
    let title = t('replay.title');
    if (replay.isPending) title = t('replay.running');
    else if (failure === 'cancelled' && (partial === undefined || partial.results.length === 0)) title = t('replay.cancelled');
    else if (failure === 'cancelled' && partial !== undefined) title = t('replay.stopped', { count: formatCount(partial.results.length, locale) });
    else if (answer !== undefined && counts !== undefined) {
      const replayed = counts.changed + counts.unchanged;
      if (answer.results.length === 0) title = t('replay.summaryEmpty');
      else if (replayed === 0) title = t('replay.summaryNothing');
      else if (counts.changed === 0) title = t('replay.summaryNone');
      else title = t(counts.changed === 1 ? 'replay.summaryOne' : 'replay.summary', { changed: formatCount(counts.changed, locale), count: formatCount(replayed, locale) });
    } else if (failure !== undefined) title = t('replay.title');
    return (
      <section className="simrun" aria-labelledby="replay-title">
        <div className="simrun__head">
          <div className="simrun__titles">
            <h2 id="replay-title" ref={heading} tabIndex={-1} className="simrun__title-text tnum">{title}</h2>
            <p className="simrun__sub">{replay.isPending ? t('replay.runningSub') : failure === 'cancelled' ? t(partial !== undefined && partial.results.length > 0 ? 'replay.stoppedSub' : 'replay.cancelledSub') : t('replay.sub')}</p>
          </div>
          <div className="simrun__actions">
            {replay.isPending ? <button type="button" className="btn" onClick={replay.cancel}>{t('replay.cancel')}</button> : (
              <button type="button" className="btn" onClick={() => { replay.reset(); show('period'); }}><span aria-hidden="true">←</span> {t('replay.change')}</button>
            )}
          </div>
        </div>
        <div role="status" aria-live="polite" className="simrun__body">
          {replay.isPending ? <><div className="replay__progress" role="progressbar" aria-label={t('replay.running')}><span /></div>{replay.progress !== undefined ? <p className="simrun__hint tnum">{t('replay.progress', { done: String(replay.progress.done), total: String(replay.progress.total), count: formatCount(replay.progress.partial.results.length, locale) })}</p> : null}<LoadingRows rows={4} /></> : null}
          {failure === 'forbidden' ? <ForbiddenNote text="replay.forbidden" /> : null}
          {failure === 'too-large' ? <TooLargeNote text="replay.tooLarge" /> : null}
          {failure === 'period' ? <ProblemNotice error={replay.error} message={t('replay.refused.period')} /> : null}
          {failure === 'missing' ? <ProblemNotice error={replay.error} message={t('replay.refused.missing')} /> : null}
          {failure === 'refused' ? <ProblemNotice error={replay.error} /> : null}
          {failure === 'other' ? <AreaError error={replay.error} onRetry={run} /> : null}
          {answer !== undefined ? <Results answer={answer} definition={version.definition} from={sent.from} to={sent.to} /> : null}
        </div>
      </section>
    );
  }

  return (
    <form className="simrun" onSubmit={start} aria-labelledby="replay-title" noValidate>
      <div className="simrun__head">
        <div className="simrun__titles">
          <h2 id="replay-title" ref={heading} tabIndex={-1} className="simrun__title-text">{t('replay.title')}</h2>
          <p className="simrun__sub">{t('replay.sub')}</p>
        </div>
        <div className="simrun__actions">
          <button type="submit" className="btn btn--primary simrun__run" disabled={reason !== undefined} aria-describedby={reason !== undefined ? reasonId : undefined}>{t('replay.run')}</button>
        </div>
      </div>
      {reason !== undefined ? <p id={reasonId} className="simrun__reason"><span aria-hidden="true">⊘</span> {t(reason)}</p> : null}
      <div className="replay__form">
        <div className="field">
          <span className="field__label" id={`${fromId}-presets`}>{t('replay.period')}</span>
          <div className="seg" role="group" aria-labelledby={`${fromId}-presets`}>
            {PRESETS.map((option) => (
              <button key={option} type="button" aria-pressed={preset === option} onClick={() => { setPreset(option); setForm((current) => ({ ...current, ...presetDays(option, Date.now()) })); }}>
                {t(`replay.preset.${option}`)}
              </button>
            ))}
          </div>
        </div>
        <div className="field">
          <label className="field__label" htmlFor={fromId}>{t('replay.from')}</label>
          <input id={fromId} type="date" className="field__input" value={form.from} max={form.to === '' ? undefined : form.to} onChange={(e) => { setPreset(undefined); setForm({ ...form, from: e.target.value }); }} />
        </div>
        <div className="field">
          <label className="field__label" htmlFor={toId}>{t('replay.to')}</label>
          <input id={toId} type="date" className="field__input" value={form.to} min={form.from === '' ? undefined : form.from} onChange={(e) => { setPreset(undefined); setForm({ ...form, to: e.target.value }); }} />
        </div>
        <div className="field replay__ids">
          <label className="field__label" htmlFor={idsId}>{t('replay.ids')}</label>
          <textarea id={idsId} className="field__input tnum" rows={2} spellCheck={false} value={form.ids ?? ''} aria-describedby={idsHintId} aria-invalid={form.ids !== undefined && !validDecisionIds(parseDecisionIds(form.ids))} onChange={(e) => { setForm({ ...form, ids: e.target.value }); }} />
          <p id={idsHintId} className="simrun__hint">{t('replay.idsHint')}</p>
        </div>
        <div className="field">
          <label className="field__label" htmlFor={capId}>{t('replay.cap')}</label>
          <input id={capId} type="number" inputMode="numeric" min={1} className="field__input tnum replay__cap" value={form.cap} aria-describedby={capHintId} onChange={(e) => { setForm({ ...form, cap: e.target.value }); }} />
          <p id={capHintId} className="simrun__hint">{t('replay.capHint')}</p>
        </div>
      </div>
    </form>
  );
}
