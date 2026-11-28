import { useId, useState, type FormEvent, type ReactNode } from 'react';
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

export type Built = { ok: true; inputs: Record<string, unknown> } | { ok: false; field: string };

/**
 * The inputs of one sample as the engine takes them: money as `{ currency, amount }` in minor units, whole numbers,
 * yes or no, lists. A blank field is left out (any value). The first field that cannot be sent is named instead.
 */
export function buildSample(definition: RuleSetDefinition, values: Readonly<Record<string, string>>, currencies: Readonly<Record<string, string>>, locale: Locale): Built {
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

/** The currency each money input is sent in: the one its cells name, else the one the user typed. */
export function sampleCurrencies(definition: RuleSetDefinition, typed: Readonly<Record<string, string>>): Record<string, string> {
  const currencies: Record<string, string> = {};
  for (const input of definition.inputs) {
    const known = currencyOfInput(definition, input.name);
    if (known.kind === 'known') currencies[input.name] = known.code;
    else if (known.kind === 'unknown') currencies[input.name] = typed[input.name] ?? '';
  }
  return currencies;
}

export interface SampleControlProps {
  input: InputClause;
  value: string;
  currency: InputCurrency;
  invalid: boolean;
  onChange: (text: string) => void;
  onCurrency: (code: string) => void;
  /** The field's id, for a visible label. */
  id?: string | undefined;
  /** The field's name when no visible label points at it, as in a grid cell. */
  ariaLabel?: string | undefined;
  describedBy?: string | undefined;
}

/**
 * The control of one sample input. A masked input is a password field: its value shows as dots and the browser
 * neither remembers nor checks its spelling. Money groups its digits while typing and carries its currency.
 */
export function SampleControl({ input, value, currency, invalid, onChange, onCurrency, id, ariaLabel, describedBy }: SampleControlProps) {
  const { t, locale } = useI18n();
  const label = clauseLabel(input, locale);
  const common = { id, className: 'field__input', 'aria-invalid': invalid, 'aria-label': ariaLabel, 'aria-describedby': describedBy };
  if (input.mask === true) {
    return <input {...common} type="password" autoComplete="off" spellCheck={false} placeholder={t('try.anyPlaceholder')} value={value} onChange={(e) => { onChange(e.target.value); }} />;
  }
  if (input.allowed || input.type === 'boolean') {
    const options = input.type === 'boolean' ? [['true', t('table.yes')], ['false', t('table.no')]] : (input.allowed ?? []).map((v) => [String(v), valueLabel(String(v))]);
    return (
      <select {...common} value={value} onChange={(e) => { onChange(e.target.value); }}>
        <option value="">{t('try.anyPlaceholder')}</option>
        {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
      </select>
    );
  }
  if (input.type === 'money' && currency.kind === 'mixed') {
    // The rule set compares this input in more than one currency: the panel cannot choose for the user.
    return <p className="cell-error">{t('try.mixedCurrency')}</p>;
  }
  if (input.type === 'money') {
    const name = ariaLabel ?? label;
    return (
      <span className="money-input">
        <input {...common} inputMode="decimal" className="field__input tnum" placeholder={t('try.anyPlaceholder')} value={value} onChange={(e) => { onChange(groupWhileTyping(e.target.value, locale)); }} />
        {currency.kind === 'known' ? (
          <span className="money-input__code" aria-label={t('try.currency', { name })}>{currency.code}</span>
        ) : (
          <input className="money-input__code money-input__code--edit" aria-label={t('try.currency', { name })} maxLength={3} placeholder="VND"
            onChange={(e) => { onCurrency(e.target.value); }} />
        )}
      </span>
    );
  }
  return <input {...common} type={input.type === 'date' ? 'date' : 'text'} inputMode={input.type === 'integer' ? 'numeric' : undefined} placeholder={t('try.anyPlaceholder')} value={value} onChange={(e) => { onChange(e.target.value); }} />;
}

function Field({ input, value, currency, invalid, onChange, onCurrency }: Omit<SampleControlProps, 'id' | 'ariaLabel' | 'describedBy'>) {
  const { t, locale } = useI18n();
  const id = useId();
  return (
    <div className="field">
      <label className="field__label" htmlFor={id}>{clauseLabel(input, locale)}</label>
      <SampleControl id={id} input={input} value={value} currency={currency} invalid={invalid} onChange={onChange} onCurrency={onCurrency} />
      {invalid ? <span className="cell-error">{t('try.invalidAmount', { example: locale === 'vi' ? '60.000.000' : '60,000,000' })}</span> : null}
    </div>
  );
}

/**
 * The sample form of Try it and Simulate: one field per input, money with separators and its currency. `read`
 * gives the inputs to send, or marks the first field that cannot be sent and gives undefined.
 */
export function useSampleForm(definition: RuleSetDefinition): { fields: ReactNode; read: () => Record<string, unknown> | undefined; describe: () => string } {
  const { locale } = useI18n();
  const [values, setValues] = useState<Record<string, string>>({});
  const [invalid, setInvalid] = useState<string | undefined>(undefined);
  const [typedCurrency, setTypedCurrency] = useState<Record<string, string>>({});
  const currencies = sampleCurrencies(definition, typedCurrency);
  const fields = definition.inputs.map((input) => (
    <Field key={input.name} input={input} currency={currencyOfInput(definition, input.name)} value={values[input.name] ?? ''} invalid={invalid === input.name}
      onChange={(text) => { setValues((current) => ({ ...current, [input.name]: text })); }}
      onCurrency={(code) => { setTypedCurrency((current) => ({ ...current, [input.name]: code })); }} />
  ));
  const read = () => {
    const built = buildSample(definition, values, currencies, locale);
    if (!built.ok) {
      setInvalid(built.field);
      return undefined;
    }
    setInvalid(undefined);
    return built.inputs;
  };
  // The sample as the user typed it, for naming it beside a result. A masked input is never written out.
  const describe = () => definition.inputs
    .filter((input) => (values[input.name] ?? '').trim() !== '')
    .map((input) => {
      const text = (values[input.name] ?? '').trim();
      const code = currencies[input.name]?.trim().toUpperCase();
      const shown = input.mask === true ? '••••' : input.type === 'money' && code ? `${text} ${code}` : text;
      return `${clauseLabel(input, locale)}: ${shown}`;
    })
    .join(', ');
  return { fields, read, describe };
}

/** Evaluate sample inputs against the version in effect and show the matched row and outputs. */
export function TryItPanel({ version, onMatched }: { version: RuleSetVersion; onMatched: (ruleIds: string[]) => void }) {
  const { t, locale } = useI18n();
  const { can } = useRulesSession();
  const evaluate = useEvaluate(version.ruleSet, (answer) => { onMatched(answer.matchedRuleIds); });
  const definition = version.definition;
  const form = useSampleForm(definition);

  if (!can('rules:evaluate')) {
    return (
      <div className="try">
        <p className="dock__note">{t('try.noPermission')}</p>
      </div>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const inputs = form.read();
    if (inputs !== undefined) evaluate.start(inputs);
  };
  const result = evaluate.data;

  return (
    <div className="try">
      <p className="dock__hint">{version.status === 'PUBLISHED' ? t('try.hint') : t('try.draftNote')}</p>
      <form className="try__form" onSubmit={submit}>
        {form.fields}
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
              <p className="status status--accent">{t('try.inEffect', { version: String(result.version) })}</p>
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
