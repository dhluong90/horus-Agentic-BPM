import { useEffect } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';

/** A success message at the bottom right, gone after six seconds (styleguide: alerts). */
export function Toast({ text, onClose }: { text: string | undefined; onClose: () => void }) {
  const { t } = useI18n();
  useEffect(() => {
    if (text === undefined) return;
    const timer = window.setTimeout(onClose, 6000);
    return () => { window.clearTimeout(timer); };
  }, [text, onClose]);
  return (
    <div className="toast-host" role="status" aria-live="polite">
      {text !== undefined ? (
        <div className="toast">
          <span aria-hidden="true" className="toast__icon">✓</span>
          <span>{text}</span>
          <button type="button" className="toast__close hit" aria-label={t('toast.close')} onClick={onClose}>×</button>
        </div>
      ) : null}
    </div>
  );
}
