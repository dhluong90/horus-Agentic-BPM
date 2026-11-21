import { useId, useState, type FormEvent } from 'react';
import type { InputClause, RuleSetDefinition, RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { useEvaluate } from '../api/useRules';
import { groupWhileTyping, toMinorUnits } from '../model/money';
import { valueLabel } from '../model/cells';
import { clauseLabel, readResult } from '../model/reading';
import { CopyButton } from '../../../components/CopyButton';
import { ValueText } from './CellValue';
import { ProblemNotice } from './ProblemNotice';

/**
 * The currency of a money input, read from that input's own condition cells only (an output's literal never
 * decides it): unknown when no cell names one, mixed when the cells disagree.
 */
export type InputCurrency = { kind: 'known'; code: string } | { kind: 'unknown' } | { kind: 'mixed' };

export function currencyOfInput(definition: RuleSetDefinition, input: string): InputCurrency {
  const found = new Set<string>();
  for (const rule of definition.rules) {
    const cell = rule.when[input];
    if (typeof cell !== 'string') continue;
    for (const match of cell.matchAll(/\d\s+([A-Z]{3})\b/g)) if (match[1]) found.add(match[1]);
  }
  const [code] = [...found];
  if (found.size > 1) return { kind: 'mixed' };
  return code === undefined ? { kind: 'unknown' } : { kind: 'known', code };
}

type Built = { ok: true; inputs: Record<string, unknown> } | { ok: false; field: string };

function build(definition: RuleSetDefinition, values: Record<string, string>, currencies: Readonly<Record<string, string>>, locale: Locale): Built {
  const inputs: Record<string, unknown> = {};
  for (const input of definition.inputs) {
    const text = (values[input.name] ?? '').trim();
    if (text === '') continue;
    if (input.type === 'money') {
      const currency = (currencies[input.name] ?? '').trim().toUpperCase();
      if (!/^[A-Z]{3}$/.test(currency)) return { ok: false, field: input.name };
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

function Field({ input, value, currency, invalid, onChange, onCurrency }: {
  input: InputClause;
  value: string;
  currency: InputCurrency;
  invalid: boolean;
  onChange: (text: string) => void;
  onCurrency: (code: string) => void;
}) {
  const { t, locale } = useI18n();
  const id = useId();
  const label = clauseLabel(input, locale);
  const common = { id, className: 'field__input', 'aria-invalid': invalid };
  let control;
  if (input.allowed || input.type === 'boolean') {
    const options = input.type === 'boolean' ? [['true', t('table.yes')], ['false', t('table.no')]] : (input.allowed ?? []).map((v) => [String(v), valueLabel(String(v))]);
    control = (
      <select {...common} value={value} onChange={(e) => { onChange(e.target.value); }}>
        <option value="">{t('try.anyPlaceholder')}</option>
        {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
      </select>
    );
  } else if (input.type === 'money' && currency.kind === 'mixed') {
    // The rule set compares this input in more than one currency: the panel cannot choose for the user.
    control = <p className="cell-error">{t('try.mixedCurrency')}</p>;
  } else if (input.type === 'money') {
    control = (
      <span className="money-input">
        <input {...common} inputMode="decimal" className="field__input tnum" placeholder={t('try.anyPlaceholder')} value={value} onChange={(e) => { onChange(groupWhileTyping(e.target.value, locale)); }} />
        {currency.kind === 'known' ? (
          <span className="money-input__code" aria-label={t('try.currency', { name: label })}>{currency.code}</span>
        ) : (
          <input className="money-input__code money-input__code--edit" aria-label={t('try.currency', { name: label })} maxLength={3} placeholder="VND"
            onChange={(e) => { onCurrency(e.target.value); }} />
        )}
      </span>
    );
  } else {
    control = <input {...common} type={input.type === 'date' ? 'date' : 'text'} inputMode={input.type === 'integer' ? 'numeric' : undefined} placeholder={t('try.anyPlaceholder')} value={value} onChange={(e) => { onChange(e.target.value); }} />;
  }
  return (
    <div className="field">
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
  const [typedCurrency, setTypedCurrency] = useState<Record<string, string>>({});
  const definition = version.definition;
  const currencyFor = (name: string) => currencyOfInput(definition, name);
  const currencies: Record<string, string> = {};
  for (const input of definition.inputs) {
    const known = currencyFor(input.name);
    if (known.kind === 'known') currencies[input.name] = known.code;
    else if (known.kind === 'unknown') currencies[input.name] = typedCurrency[input.name] ?? '';
  }

  if (!can('rules:evaluate')) {
    return (
      <div className="try">
        <p className="dock__note">{t('try.noPermission')}</p>
      </div>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const built = build(definition, values, currencies, locale);
    if (!built.ok) {
      setInvalid(built.field);
      return;
    }
    setInvalid(undefined);
    evaluate.start(built.inputs);
  };
  const result = evaluate.data;

  return (
    <div className="try">
      <p className="dock__hint">{version.status === 'PUBLISHED' ? t('try.hint') : t('try.draftNote')}</p>
      <form className="try__form" onSubmit={submit}>
        {definition.inputs.map((input) => (
          <Field key={input.name} input={input} currency={currencyFor(input.name)} value={values[input.name] ?? ''} invalid={invalid === input.name}
            onChange={(text) => { setValues((current) => ({ ...current, [input.name]: text })); }}
            onCurrency={(code) => { setTypedCurrency((current) => ({ ...current, [input.name]: code })); }} />
        ))}
        <button type="submit" className="btn btn--primary try__run" disabled={evaluate.isPending} aria-busy={evaluate.isPending}>
          <span aria-hidden="true">▷</span> {evaluate.isPending ? t('try.running') : t('try.run')}
        </button>
      </form>
      {evaluate.isError ? <ProblemNotice error={evaluate.error} /> : null}
      <div role="status" aria-live="polite">
        {result ? (
          <div className="try__result">
            <div className="try__outcome">
              <p className={result.outcome === 'MATCHED' ? 'try__matched' : 'try__nomatch'}>
                <span aria-hidden="true">{result.outcome === 'MATCHED' ? '✓' : '✕'}</span>{' '}
                {result.outcome === 'MATCHED' ? t('try.matchedRow', { rows: result.matchedRuleIds.join(', ') }) : t('try.noMatch')}
              </p>
              <p className="badge badge--accent">{t('try.inEffect', { version: String(result.version) })}</p>
            </div>
            <dl className="try__tiles">
              {definition.outputs.filter((o) => o.name in result.outputs).map((output) => (
                <div key={output.name} className="try__tile">
                  <dt>{clauseLabel(output, locale)}</dt>
                  <dd className="tnum"><ValueText value={readResult(result.outputs[output.name], locale)} /></dd>
                </div>
              ))}
            </dl>
            <details className="try__details">
              <summary className="hit">{t('try.details')}</summary>
              <p className="facts__with-action">
                <span className="dock__hint">{t('try.decision')}</span>
                <span className="mono">{result.decisionId}</span>
                <CopyButton value={result.decisionId} />
              </p>
            </details>
          </div>
        ) : null}
      </div>
    </div>
  );
}
