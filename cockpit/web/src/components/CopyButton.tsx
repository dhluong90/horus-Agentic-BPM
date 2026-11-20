import { useState } from 'react';
import { useI18n } from '../i18n/I18nProvider';

/** Copies a reference in full. `label` names what is copied when it is not a support reference (for example a SHA-256). */
export function CopyButton({ value, label, copiedLabel }: { value: string; label?: string | undefined; copiedLabel?: string | undefined }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <>
      <button type="button" className="btn btn--quiet btn--small hit" onClick={() => void copy()}>
        {label ?? t('action.copyReference')}
      </button>
      <span className="sr-only" role="status" aria-live="polite">
        {copied ? (copiedLabel ?? t('action.referenceCopied')) : ''}
      </span>
    </>
  );
}
