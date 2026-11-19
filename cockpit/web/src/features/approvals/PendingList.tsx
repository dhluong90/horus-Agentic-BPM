import { formatRoute } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import type { PendingRequest } from '../overview/useRuleOverview';
import { formatDateTime } from '../rules/model/dates';

/**
 * Requests waiting for a second person. Review appears only where the engine lists approve for this user;
 * on the user's own request the row says so and never offers Approve (four eyes). Withdraw is offered on the
 * Approvals page only, where the reason can be typed.
 */
export function PendingList({ requests, compact = false, onWithdraw }: { requests: readonly PendingRequest[]; compact?: boolean; onWithdraw?: (request: PendingRequest) => void }) {
  return (
    <ul className="rows">
      {requests.map((request) => (
        <PendingRow key={`${request.version.ruleSet}/${request.version.version}`} request={request} compact={compact} onWithdraw={onWithdraw} />
      ))}
    </ul>
  );
}

function PendingRow({ request, compact, onWithdraw }: { request: PendingRequest; compact: boolean; onWithdraw: ((request: PendingRequest) => void) | undefined }) {
  const { t, locale } = useI18n();
  const { version } = request;
  const changes = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'changes' });
  const requester = request.mine ? t('approvals.yourRequest') : t('approvals.requestedBy', { user: version.requestedBy ?? t('error.notProvided') });
  const label = t('approvals.rowLabel', { name: version.ruleSet, version: String(version.version) });
  return (
    <li className="row">
      <a className="row__main mono" href={changes}>
        {version.ruleSet}
      </a>
      <span className="row__meta tnum">{t('rulesArea.versionShort', { version: String(version.version) })}</span>
      <span className={request.mine ? 'row__who row__who--mine' : 'row__who'}>{requester}</span>
      {compact ? null : <span className="row__time tnum">{formatDateTime(version.requestedAt ?? version.createdAt, locale)}</span>}
      <span className="row__actions">
        {request.canApprove ? (
          <a className="btn btn--primary btn--small hit" href={changes} aria-label={`${t('approvals.review')}: ${label}`}>
            {t('approvals.review')}
          </a>
        ) : null}
        {request.canWithdraw && onWithdraw !== undefined ? (
          <button type="button" className="btn btn--quiet btn--small hit" aria-label={`${t('approvals.withdraw')}: ${label}`} onClick={() => { onWithdraw(request); }}>
            {t('approvals.withdraw')}
          </button>
        ) : null}
        {request.mine && !(request.canWithdraw && onWithdraw !== undefined) ? <span className="row__note">{t('approvals.waitingForOther')}</span> : null}
      </span>
    </li>
  );
}
