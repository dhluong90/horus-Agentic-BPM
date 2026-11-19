import type { HistoryEntry } from '../../../api/rules/schemas';
import type { MessageKey } from '../../../i18n/en';
import { useI18n } from '../../../i18n/I18nProvider';
import { useHistory } from '../api/useRules';
import { formatDateTime } from '../model/dates';

const ACTIONS: Record<string, MessageKey> = {
  CREATED: 'history.CREATED',
  REQUESTED: 'history.REQUESTED',
  APPROVED: 'history.APPROVED',
  REJECTED: 'history.REJECTED',
};

function Entry({ entry }: { entry: HistoryEntry }) {
  const { t, locale } = useI18n();
  const action = ACTIONS[entry.action];
  return (
    <li className={`history__entry history__entry--${entry.action.toLowerCase()}`}>
      <span className="history__what">{t('history.by', { action: action ? t(action) : entry.action, actor: entry.actor })}</span>
      <span className="history__when tnum">{formatDateTime(entry.occurredAt, locale)}</span>
      {entry.reason !== null && entry.reason !== undefined && entry.reason !== '' ? <q className="history__reason">{entry.reason}</q> : null}
    </li>
  );
}

/** The version's audit trail from the engine, collapsed under the gate with its count. */
export function HistoryList({ ruleSet, version }: { ruleSet: string; version: number }) {
  const { t } = useI18n();
  const history = useHistory(ruleSet, version);
  if (!history.isSuccess) return null;
  return (
    <details className="history">
      <summary className="hit">{t('history.title', { count: String(history.data.length) })}</summary>
      {history.data.length === 0 ? (
        <p className="dock__hint">{t('history.empty')}</p>
      ) : (
        <ol className="history__list">
          {history.data.map((entry) => <Entry key={`${entry.action}-${String(entry.lockVersion)}-${entry.occurredAt}`} entry={entry} />)}
        </ol>
      )}
    </details>
  );
}
