import { useI18n } from '../../i18n/I18nProvider';
import { instanceStatusLook } from './model/instanceView';

/** A case's status in a word with its icon; colour is never the only signal. */
export function InstanceStatusPill({ status }: { status: string }) {
  const { t } = useI18n();
  const look = instanceStatusLook(status);
  return (
    <span className={`badge badge--${look.tone}`}>
      <span aria-hidden="true">{look.glyph}</span> {t(look.key)}
    </span>
  );
}
