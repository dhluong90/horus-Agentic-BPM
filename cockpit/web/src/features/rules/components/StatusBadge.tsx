import type { HistoryStatus } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';

const LOOK: Record<HistoryStatus, { glyph: string; tone: string }> = {
  DRAFT: { glyph: '✎', tone: 'neutral' },
  PENDING_APPROVAL: { glyph: '◷', tone: 'accent' },
  PUBLISHED: { glyph: '✓', tone: 'ok' },
  RETIRED: { glyph: '■', tone: 'muted' },
  DISCARDED: { glyph: '⌫', tone: 'muted' },
};

/** A version status as icon and word, never colour alone. */
export function StatusBadge({ status }: { status: HistoryStatus }) {
  const { t } = useI18n();
  const look = LOOK[status];
  return (
    <span className={`badge badge--${look.tone}`}>
      <span aria-hidden="true">{look.glyph}</span> {t(`status.${status}`)}
    </span>
  );
}
