import { useEffect, useId, useRef, useState } from 'react';
import { nameOf } from '../../app/names';
import { toHostError } from '../../api/hostError';
import { useI18n } from '../../i18n/I18nProvider';
import { useWithdrawDefinition } from '../processes/api/useProcesses';
import { useWithdraw, type WithdrawTarget } from './useWithdraw';

/** The engine's default `lifecycle.reason-max-length`; the engine checks the real limit and answers 400 above it. */
const REASON_MAX = 2000;

/** The request to withdraw, from either engine: a rule set version or a process definition version. */
export type ProcessWithdrawTarget = WithdrawTarget & { engine: 'processes' };
export type WithdrawRequest = (WithdrawTarget & { engine?: 'rules' }) | ProcessWithdrawTarget;

const isProcess = (request: WithdrawRequest): request is ProcessWithdrawTarget => request.engine === 'processes';

interface Withdrawal {
  isPending: boolean;
  isError: boolean;
  error: unknown;
  send: (reason: string | undefined) => void;
}

/**
 * Withdraws the user's own request, with an optional reason. The request stays on screen until the engine
 * confirms; a refused withdrawal shows the engine's error. A process definition goes to the Process Engine, a rule set
 * version to the Rule Engine.
 */
export function WithdrawDialog(props: { request: WithdrawRequest; onClose: () => void; onDone: (message: string) => void }) {
  const { request, onClose, onDone } = props;
  return isProcess(request) ? <ProcessWithdrawal request={request} onClose={onClose} onDone={onDone} /> : <RuleWithdrawal request={request} onClose={onClose} onDone={onDone} />;
}

function RuleWithdrawal({ request, onClose, onDone }: { request: WithdrawTarget; onClose: () => void; onDone: (message: string) => void }) {
  const { t } = useI18n();
  const name = { name: nameOf('rule set', request.ruleSet, request), version: String(request.version) };
  const withdraw = useWithdraw(() => { onDone(t('approvals.withdrawn', name)); });
  return <WithdrawForm name={name} onClose={onClose} withdrawal={{ isPending: withdraw.isPending, isError: withdraw.isError, error: withdraw.error, send: (reason) => { withdraw.mutate({ version: request, reason }); } }} />;
}

function ProcessWithdrawal({ request, onClose, onDone }: { request: ProcessWithdrawTarget; onClose: () => void; onDone: (message: string) => void }) {
  const { t } = useI18n();
  const name = { name: nameOf('process', request.ruleSet, request), version: String(request.version) };
  // The engine's answer names the version as stored, so the toast uses the process's display name, never its key.
  const withdraw = useWithdrawDefinition((stored) => { onDone(t('approvals.withdrawn', { ...name, name: stored.name ?? name.name })); });
  return <WithdrawForm name={name} onClose={onClose} withdrawal={{ isPending: withdraw.isPending, isError: withdraw.isError, error: withdraw.error, send: (reason) => { withdraw.mutate({ key: request.ruleSet, version: request.version, lockVersion: request.lockVersion, reason }); } }} />;
}

function WithdrawForm({ name, withdrawal: withdraw, onClose }: { name: { name: string; version: string }; withdrawal: Withdrawal; onClose: () => void }) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const reasonId = useId();
  const [reason, setReason] = useState('');

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
          withdraw.send(text === '' ? undefined : text);
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
