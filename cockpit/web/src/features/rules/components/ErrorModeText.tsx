import { useI18n } from '../../../i18n/I18nProvider';
import { errorModeWords } from '../model/errorMode';

/** An error mode in words, with its one-line explanation on hover and, when `explain` is set, under it. Nothing without a mode. */
export function ErrorModeText({ mode, explain = false }: { mode: string | undefined; explain?: boolean }) {
  const { t } = useI18n();
  const words = errorModeWords(mode);
  if (words === undefined) return null;
  const why = t(words.explanation);
  return (
    <span className="error-mode" title={why}>
      <span className="error-mode__label">{t(words.label)}</span>
      {explain ? <span className="error-mode__why muted">{why}</span> : null}
    </span>
  );
}
