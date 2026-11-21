import { useEffect } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';

/**
 * A success message at the bottom right, gone after six seconds (styleguide: alerts). A `sticky` message stays until
 * the user closes it, so a keyboard or screen reader user has time to read it.
 */
export function Toast({ text, onClose, sticky = false }: { text: string | undefined; onClose: () => void; sticky?: boolean }) {
  const { t } = useI18n();
  useEffect(() => {
    if (text === undefined || sticky) return;
    const timer = window.setTimeout(onClose, 6000);
    return () => { window.clearTimeout(timer); };
  }, [text, onClose, sticky]);
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
