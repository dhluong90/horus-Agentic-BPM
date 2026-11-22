import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { OPERATORS, valueLabel, writeConditionCell, writeOutputCell, type CellModel, type Operator } from '../model/cells';
import { groupWhileTyping } from '../model/money';

/*
 * Editors for one cell of the draft table. Each shows a value (a tier picker, a comparison and an amount
 * with its currency inside the field, Yes or No) and hands back the engine's text for the cell.
 */

export interface CellProps {
  /** Spoken name of the cell, for example "Row R2, Tier". */
  label: string;
  /** The column's name alone ("Balance"), for the hint of a wildcard cell. */
  column?: string;
  /** What to do about the cell's problem, in one line. */
  error: string | undefined;
  /** The engine's own sentence, shown under the line when the line is the cockpit's format hint. */
  detail?: string | undefined;
  errorId: string;
}

const DECIMAL: Record<Locale, string> = { en: '.', vi: ',' };
const GROUP: Record<Locale, string> = { en: ',', vi: '.' };

/** Canonical decimal text ("50000000.5") as the user sees it while typing ("50,000,000.5"). */
function shownAmount(canonical: string, locale: Locale): string {
  return groupWhileTyping(canonical.replace('.', DECIMAL[locale]), locale);
}

/** What the user typed back to canonical decimal text; anything but digits and one separator is dropped. */
function canonicalAmount(typed: string, locale: Locale): string {
  const plain = typed.split(GROUP[locale]).join('').replace(DECIMAL[locale], '.');
  return plain.replace(/[^\d.-]/g, '');
}

function ErrorLine({ error, detail, errorId }: Pick<CellProps, 'error' | 'detail' | 'errorId'>) {
  if (error === undefined) return null;
  return (
    <span id={errorId} className="cell-error">
      <span aria-hidden="true">✕</span> {error}
      {detail !== undefined ? <>{' '}<span className="cell-error__detail">{detail}</span></> : null}
    </span>
  );
}

function Frame({ error, detail, errorId, children }: Pick<CellProps, 'error' | 'detail' | 'errorId'> & { children: ReactNode }) {
  return (
    <div className={error !== undefined ? 'cell-edit cell-edit--bad' : 'cell-edit'}>
      {children}
      <ErrorLine error={error} detail={detail} errorId={errorId} />
    </div>
  );
}

/**
 * A wildcard: a quiet dashed "—" that becomes a condition when chosen. On hover and focus it says what the dash means
 * for this column ("Balance: any value"), so a wildcard never reads as a cell that cannot be edited.
 */
function Wildcard({ label, column, onStart, describedBy }: { label: string; column: string | undefined; onStart: () => void; describedBy: string | undefined }) {
  const { t } = useI18n();
  const hint = column !== undefined ? t('editor.anyOf', { column }) : t('editor.anyValue');
  return (
    <button type="button" className="cell-any-edit" aria-label={t('editor.anyCell', { cell: label })} title={hint}
      {...(describedBy !== undefined ? { 'aria-describedby': describedBy } : {})} onClick={onStart}>
      <span aria-hidden="true">—</span>
      <span aria-hidden="true" className="cell-any-edit__hint">{hint}</span>
    </button>
  );
}

export function ChoiceEditor({ label, error, detail, errorId, model, options, onText }: CellProps & { model: CellModel; options: readonly string[]; onText: (text: string) => void }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const values = model.kind === 'choice' ? model.values : [];
  const negate = model.kind === 'choice' && model.negate === true;
  useEffect(() => {
    if (!open) return;
    box.current?.querySelector<HTMLInputElement>('input')?.focus();
    const close = (event: MouseEvent) => { if (!box.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => { document.removeEventListener('mousedown', close); };
  }, [open]);
  const toggle = (value: string) => {
    const next = values.includes(value) ? values.filter((v) => v !== value) : options.filter((o) => o === value || values.includes(o));
    onText(writeConditionCell({ kind: 'choice', values: next, ...(negate ? { negate: true as const } : {}) }));
  };
  const flip = () => { onText(writeConditionCell({ kind: 'choice', values, ...(negate ? {} : { negate: true as const }) })); };
  const describedBy = error !== undefined ? errorId : undefined;
  return (
    <Frame error={error} detail={detail} errorId={errorId}>
      <span className="picker">
        <button ref={trigger} type="button" className={values.length === 0 ? 'picker__trigger picker__trigger--any' : 'picker__trigger'}
          aria-label={values.length === 0 ? label : `${label}: ${negate ? `${t('editor.notThese')} ${values.map(valueLabel).join(' ')}` : values.map(valueLabel).join(', ')}`}
          aria-haspopup="true" aria-expanded={open} aria-invalid={error !== undefined} {...(describedBy !== undefined ? { 'aria-describedby': describedBy } : {})}
          onClick={() => { setOpen((v) => !v); }}>
          {values.length === 0 ? <span aria-hidden="true">—</span> : negate ? <><span className="picker__not">{t('editor.notThese')}</span>{' '}</> : null}
          {values.map((v) => <span key={v} className="value-pill">{valueLabel(v)}</span>)}
          <span aria-hidden="true" className="picker__chevron">⌄</span>
        </button>
        {open ? (
          <div ref={box} className="picker__list" role="group" aria-label={label}
            onKeyDown={(event) => {
              if (event.key === 'Escape') { event.preventDefault(); setOpen(false); trigger.current?.focus(); }
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                const stops = Array.from(box.current?.querySelectorAll<HTMLElement>('input:not([disabled]), button') ?? []);
                const at = stops.indexOf(document.activeElement as HTMLElement);
                stops[(at + (event.key === 'ArrowDown' ? 1 : stops.length - 1)) % Math.max(stops.length, 1)]?.focus();
              }
            }}>
            {options.map((option) => (
              <label key={option} className="picker__option">
                <input type="checkbox" checked={values.includes(option)} onChange={() => { toggle(option); }} />
                {valueLabel(option)}
              </label>
            ))}
            <label className="picker__option picker__option--not">
              <input type="checkbox" checked={negate} disabled={values.length === 0} onChange={flip} />
              {t('editor.notThese')}
            </label>
            <button type="button" className="picker__clear" onClick={() => { onText(''); setOpen(false); trigger.current?.focus(); }}>{t('editor.anyValue')}</button>
          </div>
        ) : null}
      </span>
    </Frame>
  );
}

export function CompareEditor({ label, column, error, detail, errorId, model, currency, onText }: CellProps & { model: CellModel; currency: string | undefined; onText: (text: string) => void }) {
  const { t, locale } = useI18n();
  const amountRef = useRef<HTMLInputElement>(null);
  // After a wildcard turns into a comparison, the amount field takes the focus once.
  const focusAmount = useRef(false);
  useEffect(() => {
    if (focusAmount.current && model.kind === 'compare') {
      focusAmount.current = false;
      amountRef.current?.focus();
    }
  }, [model.kind]);
  const describedBy = error !== undefined ? errorId : undefined;
  if (model.kind !== 'compare') {
    return (
      <Frame error={error} detail={detail} errorId={errorId}>
        <Wildcard label={label} column={column} describedBy={describedBy} onStart={() => { focusAmount.current = true; onText(writeConditionCell({ kind: 'compare', op: '≥', amount: '', currency })); }} />
      </Frame>
    );
  }
  const write = (next: Partial<{ op: Operator; amount: string }>) => {
    onText(writeConditionCell({ ...model, currency: model.currency ?? currency, ...next }));
  };
  const shownCurrency = model.currency ?? currency;
  return (
    <Frame error={error} detail={detail} errorId={errorId}>
      <span className="compare">
        <select className="compare__op" aria-label={t('editor.comparison', { cell: label })} value={model.op}
          onChange={(e) => { if (e.target.value === 'any') onText(''); else write({ op: e.target.value as Operator }); }}>
          <option value="any">{t('editor.anyValue')}</option>
          {OPERATORS.map((op) => <option key={op} value={op}>{op}</option>)}
        </select>
        <label className={shownCurrency !== undefined ? 'amount amount--money' : 'amount'}>
          <input ref={amountRef} className="amount__input tnum" inputMode="decimal" aria-label={label} aria-invalid={error !== undefined}
            {...(describedBy !== undefined ? { 'aria-describedby': describedBy } : {})}
            value={shownAmount(model.amount, locale)} onChange={(e) => { write({ amount: canonicalAmount(e.target.value, locale) }); }} />
          {shownCurrency !== undefined ? <span className="amount__code" aria-hidden="true">{shownCurrency}</span> : null}
        </label>
      </span>
    </Frame>
  );
}

export function MoneyEditor({ label, error, detail, errorId, model, onText }: CellProps & { model: Extract<CellModel, { kind: 'money' }>; onText: (text: string) => void }) {
  const { locale } = useI18n();
  const describedBy = error !== undefined ? errorId : undefined;
  return (
    <Frame error={error} detail={detail} errorId={errorId}>
      <label className="amount amount--money">
        <input className="amount__input tnum" inputMode="decimal" aria-label={label} aria-invalid={error !== undefined} placeholder={label.split(', ').at(-1)}
          {...(describedBy !== undefined ? { 'aria-describedby': describedBy } : {})}
          value={shownAmount(model.amount, locale)} onChange={(e) => { onText(writeOutputCell({ ...model, amount: canonicalAmount(e.target.value, locale) })); }} />
        <span className="amount__code" aria-hidden="true">{model.currency}</span>
      </label>
    </Frame>
  );
}

/** Yes or No; in a condition a third choice clears it to any value. */
export function BoolToggle({ label, error, detail, errorId, value, condition, onText }: CellProps & { value: boolean | undefined; condition: boolean; onText: (text: string) => void }) {
  const { t } = useI18n();
  const write = (next: boolean | undefined) => {
    if (next === undefined) onText('');
    else onText(condition ? writeConditionCell({ kind: 'bool', value: next }) : String(next));
  };
  return (
    <Frame error={error} detail={detail} errorId={errorId}>
      <span className="seg seg--cell" role="group" aria-label={label}>
        <button type="button" className="hit" aria-pressed={value === true} onClick={() => { write(true); }}>{t('table.yes')}</button>
        <button type="button" className="hit" aria-pressed={value === false} onClick={() => { write(false); }}>{t('table.no')}</button>
        {condition && value !== undefined ? <button type="button" className="hit" aria-label={t('editor.anyCell', { cell: label })} onClick={() => { write(undefined); }}>—</button> : null}
      </span>
    </Frame>
  );
}

export function TextEditor({ label, error, detail, errorId, text, mono, onText }: CellProps & { text: string; mono: boolean; onText: (text: string) => void }) {
  const describedBy = error !== undefined ? errorId : undefined;
  return (
    <Frame error={error} detail={detail} errorId={errorId}>
      <input className={mono ? 'cell-input mono' : 'cell-input'} value={text} title={text} aria-label={label} aria-invalid={error !== undefined} spellCheck={false}
        {...(describedBy !== undefined ? { 'aria-describedby': describedBy } : {})} onChange={(e) => { onText(e.target.value); }} />
    </Frame>
  );
}
