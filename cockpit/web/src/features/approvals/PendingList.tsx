import { suppliedName, usePersonName } from '../../app/people';
import { formatRoute } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import type { OwnWork, PendingRequest } from '../overview/useRuleOverview';
import { StatusPill } from '../rules/components/StatusPill';
import { formatDateTime } from '../rules/model/dates';

type Item = PendingRequest | OwnWork;

/** One row's meaning: a request this user may review, their own request, or their own draft. */
type Row = { kind: 'review' | 'request' | 'draft'; item: Item };

function toRow(item: Item): Row {
  if ('kind' in item) return { kind: item.kind, item };
  return { kind: item.mine ? 'request' : 'review', item };
}

/**
 * Requests and drafts as rows. Review appears only where the engine lists approve for this user; the user's
 * own request never offers Approve (four eyes). Withdraw is offered where the engine lists it and the caller
 * can take the reason (the Approvals page).
 */
export function PendingList({ items, compact = false, onWithdraw }: { items: readonly Item[]; compact?: boolean; onWithdraw?: (item: Item) => void }) {
  return (
    <ul className="rows">
      {items.map((item) => (
        <PendingRow key={`${item.version.ruleSet}/${String(item.version.version)}`} row={toRow(item)} compact={compact} onWithdraw={onWithdraw} />
      ))}
    </ul>
  );
}

function PendingRow({ row, compact, onWithdraw }: { row: Row; compact: boolean; onWithdraw: ((item: Item) => void) | undefined }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  const { version } = row.item;
  const lens = row.kind === 'draft' ? 'table' : 'changes';
  const href = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens });
  const title = t('rules.nameVersion', { name: version.ruleSet, version: String(version.version) });
  const date = formatDateTime(version.requestedAt ?? version.createdAt, locale) ?? '';
  const meta =
    row.kind === 'review'
      ? t('home.sentBy', { date, name: personName(version.requestedBy ?? '', suppliedName(version, 'requestedBy')) })
      : row.kind === 'request'
        ? t('home.sentWaiting', { date })
        : t('home.savedAt', { date });
  const canApprove = 'canApprove' in row.item && row.item.canApprove;
  const canWithdraw = row.item.canWithdraw && onWithdraw !== undefined && !compact;
  return (
    <li className="row row--link">
      <div className="row__text">
        <a className="row__main" href={href}>{title}</a>
        <span className="row__meta tnum">{meta}</span>
      </div>
      <StatusPill status={version.status} />
      {canApprove ? (
        <a className="btn btn--primary btn--small hit row__action" href={href} aria-label={`${t('approvals.review')}: ${title}`}>
          {t('approvals.review')}
        </a>
      ) : null}
      {canWithdraw ? (
        <button type="button" className="btn btn--quiet btn--small hit row__action" aria-label={`${t('approvals.withdraw')}: ${title}`} onClick={() => { onWithdraw(row.item); }}>
          {t('approvals.withdraw')}
        </button>
      ) : null}
    </li>
  );
}
