import { usePersonName } from '../../app/people';
import { formatRoute } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import { StatusPill } from '../rules/components/StatusPill';
import { formatDateTime } from '../rules/model/dates';
import { itemKey, itemTitle, kindLabel, reviewRoute, type ApprovalItem } from './queue';

/** Requests from the approvals queue as compact rows that open the review screen (Home's "To review"). */
export function QueueRows({ items }: { items: readonly ApprovalItem[] }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  return (
    <ul className="rows">
      {items.map((item) => (
        <li key={itemKey(item)} className="row row--link">
          <div className="row__text">
            <a className="row__main" href={formatRoute(reviewRoute(item))}>{itemTitle(item, t)}</a>
            <span className="row__meta tnum">
              {t(kindLabel(item))} · {t('home.sentBy', { date: formatDateTime(item.requestedAt, locale) ?? '', name: personName(item.requestedBy, item.requesterName) })}
            </span>
          </div>
          <StatusPill status="PENDING_APPROVAL" />
          <span className="row__chevron" aria-hidden="true">›</span>
        </li>
      ))}
    </ul>
  );
}
