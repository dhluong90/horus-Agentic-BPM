import { useState } from 'react';
import { useI18n } from '../i18n/I18nProvider';

/**
 * Copies a reference in full. `label` names what is copied when the default "Copy reference" is too long for the
 * place or it is not a support reference (for example a SHA-256); `copiedLabel` is announced once copied;
 * `iconOnly` shows a copy icon with the label as its accessible name and tooltip, for a tight row.
 */
export function CopyButton({
  value,
  label,
  copiedLabel,
  iconOnly = false,
}: {
  value: string;
  label?: string | undefined;
  copiedLabel?: string | undefined;
  iconOnly?: boolean;
}) {
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
      {iconOnly ? (
        <button type="button" className="btn btn--quiet btn--small hit copy-btn copy-btn--icon" aria-label={label ?? t('action.copyReference')} title={label ?? t('action.copyReference')} onClick={() => void copy()}>
          <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
            <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
            <path d="M10.5 3.5v-.5a1.5 1.5 0 0 0-1.5-1.5H4a1.5 1.5 0 0 0-1.5 1.5v5A1.5 1.5 0 0 0 4 9.5h.5" />
          </svg>
        </button>
      ) : (
        <button type="button" className="btn btn--quiet btn--small hit copy-btn" onClick={() => void copy()}>
          {label ?? t('action.copyReference')}
        </button>
      )}
      <span className="sr-only" role="status" aria-live="polite">
        {copied ? (copiedLabel ?? t('action.referenceCopied')) : ''}
      </span>
    </>
  );
}
