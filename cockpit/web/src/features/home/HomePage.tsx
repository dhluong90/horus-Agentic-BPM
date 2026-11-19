import { formatRoute } from '../../app/route';
import { EmptyState, LoadingLine, AreaError, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { PendingList } from '../approvals/PendingList';
import { useRetryRules } from '../overview/useRetryRules';
import { recentFirst, usePendingRequests, useEveryRuleSet, type Loadable, type PendingRequest } from '../overview/useRuleOverview';
import { StatusBadge } from '../rules/components/StatusBadge';
import { formatDateTime } from '../rules/model/dates';
import { VERSION_STATUSES, type RuleSetSummary } from '../../api/rules/schemas';

/** Rows each Home card shows; the full list is one click away. */
const HOME_ROWS = 8;

/**
 * Home: what needs this user now. Pending approvals they may act on (their own requests without Approve) and
 * the most recently changed rule sets, with counts from the engine. Nothing here is a placeholder number.
 */
export function HomePage({ pageLimit, showApprovals }: { pageLimit: number; showApprovals: boolean }) {
  const { t } = useI18n();
  const ruleSets = useEveryRuleSet(pageLimit);
  const pending = usePendingRequests(ruleSets);
  const reviewable = pending.status === 'ready' ? pending.data.filter((request) => request.canApprove).length : 0;
  const action =
    showApprovals && reviewable > 0 ? (
      <a className="btn btn--primary" href={formatRoute({ name: 'approvals' })}>
        {t('home.reviewApprovals', { count: String(reviewable) })}
      </a>
    ) : (
      <a className="btn btn--primary" href={formatRoute({ name: 'rule-sets' })}>
        {t('home.openRules')}
      </a>
    );
  return (
    <section className="workspace" aria-labelledby="home-title">
      <PageHead id="home-title" title={t('home.title')} subtitle={t('home.subtitle')} action={action} />
      <div className={showApprovals ? 'bento bento--home' : 'bento bento--single'}>
        {showApprovals ? <ApprovalsCard pending={pending} /> : null}
        <RecentRuleSetsCard ruleSets={ruleSets} />
      </div>
    </section>
  );
}

function CardHead({ id, title, count, href, linkLabel }: { id: string; title: string; count: number | undefined; href: string; linkLabel: string }) {
  return (
    <div className="card__head">
      <h2 id={id} className="card__title">
        {title}
        {count !== undefined ? <span className="count tnum">{count}</span> : null}
      </h2>
      <a className="link-action" href={href}>
        {linkLabel} <span aria-hidden="true">→</span>
      </a>
    </div>
  );
}

function ApprovalsCard({ pending }: { pending: Loadable<PendingRequest[]> }) {
  const { t } = useI18n();
  const retry = useRetryRules();
  return (
    <article className="card card--list" aria-labelledby="home-approvals">
      <CardHead
        id="home-approvals"
        title={t('home.approvals')}
        count={pending.status === 'ready' ? pending.data.length : undefined}
        href={formatRoute({ name: 'approvals' })}
        linkLabel={t('home.allApprovals')}
      />
      <ApprovalsBody pending={pending} onRetry={retry} />
      {pending.status === 'ready' && pending.data.length > 0 ? <ApprovalsFoot requests={pending.data} /> : null}
    </article>
  );
}

function ApprovalsBody({ pending, onRetry }: { pending: Loadable<PendingRequest[]>; onRetry: () => void }) {
  const { t } = useI18n();
  if (pending.status === 'loading') return <LoadingLine />;
  if (pending.status === 'error') return <AreaError error={pending.error} onRetry={onRetry} />;
  if (pending.data.length === 0) return <EmptyState text={t('approvals.empty')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} />;
  return <PendingList requests={pending.data.slice(0, HOME_ROWS)} compact />;
}

function RecentRuleSetsCard({ ruleSets }: { ruleSets: Loadable<RuleSetSummary[]> }) {
  const { t } = useI18n();
  const retry = useRetryRules();
  return (
    <article className="card card--list" aria-labelledby="home-rule-sets">
      <CardHead
        id="home-rule-sets"
        title={t('home.recentRuleSets')}
        count={ruleSets.status === 'ready' ? ruleSets.data.length : undefined}
        href={formatRoute({ name: 'rule-sets' })}
        linkLabel={t('home.allRuleSets')}
      />
      <RecentRuleSetsBody ruleSets={ruleSets} onRetry={retry} />
      {ruleSets.status === 'ready' && ruleSets.data.length > 0 ? <StatusFoot items={ruleSets.data} /> : null}
    </article>
  );
}

function RecentRuleSetsBody({ ruleSets, onRetry }: { ruleSets: Loadable<RuleSetSummary[]>; onRetry: () => void }) {
  const { t, locale } = useI18n();
  if (ruleSets.status === 'loading') return <LoadingLine />;
  if (ruleSets.status === 'error') return <AreaError error={ruleSets.error} onRetry={onRetry} />;
  if (ruleSets.data.length === 0) return <EmptyState text={t('rulesArea.empty')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} />;
  return (
    <ul className="rows">
      {recentFirst(ruleSets.data).slice(0, HOME_ROWS).map((item) => (
        <li key={item.ruleSet} className="row">
          <a className="row__main mono" href={formatRoute({ name: 'rule-set', ruleSet: item.ruleSet, version: undefined, lens: undefined })}>
            {item.ruleSet}
          </a>
          <span className="row__meta tnum">{t('rulesArea.versionShort', { version: String(item.latestVersion) })}</span>
          <StatusBadge status={item.latestStatus} />
          <span className="row__time tnum">{formatDateTime(item.lastChangedAt, locale)}</span>
        </li>
      ))}
    </ul>
  );
}

/** How the waiting requests split between those this user may review and their own. */
function ApprovalsFoot({ requests }: { requests: readonly PendingRequest[] }) {
  const { t } = useI18n();
  const toReview = requests.filter((request) => request.canApprove).length;
  const mine = requests.filter((request) => request.mine).length;
  return (
    <p className="card__foot tnum">
      <span><b>{toReview}</b> {t('home.foot.toReview')}</span>
      <span><b>{mine}</b> {t('home.foot.yours')}</span>
    </p>
  );
}

/** The latest status of every rule set, counted from the engine's list. */
function StatusFoot({ items }: { items: readonly RuleSetSummary[] }) {
  const { t } = useI18n();
  const counts = VERSION_STATUSES.map((status) => ({ status, count: items.filter((item) => item.latestStatus === status).length })).filter((entry) => entry.count > 0);
  return (
    <p className="card__foot tnum">
      {counts.map(({ status, count }) => (
        <span key={status}><b>{count}</b> {t(`status.${status}`)}</span>
      ))}
    </p>
  );
}
