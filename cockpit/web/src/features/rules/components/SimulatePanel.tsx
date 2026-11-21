import { useId, useState, type FormEvent } from 'react';
import type { OutcomeReading, RuleSetVersion, SimulationReadings } from '../../../api/rules/schemas';
import { AreaError } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { useSimulateReadings } from '../api/useRules';
import { EmptyNote, ForbiddenNote, MaskedNote, ReadingStyleSwitch, TooLargeNote, failureOf, maskedInputs, readingIn, useViewerReadingStyle } from './ReadingParts';
import { useSampleForm } from './TryItPanel';

function Side({ title, outcome, base }: { title: string; outcome: OutcomeReading; base: boolean }) {
  const { locale } = useI18n();
  const [style] = useViewerReadingStyle();
  return (
    <section className={base ? 'sim__side sim__side--base' : 'sim__side'}>
      <h3 className="sim__label">{title}</h3>
      <p className="reading__text tnum">{readingIn(outcome.readings, locale, style) ?? ''}</p>
    </section>
  );
}

function Result({ answer, version, sample }: { answer: SimulationReadings; version: RuleSetVersion; sample: string }) {
  const { t, locale } = useI18n();
  const result = answer.results[0];
  if (result === undefined) return null;
  if (!answer.languages.includes(locale)) return <EmptyNote text="readings.noLanguage" />;
  return (
    <div className="sim__result">
      {sample === '' && result.reference === undefined ? null : <p className="sim__sample">{t('sim.sample', { sample: [result.reference, result.label ?? sample].filter((part) => part !== undefined && part !== '').join(' · ') })}</p>}
      <p className={result.changed ? 'change-mark change-mark--changed sim__verdict' : 'badge sim__verdict'}>
        <span aria-hidden="true">{result.changed ? '✎' : '='}</span> {result.changed ? t('sim.changed') : t('sim.same')}
      </p>
      <Side title={t('sim.thisVersion', { version: String(answer.version) })} outcome={result.simulated} base={false} />
      {result.baseline !== null && answer.baselineVersion !== null
        ? <Side title={t('sim.inEffect', { version: String(answer.baselineVersion) })} outcome={result.baseline} base />
        : <p className="reading__note">{t('sim.noBaseline')}</p>}
      {maskedInputs(version.definition).size > 0 ? <MaskedNote /> : null}
    </div>
  );
}

/**
 * Runs the sample beside the version in effect and shows both outcomes as the engine reads them (RLE-093).
 * Nothing is logged. Without the permissions the button stays visible, disabled, with the reason beside it.
 */
export function SimulatePanel({ version }: { version: RuleSetVersion }) {
  const { t } = useI18n();
  const { can } = useRulesSession();
  const simulate = useSimulateReadings(version);
  const form = useSampleForm(version.definition);
  const allowed = can('rules:simulate') && can('rules:read');
  const reasonId = useId();
  // After a run the result takes the form's place, so it stays above the fold; the sample is kept for the next run.
  const [showForm, setShowForm] = useState(true);
  const [sample, setSample] = useState('');
  const run = () => {
    const inputs = form.read();
    const described = form.describe();
    if (inputs !== undefined) simulate.mutate(inputs, { onSuccess: () => { setSample(described); setShowForm(false); } });
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!allowed || simulate.isPending) return;
    run();
  };
  const failure = simulate.isError ? failureOf(simulate.error) : undefined;
  return (
    <div className="try sim">
      {showForm ? <p className="dock__hint">{t('sim.hint')}</p> : null}
      {showForm ? (
        <form className="try__form" onSubmit={submit}>
          {form.fields}
          <div className="sim__action">
          <button type="submit" className="btn btn--primary try__run sim__run" disabled={!allowed || simulate.isPending} aria-busy={simulate.isPending}
            aria-describedby={allowed ? undefined : reasonId}>
            {simulate.isPending ? t('sim.running') : t('sim.run')}
          </button>
          {!allowed ? <p id={reasonId} className="sim__reason">{t('sim.noPermission')}</p> : null}
          </div>
        </form>
      ) : (
        <div className="sim__head">
          <button type="button" className="btn btn--ghost sim__again" onClick={() => { setShowForm(true); }}>
            <span aria-hidden="true">←</span> {t('sim.changeSample')}
          </button>
          <ReadingStyleSwitch />
        </div>
      )}
      <div role="status" aria-live="polite">
        {failure === 'forbidden' ? <ForbiddenNote text="sim.forbidden" /> : null}
        {failure === 'too-large' ? <TooLargeNote text="readings.tooLarge.sim" /> : null}
        {failure === 'other' ? <AreaError error={simulate.error} onRetry={run} /> : null}
        {simulate.data !== undefined && !showForm ? <Result answer={simulate.data} version={version} sample={sample} /> : null}
      </div>
    </div>
  );
}
