import { useState, type ReactNode } from 'react';
import { nameOf } from '../../app/names';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { VersionSummary } from '../../api/rules/schemas';
import { usePersonName } from '../../app/people';
import { formatRoute, type Area } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, type NextAction } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { PendingList } from '../approvals/PendingList';
import { QueueRows } from '../approvals/QueueRows';
import { itemKey, reviewRoute, type ApprovalItem, type Engine } from '../approvals/queue';
import { useApprovalQueue, type QueueState } from '../approvals/useApprovalQueue';
import { useRetryRules } from '../overview/useRetryRules';
import { mapLoadable, ownWork, useEveryRuleSet, useOpenVersions, type Loadable, type OwnWork } from '../overview/useRuleOverview';
import { RuleSetTable } from '../rules/RuleSetTable';
import { formatDateTime } from '../rules/model/dates';
import { useRulesSession } from '../rules/api/rulesContext';

/** Rows of the recent rule sets card; the full list is one click away. */
const RECENT_ROWS = 5;

/**
 * Home, the landing page for every role (PD-092). Its content follows what /me allows: an approver sees the
 * requests waiting for them across both engines, an author their own drafts and requests, anyone who reads rules
 * the recent rule sets, and a user with nothing to do here one line saying so. Every number comes from the engines.
 */
export function HomePage({ pageLimit, me, areas, engines }: { pageLimit: number; me: BpmMeV1; areas: readonly Area[]; engines: readonly Engine[] }) {
  const { t } = useI18n();
  const rules = areas.includes('rules');
  const approver = mayApprove(me.permissions, engines);
  const name = me.user.displayName ?? me.user.id;
  return (
    <section className="workspace" aria-labelledby="home-title">
      <div className="page-head">
        <h1 id="home-title">{t(greeting(new Date().getHours()), { name })}</h1>
        <p className="page-head__sub">{t(subtitle(me.permissions, rules, approver))}</p>
      </div>
      {rules || approver ? <RoleContent pageLimit={pageLimit} me={me} engines={engines} rules={rules} approver={approver} /> : (
        <article className="card">
          <EmptyState icon="home" text={t('home.nothingForRole')} />
        </article>
      )}
    </section>
  );
}

/** The approve permission of an engine whose queue this user reads. */
function mayApprove(permissions: readonly string[], engines: readonly Engine[]): boolean {
  return (engines.includes('rules') && permissions.includes('rules:approve')) || (engines.includes('processes') && permissions.includes('processes:approve'));
}

function greeting(hour: number): MessageKey {
  if (hour < 12) return 'home.greeting.morning';
  if (hour < 18) return 'home.greeting.afternoon';
  return 'home.greeting.evening';
}

function subtitle(permissions: readonly string[], rules: boolean, approver: boolean): MessageKey {
  const author = rules && permissions.includes('rules:author');
  if (author && approver) return 'home.subtitle.both';
  if (author) return 'home.subtitle.author';
  if (approver) return 'home.subtitle.approver';
  return rules ? 'home.subtitle.reader' : 'home.subtitle.none';
}

/** The queue's requests this user may approve now, as one async value. A queue they may not read holds none. */
function approvable(state: QueueState): Loadable<ApprovalItem[]> {
  switch (state.status) {
    case 'loading':
      return { status: 'loading' };
    case 'error':
      return { status: 'error', error: state.error };
    case 'forbidden':
      return { status: 'ready', data: [] };
    case 'ready':
      return { status: 'ready', data: state.items.filter((item) => item.canApprove) };
  }
}

function RoleContent({ pageLimit, me, engines, rules, approver }: { pageLimit: number; me: BpmMeV1; engines: readonly Engine[]; rules: boolean; approver: boolean }) {
  const { t } = useI18n();
  const { userId } = useRulesSession();
  const queue = useApprovalQueue(engines, pageLimit);
  const retryRules = useRetryRules();
  const ruleSets = useEveryRuleSet(pageLimit, rules);
  const versions = useOpenVersions(ruleSets);
  const author = rules && me.permissions.includes('rules:author');
  const toReview = approvable(queue.state);
  const mine = mapLoadable(versions, (data) => ownWork(data, userId));
  const focus = focalItem(toReview, author ? mine : undefined);
  // The focal request is not repeated in the list, and the list shows requests only, never drafts.
  const focusKey = focus?.kind === 'review' ? itemKey(focus.item) : undefined;
  const otherReviews = mapLoadable(toReview, (data) => data.filter((item) => itemKey(item) !== focusKey));
  const focusDraft = focus?.kind === 'draft' ? `${focus.version.ruleSet}/${String(focus.version.version)}` : undefined;
  // Approvers see "Pending approvals", which holds requests only. Authors who cannot approve see their drafts
  // and requests together, under a title that says so (the focal draft is not repeated).
  const myRequests = mapLoadable(mine, (data) => data.filter((item) => item.kind === 'request'));
  const myWork = mapLoadable(mine, (data) => data.filter((item) => `${item.version.ruleSet}/${String(item.version.version)}` !== focusDraft));
  return (
    <div className="home">
      {(author || approver) ? (
        <div className={focus === undefined ? 'home__top home__top--single' : 'home__top'}>
          {focus !== undefined ? <FocusCard focus={focus} /> : null}
          {approver
            ? <PendingCard toReview={otherReviews} mine={author ? myRequests : undefined} onRetryReview={queue.retry} onRetryMine={retryRules} />
            : <PendingCard toReview={undefined} mine={myWork} title={t('home.yourDraftsAndRequests')} onRetryReview={queue.retry} onRetryMine={retryRules} />}
        </div>
      ) : null}
      {rules ? <RecentCard ruleSets={ruleSets} /> : null}
    </div>
  );
}

type Focus = { kind: 'review'; item: ApprovalItem; waiting: number } | { kind: 'draft'; version: VersionSummary };

/** The one next step: the request that has waited longest (as Approvals' Review next), else the author's latest draft. */
function focalItem(toReview: Loadable<ApprovalItem[]>, mine: Loadable<OwnWork[]> | undefined): Focus | undefined {
  if (toReview.status === 'ready' && toReview.data.length > 0) {
    const next = toReview.data[0];
    if (next !== undefined) return { kind: 'review', item: next, waiting: toReview.data.length };
  }
  if (mine?.status === 'ready') {
    const draft = mine.data.find((item) => item.kind === 'draft');
    if (draft !== undefined) return { kind: 'draft', version: draft.version };
  }
  return undefined;
}

/** The single contrast card of the screen (styleguide: one focal point). */
function FocusCard({ focus }: { focus: Focus }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  if (focus.kind === 'review') {
    const { item } = focus;
    return (
      <article className="focus-card" aria-labelledby="focus-title">
        <p className="focus-card__eyebrow">{t('home.focus.review')}</p>
        <h2 id="focus-title" className="focus-card__title">{t('rules.nameVersion', { name: item.title, version: String(item.version) })}</h2>
        <p className="focus-card__meta tnum">{t('home.sentBy', { date: formatDateTime(item.requestedAt, locale) ?? '', name: personName(item.requestedBy, item.requesterName) })}</p>
        <div className="focus-card__actions">
          <a className="btn btn--contrast" href={formatRoute(reviewRoute(item))}>{t('approvals.review')}</a>
          <a className="link-action link-action--contrast" href={formatRoute({ name: 'approvals' })}>{t('home.focus.allWaiting', { count: String(focus.waiting) })} <span aria-hidden="true">→</span></a>
        </div>
      </article>
    );
  }
  const { version } = focus;
  return (
    <article className="focus-card" aria-labelledby="focus-title">
      <p className="focus-card__eyebrow">{t('home.focus.continue')}</p>
      <h2 id="focus-title" className="focus-card__title">{t('rules.nameDraft', { name: nameOf('rule set', version.ruleSet, version), version: String(version.version) })}</h2>
      <p className="focus-card__meta tnum">{t('home.savedAt', { date: formatDateTime(version.createdAt, locale) ?? '' })}</p>
      <div className="focus-card__actions">
        <a className="btn btn--contrast" href={formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' })}>{t('home.focus.continueEditing')}</a>
        <a className="link-action link-action--contrast" href={formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: undefined, lens: undefined })}>{t('home.focus.openRuleSet')} <span aria-hidden="true">→</span></a>
      </div>
    </article>
  );
}

/** Pending approvals: the user's own requests and drafts, and the requests they may review, one view at a time. */
function PendingCard({ toReview, mine, title, onRetryReview, onRetryMine }: {
  toReview: Loadable<ApprovalItem[]> | undefined; mine: Loadable<OwnWork[]> | undefined; title?: string; onRetryReview: () => void; onRetryMine: () => void;
}) {
  const { t } = useI18n();
  const [view, setView] = useState<'review' | 'mine'>(toReview !== undefined ? 'review' : 'mine');
  const count = (value: Loadable<unknown[]> | undefined) => (value?.status === 'ready' ? value.data.length : undefined);
  return (
    <article className="card card--list" aria-labelledby="home-pending">
      <div className="card__head">
        <h2 id="home-pending" className="card__title">{title ?? t('home.pending')}</h2>
        {toReview !== undefined && mine !== undefined ? (
          <div className="seg seg--small" role="group" aria-label={t('home.pending')}>
            <button type="button" className="hit" aria-pressed={view === 'mine'} onClick={() => { setView('mine'); }}>
              {t('home.yourWork')} <span className="seg__count tnum">{count(mine) ?? ''}</span>
            </button>
            <button type="button" className="hit" aria-pressed={view === 'review'} onClick={() => { setView('review'); }}>
              {t('home.toReview')} <span className="seg__count tnum">{count(toReview) ?? ''}</span>
            </button>
          </div>
        ) : null}
      </div>
      {view === 'review' && toReview !== undefined ? (
        <PendingBody items={toReview} empty={t('home.noOtherReviews')} action={{ label: t('menu.approvals'), href: formatRoute({ name: 'approvals' }) }} onRetry={onRetryReview} render={(items) => <QueueRows items={items} />} />
      ) : mine !== undefined ? (
        <PendingBody items={mine} empty={t('home.noOwnWork')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} onRetry={onRetryMine} render={(items) => <PendingList items={items} />} />
      ) : null}
    </article>
  );
}

function PendingBody<T>({ items, empty, action, onRetry, render }: { items: Loadable<T[]>; empty: string; action: NextAction; onRetry: () => void; render: (items: T[]) => ReactNode }) {
  if (items.status === 'loading') return <LoadingRows rows={2} />;
  if (items.status === 'error') return <AreaError error={items.error} onRetry={onRetry} />;
  if (items.data.length === 0) {
    return <EmptyState icon="approvals" text={empty} action={action} />;
  }
  return <>{render(items.data.slice(0, RECENT_ROWS))}</>;
}

function RecentCard({ ruleSets }: { ruleSets: ReturnType<typeof useEveryRuleSet> }) {
  const { t } = useI18n();
  return (
    <article className="card card--table" aria-labelledby="home-recent">
      <div className="card__head card__head--padded">
        <h2 id="home-recent" className="card__title">{t('home.recentRuleSets')}</h2>
        <a className="link-action" href={formatRoute({ name: 'rule-sets' })}>{t('home.allRuleSets')} <span aria-hidden="true">→</span></a>
      </div>
      <RuleSetTable ruleSets={ruleSets} limit={RECENT_ROWS} recentFirst emptyText={t('rulesArea.empty')} />
    </article>
  );
}
