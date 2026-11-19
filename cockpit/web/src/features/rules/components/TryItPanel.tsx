import { useId, useState, type FormEvent } from 'react';
import type { InputClause, RuleSetDefinition, RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { useEvaluate } from '../api/useRules';
import { groupWhileTyping, toMinorUnits } from '../model/money';
import { columnLabel, readResult } from '../model/reading';
import { ValueText } from './CellValue';
import { ProblemNotice } from './ProblemNotice';

/** The currency the rule set's money literals use, so the amount field can show it. */
function currencyOf(definition: RuleSetDefinition): string {
  const text = JSON.stringify(definition.rules);
  return /\d\s+([A-Z]{3})\b/.exec(text)?.[1] ?? '';
}

type Built = { ok: true; inputs: Record<string, unknown> } | { ok: false; field: string };

function build(definition: RuleSetDefinition, values: Record<string, string>, currency: string, locale: Locale): Built {
  const inputs: Record<string, unknown> = {};
  for (const input of definition.inputs) {
    const text = (values[input.name] ?? '').trim();
    if (text === '') continue;
    if (input.type === 'money') {
      const minor = toMinorUnits(text, currency, locale);
      if (!minor.ok) return { ok: false, field: input.name };
      inputs[input.name] = { currency, amount: minor.amount };
    } else if (input.type === 'boolean') inputs[input.name] = text === 'true';
    else if (input.type === 'integer') {
      const digits = text.replaceAll(locale === 'vi' ? '.' : ',', '');
      if (!/^-?\d+$/.test(digits) || !Number.isSafeInteger(Number(digits))) return { ok: false, field: input.name };
      inputs[input.name] = Number(digits);
    } else if (input.type.startsWith('list<')) inputs[input.name] = text.split(',').map((item) => item.trim()).filter(Boolean);
    else inputs[input.name] = text;
  }
  return { ok: true, inputs };
}

function Field({ input, value, currency, invalid, onChange }: { input: InputClause; value: string; currency: string; invalid: boolean; onChange: (text: string) => void }) {
  const { t, locale } = useI18n();
  const id = useId();
  const label = columnLabel(input.name, input.description);
  const common = { id, className: 'field__input', 'aria-invalid': invalid };
  let control;
  if (input.allowed || input.type === 'boolean') {
    const options = input.type === 'boolean' ? [['true', t('table.yes')], ['false', t('table.no')]] : (input.allowed ?? []).map((v) => [String(v), String(v)]);
    control = (
      <select {...common} value={value} onChange={(e) => { onChange(e.target.value); }}>
        <option value="">{t('try.notSet')}</option>
        {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
      </select>
    );
  } else if (input.type === 'money') {
    control = (
      <span className="money-input">
        <input {...common} inputMode="decimal" className="field__input tnum" value={value} onChange={(e) => { onChange(groupWhileTyping(e.target.value, locale)); }} />
        <span className="money-input__code" aria-label={t('try.currency', { name: label })}>{currency}</span>
      </span>
    );
  } else {
    control = <input {...common} type={input.type === 'date' ? 'date' : 'text'} inputMode={input.type === 'integer' ? 'numeric' : undefined} value={value} onChange={(e) => { onChange(e.target.value); }} />;
  }
  return (
    <div className={input.type === 'money' ? 'field field--wide' : 'field'}>
      <label className="field__label" htmlFor={id}>{label}</label>
      {control}
      {invalid ? <span className="cell-error">{t('try.invalidAmount', { example: locale === 'vi' ? '60.000.000' : '60,000,000' })}</span> : null}
    </div>
  );
}

/** Evaluate sample inputs against the version in effect and show the matched row and outputs. */
export function TryItPanel({ version, onMatched }: { version: RuleSetVersion; onMatched: (ruleIds: string[]) => void }) {
  const { t, locale } = useI18n();
  const { can } = useRulesSession();
  const evaluate = useEvaluate(version.ruleSet, (answer) => { onMatched(answer.matchedRuleIds); });
  const [values, setValues] = useState<Record<string, string>>({});
  const [invalid, setInvalid] = useState<string | undefined>(undefined);
  const definition = version.definition;
  const currency = currencyOf(definition);

  if (!can('rules:evaluate')) {
    return (
      <section className="card" aria-labelledby="try-title">
        <h2 id="try-title" className="card__title">{t('try.title')}</h2>
        <p className="dock__note">{t('try.noPermission')}</p>
      </section>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const built = build(definition, values, currency, locale);
    if (!built.ok) {
      setInvalid(built.field);
      return;
    }
    setInvalid(undefined);
    evaluate.start(built.inputs);
  };
  const result = evaluate.data;

  return (
    <section className="card try" aria-labelledby="try-title">
      <h2 id="try-title" className="card__title">{t('try.title')}</h2>
      <p className="dock__hint">{version.status === 'PUBLISHED' ? t('try.hint') : t('try.draftNote')}</p>
      <form className="try__form" onSubmit={submit}>
        {definition.inputs.map((input) => (
          <Field key={input.name} input={input} currency={currency} value={values[input.name] ?? ''} invalid={invalid === input.name}
            onChange={(text) => { setValues((current) => ({ ...current, [input.name]: text })); }} />
        ))}
        <button type="submit" className="btn btn--primary" disabled={evaluate.isPending}>{evaluate.isPending ? t('try.running') : t('try.run')}</button>
      </form>
      {evaluate.isError ? <ProblemNotice error={evaluate.error} /> : null}
      <div role="status" aria-live="polite">
        {result ? (
          <div className="try__result">
            <p className={result.outcome === 'MATCHED' ? 'badge badge--ok' : 'badge badge--muted'}>
              <span aria-hidden="true">{result.outcome === 'MATCHED' ? '✓' : '✕'}</span>{' '}
              {result.outcome === 'MATCHED' ? t('try.matched', { rows: result.matchedRuleIds.join(', ') }) : t('try.noMatch')}
            </p>
            <dl className="dock__facts">
              {definition.outputs.filter((o) => o.name in result.outputs).map((output) => (
                <div key={output.name}>
                  <dt>{columnLabel(output.name, output.description)}</dt>
                  <dd className="tnum"><ValueText value={readResult(result.outputs[output.name], locale)} /></dd>
                </div>
              ))}
            </dl>
            <p className="dock__hint">{t('try.version', { version: String(result.version) })} · <span className="mono">{result.decisionId}</span></p>
          </div>
        ) : null}
      </div>
    </section>
  );
}
