import { useState } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { VersionSummary } from '../../api/rules/schemas';
import { suppliedName, usePersonName } from '../../app/people';
import { formatRoute, type Area } from '../../app/route';
import { AreaError, EmptyState, LoadingRows } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { PendingList } from '../approvals/PendingList';
import { useRetryRules } from '../overview/useRetryRules';
import { mapLoadable, ownWork, relevantRequests, useEveryRuleSet, useOpenVersions, type Loadable, type OwnWork, type PendingRequest } from '../overview/useRuleOverview';
import { RuleSetTable } from '../rules/RuleSetTable';
import { formatDateTime } from '../rules/model/dates';
import { useRulesSession } from '../rules/api/rulesContext';

/** Rows of the recent rule sets card; the full list is one click away. */
const RECENT_ROWS = 5;

/**
 * Home, the landing page for every role (PD-092). Its content follows what /me allows: an approver sees the
 * requests waiting for them, an author their own drafts and requests, anyone who reads rules the recent
 * rule sets, and a user with no rule permission one line saying so. Every number comes from the engine.
 */
export function HomePage({ pageLimit, me, areas }: { pageLimit: number; me: BpmMeV1; areas: readonly Area[] }) {
  const { t } = useI18n();
  const rules = areas.includes('rules');
  const name = me.user.displayName ?? me.user.id;
  return (
    <section className="workspace" aria-labelledby="home-title">
      <div className="page-head">
        <h1 id="home-title">{t(greeting(new Date().getHours()), { name })}</h1>
        <p className="page-head__sub">{t(subtitle(me.permissions, rules))}</p>
      </div>
      {rules ? <RoleContent pageLimit={pageLimit} me={me} /> : (
        <article className="card">
          <EmptyState icon="home" text={t('home.nothingForRole')} />
        </article>
      )}
    </section>
  );
}

function greeting(hour: number): MessageKey {
  if (hour < 12) return 'home.greeting.morning';
  if (hour < 18) return 'home.greeting.afternoon';
  return 'home.greeting.evening';
}

function subtitle(permissions: readonly string[], rules: boolean): MessageKey {
  if (!rules) return 'home.subtitle.none';
  const author = permissions.includes('rules:author');
  const approver = permissions.includes('rules:approve');
  if (author && approver) return 'home.subtitle.both';
  if (author) return 'home.subtitle.author';
  if (approver) return 'home.subtitle.approver';
  return 'home.subtitle.reader';
}

function RoleContent({ pageLimit, me }: { pageLimit: number; me: BpmMeV1 }) {
  const { t } = useI18n();
  const { userId } = useRulesSession();
  const ruleSets = useEveryRuleSet(pageLimit);
  const versions = useOpenVersions(ruleSets);
  const author = me.permissions.includes('rules:author');
  const approver = me.permissions.includes('rules:approve');
  const toReview = mapLoadable(versions, (data) => relevantRequests(data, userId).filter((request) => request.canApprove));
  const mine = mapLoadable(versions, (data) => ownWork(data, userId));
  const focus = focalItem(toReview, mine);
  // The focal request is not repeated in the list, and the list shows requests only, never drafts.
  const focusKey = focus?.kind === 'review' ? `${focus.request.version.ruleSet}/${String(focus.request.version.version)}` : undefined;
  const otherReviews = mapLoadable(toReview, (data) => data.filter((request) => `${request.version.ruleSet}/${String(request.version.version)}` !== focusKey));
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
          {approver ? <PendingCard toReview={otherReviews} mine={author ? myRequests : undefined} /> : <PendingCard toReview={undefined} mine={myWork} title={t('home.yourDraftsAndRequests')} />}
        </div>
      ) : null}
      <RecentCard ruleSets={ruleSets} />
    </div>
  );
}

type Focus = { kind: 'review'; request: PendingRequest; waiting: number } | { kind: 'draft'; version: VersionSummary };

/** The one next step: the request that has waited longest (as Approvals' Review next), else the author's latest draft. */
function focalItem(toReview: Loadable<PendingRequest[]>, mine: Loadable<OwnWork[]>): Focus | undefined {
  if (toReview.status === 'ready' && toReview.data.length > 0) {
    const next = toReview.data[0];
    if (next !== undefined) return { kind: 'review', request: next, waiting: toReview.data.length };
  }
  if (mine.status === 'ready') {
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
    const { version } = focus.request;
    const requester = personName(version.requestedBy ?? '', suppliedName(version, 'requestedBy'));
    return (
      <article className="focus-card" aria-labelledby="focus-title">
        <p className="focus-card__eyebrow">{t('home.focus.review')}</p>
        <h2 id="focus-title" className="focus-card__title">{t('rules.nameVersion', { name: version.ruleSet, version: String(version.version) })}</h2>
        <p className="focus-card__meta tnum">{t('home.sentBy', { date: formatDateTime(version.requestedAt ?? version.createdAt, locale) ?? '', name: requester })}</p>
        <div className="focus-card__actions">
          <a className="btn btn--contrast" href={formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'changes' })}>{t('approvals.review')}</a>
          <a className="link-action link-action--contrast" href={formatRoute({ name: 'approvals' })}>{t('home.focus.allWaiting', { count: String(focus.waiting) })} <span aria-hidden="true">→</span></a>
        </div>
      </article>
    );
  }
  const { version } = focus;
  return (
    <article className="focus-card" aria-labelledby="focus-title">
      <p className="focus-card__eyebrow">{t('home.focus.continue')}</p>
      <h2 id="focus-title" className="focus-card__title">{t('rules.nameDraft', { name: version.ruleSet, version: String(version.version) })}</h2>
      <p className="focus-card__meta tnum">{t('home.savedAt', { date: formatDateTime(version.createdAt, locale) ?? '' })}</p>
      <div className="focus-card__actions">
        <a className="btn btn--contrast" href={formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'table' })}>{t('home.focus.continueEditing')}</a>
        <a className="link-action link-action--contrast" href={formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: undefined, lens: undefined })}>{t('home.focus.openRuleSet')} <span aria-hidden="true">→</span></a>
      </div>
    </article>
  );
}

/** Pending approvals: the user's own requests and drafts, and the requests they may review, one view at a time. */
function PendingCard({ toReview, mine, title }: { toReview: Loadable<PendingRequest[]> | undefined; mine: Loadable<OwnWork[]> | undefined; title?: string }) {
  const { t } = useI18n();
  const retry = useRetryRules();
  const [view, setView] = useState<'review' | 'mine'>(toReview !== undefined ? 'review' : 'mine');
  const count = (value: Loadable<unknown[]> | undefined) => (value?.status === 'ready' ? value.data.length : undefined);
  const shown = view === 'review' ? toReview : mine;
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
      {shown === undefined ? null : <PendingBody view={view} items={shown} onRetry={retry} />}
    </article>
  );
}

function PendingBody({ view, items, onRetry }: { view: 'review' | 'mine'; items: Loadable<PendingRequest[]> | Loadable<OwnWork[]>; onRetry: () => void }) {
  const { t } = useI18n();
  if (items.status === 'loading') return <LoadingRows rows={2} />;
  if (items.status === 'error') return <AreaError error={items.error} onRetry={onRetry} />;
  if (items.data.length === 0) {
    return <EmptyState icon="approvals" text={view === 'review' ? t('home.noOtherReviews') : t('home.noOwnWork')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} />;
  }
  return <PendingList items={items.data.slice(0, RECENT_ROWS)} compact />;
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
