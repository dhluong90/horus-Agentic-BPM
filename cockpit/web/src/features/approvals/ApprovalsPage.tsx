import { useState } from 'react';
import type { RuleSetSummary } from '../../api/rules/schemas';
import { suppliedName, usePersonName } from '../../app/people';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { useRulesSession } from '../rules/api/rulesContext';
import { StatusBadge } from '../rules/components/StatusBadge';
import { StatusPill } from '../rules/components/StatusPill';
import { formatDateTime } from '../rules/model/dates';
import { useRetryRules } from '../overview/useRetryRules';
import { mapLoadable, relevantRequests, useEveryRuleSet, useOpenVersions, type Loadable, type PendingRequest } from '../overview/useRuleOverview';
import { WithdrawDialog } from './WithdrawDialog';

type View = 'review' | 'mine';

const key = (request: PendingRequest) => `${request.version.ruleSet}/${String(request.version.version)}`;

/**
 * The four-eyes queue (PD-093): requests this user may review, and their own requests, which they follow
 * and may withdraw. One view at a time; the selected request is previewed beside the list.
 */
export function ApprovalsPage({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  const { can, userId } = useRulesSession();
  const retry = useRetryRules();
  const ruleSets = useEveryRuleSet(pageLimit);
  const versions = useOpenVersions(ruleSets);
  const approver = can('rules:approve');
  const requests = mapLoadable(versions, (data) => relevantRequests(data, userId));
  const toReview = mapLoadable(requests, (data) => data.filter((request) => request.canApprove));
  const mine = mapLoadable(requests, (data) => data.filter((request) => request.mine));
  const [view, setView] = useState<View>(approver ? 'review' : 'mine');
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [withdrawing, setWithdrawing] = useState<PendingRequest | undefined>(undefined);
  const [notice, setNotice] = useState('');
  const shown = view === 'review' ? toReview : mine;
  const current = shown.status === 'ready' ? (shown.data.find((request) => key(request) === selected) ?? shown.data[0]) : undefined;
  const next = toReview.status === 'ready' ? toReview.data[toReview.data.length - 1] : undefined;
  const count = (value: Loadable<PendingRequest[]>) => (value.status === 'ready' ? String(value.data.length) : '');
  const published = ruleSets.status === 'ready' ? new Map(ruleSets.data.map((item: RuleSetSummary) => [item.ruleSet, item.publishedVersion])) : new Map<string, number | null>();

  return (
    <section className="workspace" aria-labelledby="approvals-title">
      <PageHead
        id="approvals-title"
        title={t('approvals.title')}
        subtitle={approver ? t('approvals.subtitle') : t('approvals.subtitleAuthor')}
        action={next !== undefined ? <a className="btn btn--primary" href={formatRoute({ name: 'rule-set', ruleSet: next.version.ruleSet, version: next.version.version, lens: 'changes' })}>{t('approvals.reviewNext')}</a> : undefined}
      />
      <p className="sr-only" role="status" aria-live="polite">{notice}</p>
      <div className="queue">
        <div className="queue__list">
          <div className="seg" role="group" aria-label={t('approvals.title')}>
            {approver ? (
              <button type="button" className="hit" aria-pressed={view === 'review'} onClick={() => { setView('review'); setSelected(undefined); }}>
                {t('home.toReview')} <span className="seg__count tnum">{count(toReview)}</span>
              </button>
            ) : null}
            <button type="button" className="hit" aria-pressed={view === 'mine'} onClick={() => { setView('mine'); setSelected(undefined); }}>
              {t('approvals.yourRequests')} <span className="seg__count tnum">{count(mine)}</span>
            </button>
          </div>
          <QueueBody items={shown} view={view} current={current} onSelect={(request) => { setSelected(key(request)); }} onRetry={retry} />
        </div>
        {current !== undefined ? (
          <Preview request={current} replaces={published.get(current.version.ruleSet) ?? null} onWithdraw={setWithdrawing} />
        ) : null}
      </div>
      {withdrawing !== undefined ? (
        <WithdrawDialog
          key={key(withdrawing)}
          request={withdrawing}
          onClose={() => { setWithdrawing(undefined); }}
          onDone={(message) => {
            setNotice(message);
            setWithdrawing(undefined);
          }}
        />
      ) : null}
    </section>
  );
}

function QueueBody({ items, view, current, onSelect, onRetry }: { items: Loadable<PendingRequest[]>; view: View; current: PendingRequest | undefined; onSelect: (request: PendingRequest) => void; onRetry: () => void }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  if (items.status === 'loading') return <LoadingRows rows={2} />;
  if (items.status === 'error') return <AreaError error={items.error} onRetry={onRetry} />;
  if (items.data.length === 0) {
    return (
      <article className="card">
        <EmptyState icon="approvals" text={view === 'review' ? t('approvals.empty') : t('approvals.emptyMine')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} />
      </article>
    );
  }
  return (
    <ul className="queue__items">
      {items.data.map((request) => {
        const { version } = request;
        const selected = current !== undefined && key(current) === key(request);
        const who = request.mine ? t('approvals.yourRequest') : personName(version.requestedBy ?? '', suppliedName(version, 'requestedBy'));
        return (
          <li key={key(request)}>
            <button type="button" className={selected ? 'queue-card is-selected' : 'queue-card'} aria-pressed={selected} onClick={() => { onSelect(request); }}>
              <span className="queue-card__title">{t('rules.nameVersion', { name: version.ruleSet, version: String(version.version) })}</span>
              <StatusPill status={version.status} />
              <span className="queue-card__meta tnum">{who} · {formatDateTime(version.requestedAt ?? version.createdAt, locale)}</span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** The selected request: who asked, when, what it replaces, and the way to the full review. */
function Preview({ request, replaces, onWithdraw }: { request: PendingRequest; replaces: number | null; onWithdraw: (request: PendingRequest) => void }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  const { version } = request;
  const requester = personName(version.requestedBy ?? '', suppliedName(version, 'requestedBy'));
  const review = formatRoute({ name: 'rule-set', ruleSet: version.ruleSet, version: version.version, lens: 'changes' });
  return (
    <article className="card queue__preview" aria-labelledby="approval-preview">
      <div className="card__head">
        <h2 id="approval-preview" className="card__title">{t('rules.nameVersion', { name: version.ruleSet, version: String(version.version) })}</h2>
        <span className="muted tnum">{replaces !== null ? t('approvals.replaces', { version: String(replaces) }) : t('approvals.firstVersion')}</span>
      </div>
      <dl className="facts-list">
        <div><dt>{t('rulesArea.col.requestedBy')}</dt><dd>{requester}</dd></div>
        <div><dt>{t('approvals.sent')}</dt><dd className="tnum">{formatDateTime(version.requestedAt ?? version.createdAt, locale)}</dd></div>
        <div><dt>{t('rulesArea.col.status')}</dt><dd><StatusBadge status={version.status} /></dd></div>
      </dl>
      {request.mine ? <p className="muted">{t('approvals.waitingForOther')}</p> : null}
      <div className="queue__preview-actions">
        <a className="btn" href={review}><span aria-hidden="true">→</span> {request.canApprove ? t('approvals.openReview') : t('approvals.openChanges')}</a>
        {request.canWithdraw ? (
          <button type="button" className="btn btn--quiet" onClick={() => { onWithdraw(request); }}>{t('approvals.withdraw')}</button>
        ) : null}
      </div>
    </article>
  );
}

