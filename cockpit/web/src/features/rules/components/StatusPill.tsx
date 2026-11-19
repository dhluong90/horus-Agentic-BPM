import type { VersionStatus } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';

const LOOK: Record<VersionStatus, { glyph: string; tone: string }> = {
  DRAFT: { glyph: '✎', tone: 'neutral' },
  PENDING_APPROVAL: { glyph: '◷', tone: 'accent' },
  PUBLISHED: { glyph: '✓', tone: 'ok' },
  RETIRED: { glyph: '■', tone: 'muted' },
};

/** A version status in a short word with its icon, for compact rows (Draft, Waiting, Published). */
export function StatusPill({ status }: { status: VersionStatus }) {
  const { t } = useI18n();
  const look = LOOK[status];
  return (
    <span className={`badge badge--${look.tone}`}>
      <span aria-hidden="true">{look.glyph}</span> {t(`statusShort.${status}`)}
    </span>
  );
}
