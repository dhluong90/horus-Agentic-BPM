import { useState } from 'react';
import type { TaskSummary } from '../../api/processes/schemas';
import { formatRoute, useRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { useProcessesSession } from '../processes/api/processesContext';
import { Tabs } from '../rules/components/Tabs';
import { Toast } from '../rules/components/Toast';
import { useOverdue, useQueue } from './api/useTasks';
import { MANAGE, slaOf, viewsFor, type QueueView } from './model/queue';
import { AssigneeText, DueText, SlaMark, StatusMark, taskTitle } from './parts';
import { TaskInspector, type Notice } from './TaskInspector';
import '../../components/tasks.css';

/*
 * The task queue (CK-F-13, CKP-046 to CKP-048): My tasks, Available to me and, for holders of
 * processes:task:manage, All tasks. One list at a time in the engine's due-time order, the selected task beside it
 * with the actions the engine allows. The tab and the task live in the URL; nothing moves before the engine answers.
 */

const TAB_LABEL: Record<QueueView, MessageKey> = {
  mine: 'tasks.tab.mine',
  candidate: 'tasks.tab.candidate',
  all: 'tasks.tab.all',
};

const EMPTY: Record<QueueView, MessageKey> = {
  mine: 'tasks.empty.mine',
  candidate: 'tasks.empty.candidate',
  all: 'tasks.empty.all',
};

export function TasksPage({ view, taskId, refreshSeconds }: { view: QueueView | undefined; taskId: string | undefined; refreshSeconds: number }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const [, navigate] = useRoute();
  const views = viewsFor(can);
  const active = view !== undefined && views.includes(view) ? view : views[0] ?? 'mine';
  const queues = {
    mine: useQueue('mine', views.includes('mine'), refreshSeconds),
    candidate: useQueue('candidate', views.includes('candidate'), refreshSeconds),
    all: useQueue('all', views.includes('all'), refreshSeconds),
  };
  const overdue = useOverdue(views, refreshSeconds);
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  const [toast, setToast] = useState<string | undefined>(undefined);
  const closeToast = () => { setToast(undefined); };

  const tabLabel = (item: QueueView) => {
    const label = t(TAB_LABEL[item]);
    const data = queues[item].data;
    if (data === undefined) return label;
    const count = data.hasNext ? t('tasks.more', { count: String(data.data.length) }) : String(data.data.length);
    return t('tasks.tab.withCount', { label, count });
  };

  const list = queues[active];
  const items = list.data?.data ?? [];
  const selected = items.find((item) => item.id === taskId) ?? items[0];
  const select = (id: string) => { navigate({ name: 'tasks', view: active, taskId: id }); };

  return (
    <section className="workspace tasks" aria-labelledby="tasks-title">
      <PageHead id="tasks-title" title={t('tasks.title')} subtitle={can(MANAGE) ? t('tasks.subtitleManage') : t('tasks.subtitle')} />
      <Tabs
        label={t('tasks.tabs')}
        items={views.map((item) => ({ id: item, label: tabLabel(item) }))}
        selected={active}
        onSelect={(next) => { setNotice(undefined); navigate({ name: 'tasks', view: next }); }}
        className="tasks__tabs"
      >
        {notice !== undefined ? <NoticeLine notice={notice} onClose={() => { setNotice(undefined); }} /> : null}
        {list.isPending ? (
          <article className="card"><LoadingRows rows={5} /></article>
        ) : list.isError ? (
          <article className="card"><AreaError error={list.error} onRetry={() => { void list.refetch(); }} /></article>
        ) : items.length === 0 ? (
          <article className="card tasks__empty">
            <EmptyState icon="tasks" text={t(EMPTY[active])}
              action={active === 'mine' && views.includes('candidate') ? { label: t('tasks.empty.mineAction'), href: formatRoute({ name: 'tasks', view: 'candidate' }) } : undefined} />
          </article>
        ) : (
          <div className="tasks__grid">
            <QueueTable items={items} hasMore={list.data.hasNext} overdue={overdue.data} selectedId={selected?.id} view={active} onSelect={select} />
            {selected !== undefined ? (
              <TaskInspector key={selected.id} summary={selected} overdue={overdue.data} onNotice={setNotice} onDone={(text) => { setNotice(undefined); setToast(text); }} />
            ) : null}
          </div>
        )}
      </Tabs>
      <Toast text={toast} onClose={closeToast} />
    </section>
  );
}

function NoticeLine({ notice, onClose }: { notice: Notice; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <div className="alert alert--error tasks__notice" role="alert">
      <span className="alert__icon" aria-hidden="true">✕</span>
      <div className="alert__body"><p>{notice.text}</p></div>
      <button type="button" className="btn btn--quiet btn--small hit" onClick={onClose}>{t('toast.close')}</button>
    </div>
  );
}

function QueueTable({ items, hasMore, overdue, selectedId, view, onSelect }: {
  items: readonly TaskSummary[];
  hasMore: boolean;
  overdue: ReadonlySet<string> | undefined;
  selectedId: string | undefined;
  view: QueueView;
  onSelect: (id: string) => void;
}) {
  const { t } = useI18n();
  const late = items.filter((item) => slaOf(item, overdue) === 'BREACHED').length;
  const count = items.length === 1 && !hasMore ? t('tasks.summaryOne') : t('tasks.summary', { count: hasMore ? t('tasks.more', { count: String(items.length) }) : String(items.length) });
  return (
    <article className="card card--table tasks__list" aria-labelledby="tasks-list-title">
      <div className="card__head card__head--padded">
        <h2 id="tasks-list-title" className="tasks__count">
          <span className="tnum">{count}</span>
          {late > 0 ? <span className="tasks__count-late"><span aria-hidden="true">▲</span> <span className="tnum">{t('tasks.summaryOverdue', { count: String(late) })}</span></span> : null}
        </h2>
      </div>
      <table className="qtable qtable--links tasks__table">
        <thead>
          <tr>
            <th scope="col">{t('tasks.col.task')}</th>
            <th scope="col">{t('tasks.col.assignee')}</th>
            <th scope="col">{t('tasks.col.due')}</th>
            <th scope="col">{t('tasks.col.status')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const isSelected = item.id === selectedId;
            return (
              <tr key={item.id} className={isSelected ? 'is-selected' : undefined}>
                <th scope="row">
                  <a className="qtable__link tasks__name" href={formatRoute({ name: 'tasks', view, taskId: item.id })} aria-current={isSelected ? 'true' : undefined}
                    onClick={(event) => { event.preventDefault(); onSelect(item.id); }}>
                    {taskTitle(item, t)}
                  </a>
                  {item.businessKey ? <span className="tasks__case">{t('tasks.case', { key: item.businessKey })}</span> : null}
                </th>
                <td><AssigneeText assignee={item.assignee} /></td>
                <td>
                  <span className="tasks__due">
                    <DueText dueAt={item.dueAt} />
                    <SlaMark sla={slaOf(item, overdue)} overdueBy={item.overdueBy} />
                  </span>
                </td>
                <td><StatusMark status={item.status} claimedBy={item.claimedBy} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </article>
  );
}
