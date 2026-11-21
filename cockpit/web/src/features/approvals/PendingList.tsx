import { formatRoute } from '../../app/route';
import { useI18n } from '../../i18n/I18nProvider';
import type { OwnWork } from '../overview/useRuleOverview';
import { StatusPill } from '../rules/components/StatusPill';
import { formatDateTime } from '../rules/model/dates';

/**
 * An author's own drafts and requests as rows. A request never offers Approve (four eyes); it reads as waiting for
 * an approver.
 */
export function PendingList({ items }: { items: readonly OwnWork[] }) {
  return (
    <ul className="rows">
      {items.map((item) => (
        <OwnRow key={`${item.version.ruleSet}/${String(item.version.version)}`} item={item} />
      ))}
    </ul>
  );
}

function OwnRow({ item }: { item: OwnWork }) {
  const { t, locale } = useI18n();
  const { version } = item;
  const lens = item.kind === 'draft' ? 'table' : 'changes';
  const href = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens });
  const date = formatDateTime(version.requestedAt ?? version.createdAt, locale) ?? '';
  return (
    <li className="row row--link">
      <div className="row__text">
        <a className="row__main" href={href}>{t('rules.nameVersion', { name: version.ruleSet, version: String(version.version) })}</a>
        <span className="row__meta tnum">{item.kind === 'request' ? t('home.sentWaiting', { date }) : t('home.savedAt', { date })}</span>
      </div>
      <StatusPill status={version.status} />
      <span className="row__chevron" aria-hidden="true">›</span>
    </li>
  );
}
