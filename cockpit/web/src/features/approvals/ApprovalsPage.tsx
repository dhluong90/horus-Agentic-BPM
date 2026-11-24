import { useId, useState } from 'react';
import { usePersonName } from '../../app/people';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useKeepInView } from '../../components/useKeepInView';
import { useI18n } from '../../i18n/I18nProvider';
import { useProcessesSession } from '../processes/api/processesContext';
import { useRulesSession } from '../rules/api/rulesContext';
import { StatusPill } from '../rules/components/StatusPill';
import { formatDateTime } from '../rules/model/dates';
import { denialMessage, inView, itemKey, itemTitle, kindLabel, reviewRoute, type ApprovalItem, type Engine, type QueueView } from './queue';
import { useApprovalQueue, type ApprovalQueue } from './useApprovalQueue';
import { WithdrawDialog } from './WithdrawDialog';

/**
 * The four-eyes queue across both engines (#31, PD-093): requests this user may review, and their own requests,
 * which they follow and may withdraw. One view at a time; the selected request is previewed beside the list. The
 * decision is taken on the existing review screen, where the change is read: the rule diff or the process diff,
 * each of which sends the hash of the content on screen (PD-139). Approve that the engine does not offer is shown
 * disabled, with the engine's reason in one line.
 */
export function ApprovalsPage({ engines, pageLimit }: { engines: readonly Engine[]; pageLimit: number }) {
  const { t } = useI18n();
  const rules = useRulesSession();
  const processes = useProcessesSession();
  const queue = useApprovalQueue(engines, pageLimit);
  const approver = (engines.includes('rules') && rules.can('rules:approve')) || (engines.includes('processes') && processes.can('processes:approve'));
  // Until the user picks a view: To review for an approver, unless nothing waits there and their own requests do.
  const [chosen, setView] = useState<QueueView | undefined>(undefined);
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [withdrawing, setWithdrawing] = useState<ApprovalItem | undefined>(undefined);
  const [notice, setNotice] = useState('');
  const { state } = queue;
  const items = state.status === 'ready' ? state.items : [];
  const view: QueueView = chosen ?? (approver && (items.some((item) => inView(item, 'review')) || !items.some((item) => inView(item, 'mine'))) ? 'review' : 'mine');
  const shown = items.filter((item) => inView(item, view));
  const current = shown.find((item) => itemKey(item) === selected) ?? shown[0];
  const next = items.find((item) => item.canApprove);
  const count = (of: QueueView) => (state.status === 'ready' ? String(items.filter((item) => inView(item, of)).length) + (state.more ? '+' : '') : '');

  return (
    <section className="workspace" aria-labelledby="approvals-title">
      <PageHead
        id="approvals-title"
        title={t('approvals.title')}
        subtitle={approver ? t('approvals.subtitle') : t('approvals.subtitleAuthor')}
        action={next !== undefined ? <a className="btn btn--primary" href={formatRoute(reviewRoute(next))}>{t('approvals.reviewNext')}</a> : undefined}
      />
      <p className="sr-only" role="status" aria-live="polite">{notice}</p>
      {state.status === 'ready' && state.failed.length > 0 ? <Incomplete failed={state.failed} onRetry={queue.retry} /> : null}
      <div className="queue">
        <div className="queue__list">
          {state.status !== 'forbidden' && state.status !== 'error' ? (
            <div className="seg" role="group" aria-label={t('approvals.title')}>
              {approver ? (
                <button type="button" className="hit" aria-pressed={view === 'review'} onClick={() => { setView('review'); setSelected(undefined); }}>
                  {t('home.toReview')} <span className="seg__count tnum">{count('review')}</span>
                </button>
              ) : null}
              <button type="button" className="hit" aria-pressed={view === 'mine'} onClick={() => { setView('mine'); setSelected(undefined); }}>
                {t('approvals.yourRequests')} <span className="seg__count tnum">{count('mine')}</span>
              </button>
            </div>
          ) : null}
          <QueueBody queue={queue} engines={engines} items={shown} view={view} current={current} onSelect={(item) => { setSelected(itemKey(item)); }} />
        </div>
        {current !== undefined ? <Preview item={current} onWithdraw={setWithdrawing} /> : null}
      </div>
      {withdrawing !== undefined ? (
        <WithdrawDialog
          key={itemKey(withdrawing)}
          request={{ engine: withdrawing.engine, ruleSet: withdrawing.key, version: withdrawing.version, lockVersion: withdrawing.lockVersion, displayName: withdrawing.title }}
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

/** One engine answered and another did not: the list says it is incomplete, and offers to read it again. */
function Incomplete({ failed, onRetry }: { failed: readonly Engine[]; onRetry: () => void }) {
  const { t } = useI18n();
  const text = failed.length > 1 ? t('approvals.partial.both') : failed[0] === 'processes' ? t('approvals.partial.processes') : t('approvals.partial.rules');
  return (
    <div className="notice notice--warn notice--line queue__notice" role="status">
      <div className="notice__row">
        <p className="notice__title"><span className="notice__icon" aria-hidden="true">!</span> {text}</p>
        <button type="button" className="btn btn--quiet btn--small hit" onClick={onRetry}>{t('action.retry')}</button>
      </div>
    </div>
  );
}

function QueueBody({ queue, engines, items, view, current, onSelect }: {
  queue: ApprovalQueue; engines: readonly Engine[]; items: readonly ApprovalItem[]; view: QueueView; current: ApprovalItem | undefined; onSelect: (item: ApprovalItem) => void;
}) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  const { state } = queue;
  const selectedRow = useKeepInView<HTMLLIElement>(current !== undefined ? itemKey(current) : undefined);
  if (state.status === 'loading') return <article className="card"><LoadingRows rows={3} /></article>;
  if (state.status === 'error') return <article className="card"><AreaError error={state.error} onRetry={queue.retry} /></article>;
  if (state.status === 'forbidden') {
    return (
      <article className="card">
        <EmptyState icon="approvals" text={t('approvals.forbidden')} action={{ label: t('menu.home'), href: formatRoute({ name: 'home' }) }} />
      </article>
    );
  }
  if (items.length === 0) {
    const next = engines.includes('rules') ? { label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) } : { label: t('approvals.openProcesses'), href: formatRoute({ name: 'processes' }) };
    return (
      <article className="card">
        <EmptyState icon="approvals" text={view === 'review' ? t('approvals.empty') : t('approvals.emptyMine')} action={next} />
      </article>
    );
  }
  return (
    <>
      <ul className="queue__items">
        {items.map((item) => {
          const selected = current !== undefined && itemKey(current) === itemKey(item);
          const who = item.mine ? t('approvals.yourRequest') : personName(item.requestedBy, item.requesterName);
          return (
            <li key={itemKey(item)} ref={selected ? selectedRow : undefined}>
              <button type="button" className={selected ? 'queue-card is-selected' : 'queue-card'} aria-pressed={selected} onClick={() => { onSelect(item); }}>
                <span className="queue-card__title">{itemTitle(item, t)}</span>
                <span className="tag tag--kind">{t(kindLabel(item))}</span>
                <span className="queue-card__meta tnum">{who} · {formatDateTime(item.requestedAt, locale)}</span>
              </button>
            </li>
          );
        })}
      </ul>
      {state.more ? (
        <button type="button" className="btn btn--quiet hit" disabled={queue.loadingMore} aria-busy={queue.loadingMore} onClick={queue.loadMore}>
          {queue.loadingMore ? t('approvals.loadingMore') : t('approvals.showMore')}
        </button>
      ) : null}
    </>
  );
}

/**
 * The selected request: what it is, who asked and when, and the decision. Where the engine offers Approve, the way
 * in is the full review with the change in view; where it does not, Approve is disabled with the reason beside it.
 */
function Preview({ item, onWithdraw }: { item: ApprovalItem; onWithdraw: (item: ApprovalItem) => void }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  const reasonId = useId();
  const review = formatRoute(reviewRoute(item));
  const requester = item.mine ? t('approvals.yourRequest') : personName(item.requestedBy, item.requesterName);
  return (
    <article className="card queue__preview" aria-labelledby="approval-preview">
      <div className="card__head">
        <h2 id="approval-preview" className="card__title">{itemTitle(item, t)}</h2>
        <StatusPill status="PENDING_APPROVAL" />
      </div>
      <dl className="facts-list">
        <div><dt>{t('approvals.kindLabel')}</dt><dd>{t(kindLabel(item))}</dd></div>
        <div><dt>{t('rulesArea.col.requestedBy')}</dt><dd>{requester}</dd></div>
        <div><dt>{t('approvals.sent')}</dt><dd className="tnum">{formatDateTime(item.requestedAt, locale)}</dd></div>
        {item.effectiveFrom !== undefined ? <div><dt>{t('approvals.effectiveFrom')}</dt><dd className="tnum">{formatDateTime(item.effectiveFrom, locale)}</dd></div> : null}
        {item.reason !== undefined ? <div><dt>{t('approvals.reasonLabel')}</dt><dd>{item.reason}</dd></div> : null}
      </dl>
      {item.canApprove ? (
        <div className="queue__preview-actions">
          <a className="btn" href={review}><span aria-hidden="true">✓</span> {t('approvals.reviewAndApprove')}</a>
        </div>
      ) : (
        <div className="queue__decision">
          <p className="reason-line" id={reasonId}><span aria-hidden="true">⊘</span> {t(denialMessage(item.approveDenied ?? 'UNAVAILABLE'))}</p>
          <div className="queue__preview-actions">
            <button type="button" className="btn" disabled aria-describedby={reasonId}><span aria-hidden="true">✓</span> {t('approvals.approve')}</button>
            <a className="btn btn--quiet" href={review}>{t('approvals.openChanges')} <span aria-hidden="true">→</span></a>
            {item.canWithdraw ? (
              <button type="button" className="btn btn--quiet" onClick={() => { onWithdraw(item); }}>{t('approvals.withdraw')}</button>
            ) : null}
          </div>
        </div>
      )}
    </article>
  );
}
