import type { HistoryEntry } from '../../../api/rules/schemas';
import type { MessageKey } from '../../../i18n/en';
import { useI18n } from '../../../i18n/I18nProvider';
import { useHistory } from '../api/useRules';
import { formatDateTime } from '../model/dates';
import { ProblemNotice } from './ProblemNotice';

const ACTIONS: Record<string, { key: MessageKey; glyph: string }> = {
  CREATED: { key: 'history.CREATED', glyph: '✎' },
  REQUESTED: { key: 'history.REQUESTED', glyph: '◷' },
  APPROVED: { key: 'history.APPROVED', glyph: '✓' },
  REJECTED: { key: 'history.REJECTED', glyph: '✕' },
};

function Entry({ entry }: { entry: HistoryEntry }) {
  const { t, locale } = useI18n();
  const action = ACTIONS[entry.action];
  return (
    <li className={`history__entry history__entry--${entry.action.toLowerCase()}`}>
      <span className="history__what">
        <span aria-hidden="true">{action?.glyph ?? '•'}</span> {t('history.by', { action: action ? t(action.key) : entry.action, actor: entry.actor })}
      </span>
      <span className="history__when tnum">{formatDateTime(entry.occurredAt, locale)}</span>
      {entry.reason !== null && entry.reason !== undefined && entry.reason !== '' ? <q className="history__reason">{entry.reason}</q> : null}
    </li>
  );
}

/** The version's audit trail from the engine, as the History tab. */
export function HistoryList({ ruleSet, version }: { ruleSet: string; version: number }) {
  const { t } = useI18n();
  const history = useHistory(ruleSet, version);
  if (history.isPending) return <p className="empty" role="status">{t('ruleSets.loading')}</p>;
  if (history.isError) return <ProblemNotice error={history.error} />;
  return (
    <section className="history" aria-labelledby="history-title">
      <h2 id="history-title" className="rules-card-title">{t('history.title', { count: String(history.data.length) })}</h2>
      {history.data.length === 0 ? (
        <p className="dock__hint">{t('history.empty')}</p>
      ) : (
        <ol className="history__list">
          {history.data.map((entry) => <Entry key={`${entry.action}-${String(entry.lockVersion)}-${entry.occurredAt}`} entry={entry} />)}
        </ol>
      )}
    </section>
  );
}
