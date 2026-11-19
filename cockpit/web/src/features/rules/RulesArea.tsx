import type { Route } from '../../app/route';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingLine, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { useRetryRules } from '../overview/useRetryRules';
import { useEveryRuleSet } from '../overview/useRuleOverview';
import { useVersions } from './api/useRules';
import { StatusBadge } from './components/StatusBadge';
import { formatDateTime } from './model/dates';

/**
 * The Rules area of the menu: every route under #/rules renders here. This release shows the rule set list
 * and the version list read only; the rule editing screens replace this component's body when they land.
 */
export function RulesArea({ route, pageLimit }: { route: Extract<Route, { name: 'rule-sets' | 'rule-set' }>; pageLimit: number }) {
  if (route.name === 'rule-set') return <RuleSetVersions name={route.ruleSet} highlight={route.version} />;
  return <RuleSetTable pageLimit={pageLimit} />;
}

function RuleSetTable({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="rules-title">
      <PageHead id="rules-title" title={t('rulesArea.title')} subtitle={t('rulesArea.subtitle')} />
      <article className="card card--list">
        <RuleSetRows pageLimit={pageLimit} />
      </article>
    </section>
  );
}

function RuleSetRows({ pageLimit }: { pageLimit: number }) {
  const { t, locale } = useI18n();
  const retry = useRetryRules();
  const ruleSets = useEveryRuleSet(pageLimit);
  if (ruleSets.status === 'loading') return <LoadingLine />;
  if (ruleSets.status === 'error') return <AreaError error={ruleSets.error} onRetry={retry} />;
  if (ruleSets.data.length === 0) return <EmptyState text={t('rulesArea.empty')} />;
  return (
    <table className="qtable">
      <thead>
        <tr>
          <th scope="col">{t('rulesArea.col.name')}</th>
          <th scope="col">{t('rulesArea.col.latest')}</th>
          <th scope="col">{t('rulesArea.col.status')}</th>
          <th scope="col">{t('rulesArea.col.published')}</th>
          <th scope="col">{t('rulesArea.col.changed')}</th>
        </tr>
      </thead>
      <tbody>
        {ruleSets.data.map((item) => (
          <tr key={item.ruleSet}>
            <th scope="row">
              <a className="mono" href={formatRoute({ name: 'rule-set', ruleSet: item.ruleSet, version: undefined, lens: undefined })}>{item.ruleSet}</a>
            </th>
            <td className="tnum">{t('rulesArea.versionShort', { version: String(item.latestVersion) })}</td>
            <td><StatusBadge status={item.latestStatus} /></td>
            <td className="tnum">{item.publishedVersion !== null ? t('rulesArea.versionShort', { version: String(item.publishedVersion) }) : <span aria-label={t('rulesArea.none')}>—</span>}</td>
            <td className="tnum">{formatDateTime(item.lastChangedAt, locale)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RuleSetVersions({ name, highlight }: { name: string; highlight: number | undefined }) {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="rule-set-title">
      <a className="link-action link-action--back" href={formatRoute({ name: 'rule-sets' })}>
        <span aria-hidden="true">←</span> {t('rulesArea.back')}
      </a>
      <PageHead id="rule-set-title" title={name} subtitle={t('rulesArea.versionsSubtitle')} />
      <article className="card card--list">
        <VersionRows name={name} highlight={highlight} />
      </article>
    </section>
  );
}

function VersionRows({ name, highlight }: { name: string; highlight: number | undefined }) {
  const { t, locale } = useI18n();
  const retry = useRetryRules();
  const versions = useVersions(name);
  if (versions.isPending) return <LoadingLine />;
  if (versions.isError) return <AreaError error={versions.error} onRetry={retry} />;
  const dash = <span aria-label={t('rulesArea.none')}>—</span>;
  return (
    <table className="qtable">
      <thead>
        <tr>
          <th scope="col">{t('rulesArea.col.version')}</th>
          <th scope="col">{t('rulesArea.col.status')}</th>
          <th scope="col">{t('rulesArea.col.createdBy')}</th>
          <th scope="col">{t('rulesArea.col.requestedBy')}</th>
          <th scope="col">{t('rulesArea.col.approvedBy')}</th>
          <th scope="col">{t('rulesArea.col.changed')}</th>
        </tr>
      </thead>
      <tbody>
        {versions.data.map((version) => (
          <tr key={version.version} className={version.version === highlight ? 'is-selected' : undefined} aria-current={version.version === highlight ? 'true' : undefined}>
            <th scope="row" className="tnum">{t('rulesArea.versionShort', { version: String(version.version) })}</th>
            <td><StatusBadge status={version.status} /></td>
            <td>{version.createdBy ?? dash}</td>
            <td>{version.requestedBy ?? dash}</td>
            <td>{version.approvedBy ?? dash}</td>
            <td className="tnum">{formatDateTime(version.approvedAt ?? version.requestedAt ?? version.createdAt, locale)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
