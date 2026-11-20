import { useI18n } from '../../../i18n/I18nProvider';
import { monthLabel, monthOf } from '../model/references';

/** How many months back the picker offers; the decision log keeps its partitions by month. */
const MONTHS_BACK = 24;

/** The months offered, newest first: this month and the 24 before it, plus `value` when it is older. */
export function monthOptions(now: Date, value: string): string[] {
  const months = Array.from({ length: MONTHS_BACK + 1 }, (_, back) => monthOf(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - back, 1))));
  return months.includes(value) ? months : [...months, value].sort().reverse();
}

/**
 * A calendar month as a list of named months in the screen's language ("November 2026", "tháng 11 năm 2026").
 * A native month field would name months in the browser's language, which can differ from the screen's.
 */
export function MonthSelect({ id, value, onChange, className = 'field__input' }: { id?: string | undefined; value: string; onChange: (month: string) => void; className?: string }) {
  const { locale } = useI18n();
  return (
    <select id={id} className={className} value={value} onChange={(event) => { onChange(event.target.value); }}>
      {monthOptions(new Date(), value).map((month) => <option key={month} value={month}>{monthLabel(month, locale)}</option>)}
    </select>
  );
}
