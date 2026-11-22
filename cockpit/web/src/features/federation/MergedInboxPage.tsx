import { useState } from 'react';
import type { TaskSummary } from '../../api/processes/schemas';
import { useFederation } from '../../app/federation/federation';
import { formatRoute, useRoute } from '../../app/route';
import { EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useKeepInView } from '../../components/useKeepInView';
import { useI18n } from '../../i18n/I18nProvider';
import { Toast } from '../rules/components/Toast';
import { useWantNames, wantedOf } from '../processes/api/displayNames';
import { slaOf, WORK } from '../tasks/model/queue';
import { AssigneeText, DueText, SlaMark, StatusMark, taskTitle } from '../tasks/parts';
import { TaskInspector, type Notice } from '../tasks/TaskInspector';
import { ErrorAlert } from '../../components/PageParts';
import { HostScope } from './HostScope';
import { byDueTime, type Row } from './merge';
import { HostTag, IncompleteNotice, ScopeSwitch } from './parts';
import { useMergedList, type MergedSpec } from './useMergedList';
import '../../components/tasks.css';

/*
 * One task inbox across hosts (CK-F-28, CKP-019): the tasks of every host the user can reach, in exact due-date
 * order, each row labelled with its host. A host that fails is named in a banner and the rest stay. The inspector and
 * its actions run as the row's host, so four eyes, masking and the engine's allowed actions are that host's own.
 */

export const INBOX_LIMIT = 50;

const SPEC: Omit<MergedSpec<TaskSummary>, 'refreshSeconds'> = {
  kind: 'tasks',
  variant: 'inbox',
  permission: WORK,
  pageLimit: INBOX_LIMIT,
  compare: byDueTime,
  readPage: (client, { cursor, signal }, etag) => client.readTasks({ assignment: ['mine', 'candidate'], status: ['OPEN', 'CLAIMED'], limit: INBOX_LIMIT, cursor, signal }, etag),
};

export function MergedInboxPage({ taskId, on, refreshSeconds }: { taskId: string | undefined; on: string | undefined; refreshSeconds: number }) {
  const { t } = useI18n();
  const federation = useFederation();
  const [, navigate] = useRoute();
  const list = useMergedList<TaskSummary>({ ...SPEC, refreshSeconds });
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  const [toast, setToast] = useState<string | undefined>(undefined);
  const rows = list.status === 'ready' ? list.rows : [];
  useWantNames(wantedOf(rows.map((row) => row.item.assignee), rows.map((row) => row.item.claimedBy)));
  const selected = rows.find((row) => row.item.id === taskId && (on === undefined || row.hostId === on)) ?? rows[0];
  const selectedHost = federation?.hosts.find((host) => host.id === selected?.hostId);
  const labelOf = (hostId: string) => federation?.hosts.find((host) => host.id === hostId)?.label ?? hostId;

  return (
    <section className="workspace tasks" aria-labelledby="tasks-title">
      <PageHead id="tasks-title" title={t('tasks.title')} subtitle={t('federation.tasks.subtitle')} action={<ScopeSwitch area="tasks" merged />} />
      {notice !== undefined ? (
        <ErrorAlert className="tasks__notice" text={notice.text} error={notice.error}
          action={<button type="button" className="btn btn--quiet btn--small hit" onClick={() => { setNotice(undefined); }}>{t('toast.close')}</button>} />
      ) : null}
      {list.status === 'loading' ? (
        <article className="card"><LoadingRows rows={5} /></article>
      ) : list.status === 'none' ? (
        <article className="card tasks__empty"><EmptyState icon="tasks" text={t('federation.none')} /></article>
      ) : (
        <>
          <IncompleteNotice hosts={list.incomplete} onRetry={list.refresh} />
          {rows.length === 0 ? (
            <article className="card tasks__empty">
              <EmptyState icon="tasks" text={list.answered.length === 0 ? t('federation.allFailed') : t('federation.tasks.empty')} />
            </article>
          ) : (
            <div className="tasks__grid">
              <InboxTable rows={rows} hasMore={list.hasMore} loadingMore={list.loadingMore} onMore={list.loadMore} labelOf={labelOf}
                selected={selected} onSelect={(row) => { navigate({ name: 'tasks', scope: 'all', on: row.hostId, taskId: row.item.id }); }} />
              {selected !== undefined && selectedHost?.state === 'ready' ? (
                <HostScope host={selectedHost} key={`${selected.hostId}|${selected.item.id}`}>
                  <TaskInspector summary={selected.item} overdue={undefined} onNotice={setNotice} onDone={(text) => { setNotice(undefined); setToast(text); }} />
                </HostScope>
              ) : null}
            </div>
          )}
        </>
      )}
      <Toast text={toast} onClose={() => { setToast(undefined); }} />
    </section>
  );
}

function InboxTable({ rows, hasMore, loadingMore, onMore, labelOf, selected, onSelect }: {
  rows: readonly Row<TaskSummary>[];
  hasMore: boolean;
  loadingMore: boolean;
  onMore: () => void;
  labelOf: (hostId: string) => string;
  selected: Row<TaskSummary> | undefined;
  onSelect: (row: Row<TaskSummary>) => void;
}) {
  const { t } = useI18n();
  const selectedRow = useKeepInView<HTMLTableRowElement>(selected?.item.id);
  const shown = hasMore ? t('tasks.more', { count: String(rows.length) }) : String(rows.length);
  const count = rows.length === 1 && !hasMore ? t('tasks.summaryOne') : t('tasks.summary', { count: shown });
  return (
    <article className="card card--table tasks__list" aria-labelledby="tasks-list-title">
      <div className="card__head card__head--padded"><h2 id="tasks-list-title" className="tasks__count"><span className="tnum">{count}</span></h2></div>
      <table className="qtable qtable--links tasks__table">
        <thead>
          <tr>
            <th scope="col">{t('tasks.col.task')}</th>
            <th scope="col">{t('federation.col.service')}</th>
            <th scope="col">{t('tasks.col.assignee')}</th>
            <th scope="col">{t('tasks.col.due')}</th>
            <th scope="col">{t('tasks.col.status')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const { item, hostId } = row;
            const isSelected = selected?.item.id === item.id && selected.hostId === hostId;
            return (
              <tr key={`${hostId}|${item.id}`} className={isSelected ? 'is-selected' : undefined} ref={isSelected ? selectedRow : undefined}>
                <th scope="row">
                  <a className="qtable__link tasks__name" href={formatRoute({ name: 'tasks', scope: 'all', on: hostId, taskId: item.id })} aria-current={isSelected ? 'true' : undefined}
                    onClick={(event) => { event.preventDefault(); onSelect(row); }}>
                    {taskTitle(item, t)}
                  </a>
                  {item.businessKey ? <span className="tasks__case">{t('tasks.case', { key: item.businessKey })}</span> : null}
                </th>
                <td><HostTag label={labelOf(hostId)} /></td>
                <td><AssigneeText assignee={item.assignee} /></td>
                <td><span className="tasks__due"><DueText dueAt={item.dueAt} /><SlaMark sla={slaOf(item, undefined)} overdueBy={item.overdueBy} /></span></td>
                <td><StatusMark status={item.status} claimedBy={item.claimedBy} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {hasMore ? (
        <div className="merged__more">
          <button type="button" className="btn btn--quiet hit" disabled={loadingMore} onClick={onMore}>{loadingMore ? t('federation.loadMore.busy') : t('federation.loadMore')}</button>
        </div>
      ) : null}
    </article>
  );
}
