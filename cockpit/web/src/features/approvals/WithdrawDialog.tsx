import { useEffect, useId, useRef, useState } from 'react';
import { nameOf } from '../../app/names';
import { toHostError } from '../../api/hostError';
import { useI18n } from '../../i18n/I18nProvider';
import { useWithdraw, type WithdrawTarget } from './useWithdraw';

/** The engine's default `lifecycle.reason-max-length`; the engine checks the real limit and answers 400 above it. */
const REASON_MAX = 2000;

/**
 * Withdraws the user's own request, with an optional reason. The request stays on screen until the engine
 * confirms; a refused withdrawal shows the engine's error.
 */
export function WithdrawDialog({ request, onClose, onDone }: { request: WithdrawTarget; onClose: () => void; onDone: (message: string) => void }) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const reasonId = useId();
  const [reason, setReason] = useState('');
  const version = request;
  const name = { name: nameOf('rule set', version.ruleSet, version), version: String(version.version) };
  const withdraw = useWithdraw(() => {
    onDone(t('approvals.withdrawn', name));
  });

  // The native dialog gives the focus trap, Escape and focus return.
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    // jsdom has no showModal; the open attribute is enough there. No close() on clean-up: its close event would
    // reach onClose during StrictMode's second effect run. Removing the element closes the dialog.
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);

  const problem = withdraw.isError ? toHostError(withdraw.error) : undefined;
  const problemText = problem === undefined ? undefined : problem.kind === 'problem' && problem.status === 409 ? t('approvals.withdrawConflict') : t('area.error.generic');
  return (
    <dialog ref={dialog} className="dialog" aria-labelledby={`${reasonId}-title`} onClose={onClose}>
      <form
        method="dialog"
        className="dialog__body"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (withdraw.isPending) return;
          const text = reason.trim();
          withdraw.mutate({ version, reason: text === '' ? undefined : text });
        }}
      >
        <h2 id={`${reasonId}-title`} className="dialog__title">{t('approvals.withdrawTitle', name)}</h2>
        <p className="dialog__text">{t('approvals.withdrawText')}</p>
        <label htmlFor={reasonId} className="field__label">{t('approvals.reason')}</label>
        <textarea
          id={reasonId}
          className="field__input"
          rows={3}
          maxLength={REASON_MAX}
          value={reason}
          onChange={(event) => {
            setReason(event.target.value);
          }}
        />
        {problemText !== undefined ? <p className="field__error" role="alert">{problemText}</p> : null}
        <div className="dialog__actions">
          <button type="button" className="btn btn--quiet" onClick={onClose}>{t('action.cancel')}</button>
          <button type="submit" className="btn btn--danger" disabled={withdraw.isPending}>
            {withdraw.isPending ? t('approvals.withdrawing') : t('approvals.withdraw')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
