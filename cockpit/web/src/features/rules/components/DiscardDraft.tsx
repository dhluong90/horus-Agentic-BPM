import { useEffect, useId, useRef, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { DiscardedVersion, RuleSetVersion } from '../../../api/rules/schemas';
import { ErrorAlert } from '../../../components/PageParts';
import type { MessageKey } from '../../../i18n/en';
import { useI18n } from '../../../i18n/I18nProvider';
import { useDiscardDraft } from '../api/useRules';

/** The engine's default `lifecycle.reason-max-length`; the engine checks the real limit. */
const REASON_MAX = 2000;

/** A refused discard in one plain sentence, read from the problem code. */
function refusalKey(error: unknown): MessageKey {
  const host = toHostError(error);
  if (host.kind !== 'problem') return 'area.error.generic';
  switch (host.code) {
    case 'VERSION_CONFLICT':
      return 'discard.refused.changed';
    case 'INVALID_STATE':
      return 'discard.refused.notDraft';
    case 'PERMISSION_DENIED':
      return 'discard.refused.notAuthor';
    default:
      return 'area.error.generic';
  }
}

/**
 * Discard draft (#444, RLE-098): shown only when the engine lists `discard` in the draft's `allowedActions`, which it
 * does for the draft's author. A confirmation names the version and takes an optional reason; nothing changes on
 * screen before the engine answers.
 */
export function DiscardDraft({ version, onDiscarded }: { version: RuleSetVersion; onDiscarded: (discarded: DiscardedVersion) => void }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  if (version.status !== 'DRAFT' || !version.allowedActions.includes('discard')) return null;
  return (
    <>
      <button ref={opener} type="button" className="btn btn--danger-quiet" onClick={() => { setOpen(true); }}>{t('discard.action')}</button>
      {open ? <DiscardDialog version={version} onClose={() => { setOpen(false); opener.current?.focus(); }} onDiscarded={onDiscarded} /> : null}
    </>
  );
}

function DiscardDialog({ version, onClose, onDiscarded }: { version: RuleSetVersion; onClose: () => void; onDiscarded: (discarded: DiscardedVersion) => void }) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const base = useId();
  const [reason, setReason] = useState('');
  const discard = useDiscardDraft(onDiscarded);
  const number = String(version.version);

  // The native dialog gives the focus trap, Escape and focus return.
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby={`${base}-title`} aria-describedby={`${base}-text`} onClose={onClose}>
      <form method="dialog" className="dialog__body" noValidate
        onSubmit={(event) => {
          event.preventDefault();
          const text = reason.trim();
          discard.start({ version, reason: text === '' ? undefined : text });
        }}>
        <h2 id={`${base}-title`} className="dialog__title">{t('discard.title', { version: number })}</h2>
        <p id={`${base}-text`} className="dialog__text">{t('discard.text')}</p>
        <div className="field">
          <label htmlFor={`${base}-reason`} className="field__label">{t('discard.reason')}</label>
          <textarea id={`${base}-reason`} className="field__input" rows={3} maxLength={REASON_MAX} value={reason} onChange={(event) => { setReason(event.target.value); }} />
        </div>
        {discard.isError ? <ErrorAlert text={t(refusalKey(discard.error))} error={discard.error} /> : null}
        <div className="dialog__actions">
          <button type="button" className="btn btn--quiet" onClick={onClose}>{t('action.cancel')}</button>
          <button type="submit" className="btn btn--danger" disabled={discard.isPending} aria-busy={discard.isPending}>
            {discard.isPending ? t('discard.sending') : t('discard.confirm')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
