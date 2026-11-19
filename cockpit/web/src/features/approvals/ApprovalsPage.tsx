import { useState } from 'react';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingLine, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { useRulesSession } from '../rules/api/rulesContext';
import { useRetryRules } from '../overview/useRetryRules';
import { usePendingRequests, useEveryRuleSet, type Loadable, type PendingRequest } from '../overview/useRuleOverview';
import { PendingList } from './PendingList';
import { WithdrawDialog } from './WithdrawDialog';

/** The four-eyes queue: requests this user may review, and their own requests, which they may withdraw. */
export function ApprovalsPage({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  const { can } = useRulesSession();
  const retry = useRetryRules();
  const pending = usePendingRequests(useEveryRuleSet(pageLimit));
  const [withdrawing, setWithdrawing] = useState<PendingRequest | undefined>(undefined);
  const [notice, setNotice] = useState('');
  const subtitle = can('rules:approve') ? t('approvals.subtitle') : t('approvals.subtitleAuthor');
  return (
    <section className="workspace" aria-labelledby="approvals-title">
      <PageHead id="approvals-title" title={t('approvals.title')} subtitle={subtitle} />
      <p className="sr-only" role="status" aria-live="polite">{notice}</p>
      <article className="card card--list" aria-labelledby="approvals-queue">
        <div className="card__head">
          <h2 id="approvals-queue" className="card__title">
            {t('approvals.queue')}
            {pending.status === 'ready' ? <span className="count tnum">{pending.data.length}</span> : null}
          </h2>
        </div>
        <QueueBody pending={pending} onRetry={retry} onWithdraw={setWithdrawing} />
      </article>
      {withdrawing !== undefined ? (
        <WithdrawDialog
          key={`${withdrawing.version.ruleSet}/${withdrawing.version.version}`}
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

function QueueBody({ pending, onRetry, onWithdraw }: { pending: Loadable<PendingRequest[]>; onRetry: () => void; onWithdraw: (request: PendingRequest) => void }) {
  const { t } = useI18n();
  if (pending.status === 'loading') return <LoadingLine />;
  if (pending.status === 'error') return <AreaError error={pending.error} onRetry={onRetry} />;
  if (pending.data.length === 0) return <EmptyState text={t('approvals.empty')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} />;
  return <PendingList requests={pending.data} onWithdraw={onWithdraw} />;
}
