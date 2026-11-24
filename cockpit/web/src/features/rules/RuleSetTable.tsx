import type { RuleSetSummary } from '../../api/rules/schemas';
import { nameOf } from '../../app/names';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, type NextAction } from '../../components/PageParts';
import { InitialDisc } from '../../components/TintTiles';
import { useI18n } from '../../i18n/I18nProvider';
import { useRetryRules } from '../overview/useRetryRules';
import { recentFirst as byRecent, type Loadable } from '../overview/useRuleOverview';
import { StatusBadge } from './components/StatusBadge';
import { formatDate, formatDateTime } from './model/dates';

/**
 * Rule sets as a table whose rows open the rule set: name in the UI font, status in words, versions as
 * "Version 2", the version in effect, the last change. Used by Home (five most recent) and by Rules.
 */
export function RuleSetTable({ ruleSets, limit, recentFirst = false, emptyText, emptyAction, dense = false, compact = false }: {
  ruleSets: Loadable<RuleSetSummary[]>;
  limit?: number;
  recentFirst?: boolean;
  emptyText: string;
  emptyAction?: NextAction | undefined;
  /** The list style of the Rules page: an initial disc, the human name alone and one action per row. */
  dense?: boolean;
  /** Half-width cards (Home): tighter cells, the date without the time, so every column fits in either language. */
  compact?: boolean;
}) {
  const { t, locale } = useI18n();
  const retry = useRetryRules();
  if (ruleSets.status === 'loading') return <div className="card__pad"><LoadingRows rows={limit ?? 4} /></div>;
  if (ruleSets.status === 'error') return <div className="card__pad"><AreaError error={ruleSets.error} onRetry={retry} /></div>;
  if (ruleSets.data.length === 0) return <div className="card__pad"><EmptyState icon="rules" text={emptyText} action={emptyAction} /></div>;
  const ordered = recentFirst ? byRecent(ruleSets.data) : ruleSets.data;
  const rows = limit === undefined ? ordered : ordered.slice(0, limit);
  const version = (value: number) => t('rules.version', { version: String(value) });
  return (
    <table className={compact ? 'qtable qtable--links qtable--compact' : 'qtable qtable--links'}>
      <thead>
        <tr>
          <th scope="col">{t('rulesArea.col.name')}</th>
          <th scope="col">{t('rulesArea.col.status')}</th>
          <th scope="col">{t('rulesArea.col.latest')}</th>
          <th scope="col">{t('rulesArea.col.published')}</th>
          <th scope="col">{t('rulesArea.col.changed')}</th>
          <th scope="col"><span className="sr-only">{t('rulesArea.col.open')}</span></th>
        </tr>
      </thead>
      <tbody>
        {rows.map((item) => (
          <tr key={item.ruleSet}>
            <th scope="row">
              {dense ? (
                <div className="dense__name">
                  <InitialDisc name={nameOf('rule set', item.ruleSet, item)} />
                  <span className="dense__text">
                    <a className="qtable__link" href={formatRoute({ name: 'rule-set', ruleSet: item.ruleSet, version: undefined, lens: undefined })} title={item.ruleSet}>{nameOf('rule set', item.ruleSet, item)}</a>
                    <span className="qtable__key">{item.ruleSet}</span>
                  </span>
                </div>
              ) : (
                <>
                  <a className="qtable__link" href={formatRoute({ name: 'rule-set', ruleSet: item.ruleSet, version: undefined, lens: undefined })} title={item.ruleSet}>{nameOf('rule set', item.ruleSet, item)}</a>
                  {/* The key stays visible behind the name people read, for support and for the Details of the rule set. */}
                  <span className="qtable__key">{item.ruleSet}</span>
                </>
              )}
            </th>
            <td><StatusBadge status={item.latestStatus} /></td>
            <td className="tnum">{version(item.latestVersion)}</td>
            <td className="tnum">{item.publishedVersion !== null ? version(item.publishedVersion) : <span className="muted" aria-label={t('rulesArea.none')}>—</span>}</td>
            <td className="tnum muted" title={compact ? formatDateTime(item.lastChangedAt, locale) : undefined}>{dense || compact ? formatDate(item.lastChangedAt, locale) : formatDateTime(item.lastChangedAt, locale)}</td>
            {dense ? (
              <td className="qtable__action">
                {item.latestStatus === 'PENDING_APPROVAL' ? (
                  <a className="btn btn--small hit" href={formatRoute({ name: 'rule-set', ruleSet: item.ruleSet, version: item.latestVersion, lens: 'changes' })}
                    aria-label={`${t('processes.reviewChange')}: ${item.displayName}, ${version(item.latestVersion)}`}>{t('processes.reviewChange')}</a>
                ) : (
                  <a className="btn btn--small btn--quiet hit" href={formatRoute({ name: 'rule-set', ruleSet: item.ruleSet, version: undefined, lens: undefined })} aria-label={t('list.openName', { name: item.displayName })}>{t('list.open')}</a>
                )}
              </td>
            ) : <td className="qtable__chevron" aria-hidden="true">›</td>}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
