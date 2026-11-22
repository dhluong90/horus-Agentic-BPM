import type { HistoryEntry } from '../../../api/rules/schemas';
import { usePersonName } from '../../../app/people';
import type { MessageKey } from '../../../i18n/en';
import { useI18n } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../model/dates';

const ACTIONS: Record<string, { key: MessageKey; glyph: string }> = {
  CREATED: { key: 'history.CREATED', glyph: '✎' },
  REQUESTED: { key: 'history.REQUESTED', glyph: '◷' },
  APPROVED: { key: 'history.APPROVED', glyph: '✓' },
  REJECTED: { key: 'history.REJECTED', glyph: '✕' },
  RETIRED: { key: 'history.RETIRED', glyph: '■' },
  WITHDRAWN: { key: 'history.WITHDRAWN', glyph: '↩' },
  DRAFT_UPDATED: { key: 'history.DRAFT_UPDATED', glyph: '✎' },
  DISCARDED: { key: 'history.DISCARDED', glyph: '⌫' },
};

/** One entry of a version's audit trail: what happened in words, who did it, when, and the reason given. */
export function HistoryEntryItem({ entry }: { entry: HistoryEntry }) {
  const { t, locale } = useI18n();
  const name = usePersonName();
  const action = ACTIONS[entry.action];
  return (
    <li className={`history__entry history__entry--${entry.action.toLowerCase()}`}>
      <span className="history__what">
        <span className="history__glyph" aria-hidden="true">{action?.glyph ?? '•'}</span> {t('history.by', { action: action ? t(action.key) : t('history.other'), actor: name(entry.actor) })}
      </span>
      <span className="history__when tnum">{formatDateTime(entry.occurredAt, locale)}</span>
      {entry.reason !== null && entry.reason !== undefined && entry.reason !== '' ? <q className="history__reason">{entry.reason}</q> : null}
    </li>
  );
}
