import { useState } from 'react';
import { useI18n } from '../i18n/I18nProvider';

export function CopyButton({ value }: { value: string }) {
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
        {t('action.copyReference')}
      </button>
      <span className="sr-only" role="status" aria-live="polite">
        {copied ? t('action.referenceCopied') : ''}
      </span>
    </>
  );
}
