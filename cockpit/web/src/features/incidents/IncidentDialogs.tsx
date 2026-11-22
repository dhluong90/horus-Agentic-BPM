import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import type { IncidentResolution } from '../../api/processes/processesClient';
import { ErrorAlert } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { fieldLabel } from '../processes/model/review';
import { groupWhileTyping } from '../rules/model/money';
import { buildOutput, type FieldProblem, type OutputField } from './model/incidents';

/** The engine's `lifecycle.reason-max-length` default; the engine checks the real limit. */
const REASON_MAX = 2000;

/** A refused command, kept in the dialog: the sentence, the engine's answer for Details, and the fields it names. */
export interface DialogRefusal {
  text: string;
  error: unknown;
  fields: readonly string[];
}

/** The native dialog: focus trap, Escape and focus return. jsdom has no showModal; the open attribute serves there. */
function useModal() {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);
  return dialog;
}

function Frame({ title, text, wide = false, onClose, onSubmit, children }: {
  title: string; text: string; wide?: boolean; onClose: () => void; onSubmit: (event: FormEvent) => void; children: ReactNode;
}) {
  const dialog = useModal();
  const base = useId();
  return (
    <dialog ref={dialog} className={wide ? 'dialog tasks-dialog incidents-dialog incidents-dialog--wide' : 'dialog tasks-dialog incidents-dialog'}
      aria-labelledby={`${base}-title`} aria-describedby={`${base}-text`} onClose={onClose}>
      <form method="dialog" className="dialog__body" noValidate onSubmit={onSubmit}>
        <h2 id={`${base}-title`} className="dialog__title">{title}</h2>
        <p id={`${base}-text`} className="dialog__text">{text}</p>
        {children}
      </form>
    </dialog>
  );
}

function Foot({ blocked, blockedId, submit, sendingLabel, sending, danger = false, onClose }: {
  blocked: string | undefined; blockedId: string; submit: string; sendingLabel: string; sending: boolean; danger?: boolean; onClose: () => void;
}) {
  const { t } = useI18n();
  const unavailable = blocked !== undefined || sending;
  return (
    <div className="tasks-dialog__foot">
      {blocked !== undefined ? <p className="tasks__why" id={blockedId}>{blocked}</p> : <span />}
      <div className="dialog__actions">
        <button type="button" className="btn btn--quiet" onClick={onClose}>{t('action.cancel')}</button>
        <button type="submit" className={danger ? 'btn btn--danger' : 'btn btn--primary'} aria-disabled={unavailable ? 'true' : undefined} aria-busy={sending}
          aria-describedby={blocked !== undefined ? blockedId : undefined}>
          {sending ? sendingLabel : submit}
        </button>
      </div>
    </div>
  );
}

/**
 * Asks for a reason (retry, reject) or an optional comment (approve) before the command is sent. A refusal stays in
 * the dialog, so the user keeps what they typed.
 */
export function ReasonDialog({ title, text, label, required, danger = false, submit, sendingLabel, sending, onClose, onSubmit }: {
  title: string; text: string; label: string; required: boolean; danger?: boolean; submit: string; sendingLabel: string; sending: boolean;
  onClose: () => void; onSubmit: (reason: string) => Promise<DialogRefusal | undefined>;
}) {
  const { t } = useI18n();
  const base = useId();
  const [reason, setReason] = useState('');
  const [refusal, setRefusal] = useState<DialogRefusal | undefined>(undefined);
  const blocked = required && reason.trim() === '' ? t('incidents.dialog.needReason') : undefined;
  return (
    <Frame title={title} text={text} onClose={onClose} onSubmit={(event) => {
      event.preventDefault();
      if (blocked !== undefined || sending) return;
      setRefusal(undefined);
      void onSubmit(reason.trim()).then(setRefusal);
    }}>
      <div className="field">
        <label htmlFor={`${base}-reason`} className="field__label">{label}</label>
        <textarea id={`${base}-reason`} className="field__input" rows={3} maxLength={REASON_MAX} required={required}
          aria-describedby={`${base}-count`} value={reason} onChange={(event) => { setReason(event.target.value); }} />
        <p id={`${base}-count`} className="field__hint tnum">{t('tasks.dialog.reasonCount', { count: String(reason.length), max: String(REASON_MAX) })}</p>
      </div>
      {refusal !== undefined ? <ErrorAlert text={refusal.text} error={refusal.error} /> : null}
      <Foot blocked={blocked} blockedId={`${base}-blocked`} submit={submit} sendingLabel={sendingLabel} sending={sending} danger={danger} onClose={onClose} />
    </Frame>
  );
}

const KINDS: readonly { kind: OutputField['kind']; label: MessageKey }[] = [
  { kind: 'text', label: 'incidents.kind.text' },
  { kind: 'number', label: 'incidents.kind.number' },
  { kind: 'amount', label: 'incidents.kind.amount' },
  { kind: 'yesno', label: 'incidents.kind.yesno' },
];

const PROBLEM: Record<FieldProblem, MessageKey> = {
  'name-missing': 'incidents.field.nameMissing',
  'name-format': 'incidents.field.nameFormat',
  'name-duplicate': 'incidents.field.nameDuplicate',
  'value-missing': 'incidents.field.valueMissing',
  number: 'incidents.field.number',
  amount: 'incidents.field.amount',
  currency: 'incidents.field.currency',
};

let fieldSeq = 0;
const newField = (): OutputField => {
  fieldSeq += 1;
  return { key: `f${String(fieldSeq)}`, name: '', kind: 'text', text: '', currency: 'VND', yes: true };
};

/**
 * Proposes the result the waiting step completes with (PRE-078, PD-126), as fields in words, never raw JSON: a
 * name, a kind (text, number, amount, yes or no) and a value; an amount shows separators as it is typed and keeps
 * its currency inside the field. Under four eyes a colleague approves the proposal; the dialog says which applies.
 */
export function ResolveDialog({ step, undo, mode, sending, onClose, onSubmit }: {
  step: string; undo: boolean; mode: 'four-eyes' | 'single'; sending: boolean;
  onClose: () => void; onSubmit: (resolution: IncidentResolution) => Promise<DialogRefusal | undefined>;
}) {
  const { t, locale } = useI18n();
  const base = useId();
  const [fields, setFields] = useState<OutputField[]>(() => [newField()]);
  const [reason, setReason] = useState('');
  const [shown, setShown] = useState(false);
  const [refusal, setRefusal] = useState<DialogRefusal | undefined>(undefined);
  const built = buildOutput(fields, locale);
  const update = (key: string, change: Partial<OutputField>) => {
    setFields((current) => current.map((field) => (field.key === key ? { ...field, ...change } : field)));
    setRefusal(undefined);
  };
  const missingReason = reason.trim() === '';
  const blocked = !built.ok && built.empty ? t('incidents.dialog.needField') : missingReason ? t('incidents.dialog.needReason') : undefined;
  const problems = !built.ok && shown ? built.problems : {};
  const refusedNames = new Set(refusal?.fields ?? []);
  const text = `${t(undo ? 'incidents.resolve.textUndo' : 'incidents.resolve.text')} ${t(mode === 'single' ? 'incidents.resolve.single' : 'incidents.resolve.fourEyes')}`;
  return (
    <Frame wide title={t('incidents.resolve.title', { step })} text={text} onClose={onClose} onSubmit={(event) => {
      event.preventDefault();
      setShown(true);
      if (blocked !== undefined || sending || !built.ok) return;
      setRefusal(undefined);
      void onSubmit({ output: built.output, reason: reason.trim() }).then(setRefusal);
    }}>
      <fieldset className="incidents-fields">
        <legend className="field__label">{t('incidents.fields.legend')}</legend>
        <div className="incidents-fields__head" aria-hidden="true">
          <span>{t('incidents.fields.name')}</span><span>{t('incidents.fields.kind')}</span><span>{t('incidents.fields.value')}</span><span />
        </div>
        <ul className="incidents-fields__rows">
          {fields.map((field, index) => {
            const problem = problems[field.key];
            const refused = refusedNames.has(field.name.trim());
            const errorId = `${base}-${field.key}-error`;
            const number = String(index + 1);
            const invalid = problem !== undefined || refused;
            return (
              <li key={field.key} className="incidents-fields__row">
                <input className="field__input" aria-label={t('incidents.fields.nameOf', { number })} value={field.name} autoComplete="off" spellCheck={false} maxLength={100}
                  aria-invalid={problem?.startsWith('name') === true || refused ? 'true' : undefined} aria-describedby={invalid ? errorId : undefined}
                  placeholder={t('incidents.fields.namePlaceholder')} onChange={(event) => { update(field.key, { name: event.target.value }); }} />
                <select className="field__input" aria-label={t('incidents.fields.kindOf', { number })} value={field.kind}
                  onChange={(event) => { update(field.key, { kind: event.target.value as OutputField['kind'], text: '' }); }}>
                  {KINDS.map((option) => <option key={option.kind} value={option.kind}>{t(option.label)}</option>)}
                </select>
                <Value field={field} number={number} invalid={invalid && problem?.startsWith('name') !== true} errorId={errorId}
                  onChange={(change) => { update(field.key, change); }} groupDigits={(value) => groupWhileTyping(value, locale)} />
                <button type="button" className="btn btn--quiet btn--small hit incidents-fields__remove" aria-label={t('incidents.fields.remove', { number })}
                  title={t('incidents.fields.remove', { number })} onClick={() => { setFields((current) => current.filter((item) => item.key !== field.key)); }}>✕</button>
                {invalid ? (
                  <p id={errorId} className="field__error incidents-fields__error">
                    <span aria-hidden="true">✕</span> {problem !== undefined ? t(PROBLEM[problem]) : t('incidents.field.refused', { field: fieldLabel(field.name.trim()) })}
                  </p>
                ) : null}
              </li>
            );
          })}
        </ul>
        <button type="button" className="btn btn--ghost btn--small hit" onClick={() => { setFields((current) => [...current, newField()]); }}>
          <span aria-hidden="true">＋</span> {t('incidents.fields.add')}
        </button>
      </fieldset>
      <div className="field">
        <label htmlFor={`${base}-reason`} className="field__label">{t('incidents.reason')}</label>
        <textarea id={`${base}-reason`} className="field__input" rows={2} maxLength={REASON_MAX} required aria-describedby={`${base}-count`}
          value={reason} onChange={(event) => { setReason(event.target.value); }} />
        <p id={`${base}-count`} className="field__hint tnum">{t('tasks.dialog.reasonCount', { count: String(reason.length), max: String(REASON_MAX) })}</p>
      </div>
      {refusal !== undefined ? <ErrorAlert text={refusal.text} error={refusal.error} /> : null}
      <Foot blocked={blocked} blockedId={`${base}-blocked`} submit={t(mode === 'single' ? 'incidents.resolve.submitSingle' : 'incidents.resolve.submit')}
        sendingLabel={t('incidents.resolve.sending')} sending={sending} onClose={onClose} />
    </Frame>
  );
}

/** The value of one field, by its kind: text, a number, an amount with its currency inside the field, or yes or no. */
function Value({ field, number, invalid, errorId, onChange, groupDigits }: {
  field: OutputField; number: string; invalid: boolean; errorId: string; onChange: (change: Partial<OutputField>) => void; groupDigits: (text: string) => string;
}) {
  const { t } = useI18n();
  const label = t('incidents.fields.valueOf', { number });
  const describe = invalid ? { 'aria-invalid': 'true' as const, 'aria-describedby': errorId } : {};
  if (field.kind === 'yesno') {
    return (
      <div className="seg seg--small" role="group" aria-label={label}>
        <button type="button" className="hit" aria-pressed={field.yes} onClick={() => { onChange({ yes: true }); }}>{t('review.yes')}</button>
        <button type="button" className="hit" aria-pressed={!field.yes} onClick={() => { onChange({ yes: false }); }}>{t('review.no')}</button>
      </div>
    );
  }
  if (field.kind === 'amount') {
    return (
      <span className="money-field">
        <input className="field__input money-field__input tnum" inputMode="decimal" aria-label={label} value={field.text} autoComplete="off" {...describe}
          onChange={(event) => { onChange({ text: groupDigits(event.target.value) }); }} />
        <input className="money-field__currency" aria-label={t('incidents.fields.currencyOf', { number })} value={field.currency} maxLength={3} autoComplete="off" spellCheck={false}
          onChange={(event) => { onChange({ currency: event.target.value.toUpperCase() }); }} />
      </span>
    );
  }
  return (
    <input className={field.kind === 'number' ? 'field__input tnum' : 'field__input'} inputMode={field.kind === 'number' ? 'decimal' : undefined} aria-label={label}
      value={field.text} autoComplete="off" maxLength={500} {...describe} onChange={(event) => { onChange({ text: event.target.value }); }} />
  );
}
