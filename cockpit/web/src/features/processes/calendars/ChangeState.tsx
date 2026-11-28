import type { CalendarChange } from '../../../api/processes/schemas';
import { usePersonName } from '../../../app/people';
import { useI18n } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../../rules/model/dates';

/**
 * Where a calendar change stands, in words with a small dot: waiting, approved or rejected. The line under it says who
 * proposed it and when, and for a decided change who decided it and when. Nothing here is boxed; the words carry the
 * state and the dot only repeats it, so it never rests on colour alone.
 */
export function ChangeState({ change }: { change: Pick<CalendarChange, 'status' | 'proposedBy' | 'proposedAt' | 'decidedBy' | 'decidedAt'> }) {
  const { t, locale } = useI18n();
  const person = usePersonName();
  const status = change.status;
  const proposed = t('calendars.proposedBy', { who: person(change.proposedBy), date: formatDateTime(change.proposedAt, locale) ?? '' });
  const decided = status !== 'PENDING' && change.decidedBy !== null && change.decidedBy !== undefined
    ? t(status === 'APPROVED' ? 'calendars.approvedBy' : 'calendars.rejectedBy', { who: person(change.decidedBy), date: formatDateTime(change.decidedAt, locale) ?? '' })
    : undefined;
  return (
    <span className="change-state">
      <span className={`change-state__head change-state__head--${status.toLowerCase()}`}>
        <span className="change-state__dot" aria-hidden="true" />
        {t(status === 'PENDING' ? 'calendars.state.pending' : status === 'APPROVED' ? 'calendars.state.approved' : 'calendars.state.rejected')}
      </span>
      <span className="change-state__who tnum">{decided !== undefined ? `${decided}. ${proposed}` : proposed}</span>
    </span>
  );
}
