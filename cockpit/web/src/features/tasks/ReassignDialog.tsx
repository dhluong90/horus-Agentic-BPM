import { useEffect, useId, useRef, useState } from 'react';
import type { TaskHandOver } from '../../api/processes/processesClient';
import { useI18n } from '../../i18n/I18nProvider';
import { ASSIGNEE_MAX, handOverProblems, REASON_MAX } from './model/queue';

/**
 * Hands a task to a colleague (PD-110): their user id and a required reason of 1 to 500 characters, which the engine
 * records in the task history. Reassign stays unavailable, with the reason in words, until both are entered. A
 * refusal stays in the dialog, so the user can pick another colleague without typing the reason again.
 */
export function ReassignDialog({ title, sending, onClose, onSubmit }: {
  title: string;
  sending: boolean;
  onClose: () => void;
  /** Sends the hand-over; resolves to the refusal sentence, or undefined when the engine accepted it. */
  onSubmit: (handOver: TaskHandOver) => Promise<string | undefined>;
}) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const base = useId();
  const [assignee, setAssignee] = useState('');
  const [reason, setReason] = useState('');
  const [refusal, setRefusal] = useState<string | undefined>(undefined);
  const problems = handOverProblems(assignee, reason);
  const blocked = problems.length === 2 ? t('tasks.dialog.needBoth') : problems[0] === 'assignee-missing' ? t('tasks.dialog.needAssignee') : problems[0] === 'reason-missing' ? t('tasks.dialog.needReason') : undefined;
  const unavailable = blocked !== undefined || sending;

  // The native dialog gives the focus trap, Escape and focus return.
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);

  return (
    <dialog ref={dialog} className="dialog tasks-dialog" aria-labelledby={`${base}-title`} aria-describedby={`${base}-text`} onClose={onClose}>
      <form
        method="dialog"
        className="dialog__body"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (unavailable) return;
          setRefusal(undefined);
          void onSubmit({ assignee: assignee.trim(), reason: reason.trim() }).then(setRefusal);
        }}
      >
        <h2 id={`${base}-title`} className="dialog__title">{t('tasks.dialog.title', { task: title })}</h2>
        <p id={`${base}-text`} className="dialog__text">{t('tasks.dialog.text')}</p>
        <div className="field">
          <label htmlFor={`${base}-assignee`} className="field__label">{t('tasks.dialog.assignee')}</label>
          <input id={`${base}-assignee`} className="field__input" autoComplete="off" spellCheck={false} maxLength={ASSIGNEE_MAX} required
            aria-describedby={`${base}-assignee-hint`} value={assignee} onChange={(event) => { setAssignee(event.target.value); setRefusal(undefined); }} />
          <p id={`${base}-assignee-hint`} className="field__hint">{t('tasks.dialog.assigneeHint')}</p>
        </div>
        <div className="field">
          <label htmlFor={`${base}-reason`} className="field__label">{t('tasks.dialog.reason')}</label>
          <textarea id={`${base}-reason`} className="field__input" rows={3} maxLength={REASON_MAX} required placeholder={t('tasks.dialog.reasonHint')}
            aria-describedby={`${base}-reason-count`} value={reason} onChange={(event) => { setReason(event.target.value); }} />
          <p id={`${base}-reason-count`} className="field__hint tnum">{t('tasks.dialog.reasonCount', { count: String(reason.length), max: String(REASON_MAX) })}</p>
        </div>
        {refusal !== undefined ? (
          <div className="alert alert--error" role="alert">
            <span className="alert__icon" aria-hidden="true">✕</span>
            <div className="alert__body"><p>{refusal}</p></div>
          </div>
        ) : null}
        <div className="tasks-dialog__foot">
          {blocked !== undefined ? <p className="tasks__why" id={`${base}-blocked`}>{blocked}</p> : <span />}
          <div className="dialog__actions">
            <button type="button" className="btn btn--quiet" onClick={onClose}>{t('action.cancel')}</button>
            <button type="submit" className="btn btn--primary" aria-disabled={unavailable ? 'true' : undefined} aria-busy={sending}
              aria-describedby={blocked !== undefined ? `${base}-blocked` : undefined}>
              {sending ? t('tasks.dialog.sending') : t('tasks.dialog.submit')}
            </button>
          </div>
        </div>
      </form>
    </dialog>
  );
}
