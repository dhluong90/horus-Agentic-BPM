import { useId, useState } from 'react';
import type { Task, TaskSummary } from '../../api/processes/schemas';
import { toHostError } from '../../api/hostError';
import { usePersonName } from '../../app/people';
import { formatRoute } from '../../app/route';
import { AreaError, LoadingRows } from '../../components/PageParts';
import { useI18n, type Translate } from '../../i18n/I18nProvider';
import { useProcessesSession } from '../processes/api/processesContext';
import { containsMasked } from '../processes/model/review';
import { formatDateTime } from '../rules/model/dates';
import { useQueueCommand, useQueueTask, type QueueCommand } from './api/useTasks';
import { handOverCommand, refusalOf, slaOf } from './model/queue';
import { AssigneeText, DueText, SlaMark, StatusMark, taskTitle, useAssigneeLabel } from './parts';
import { ReassignDialog } from './ReassignDialog';

/** A refusal shown above the list, so it stays when the task leaves the list. */
export interface Notice {
  text: string;
  /** The engine's answer, so the code and the reference sit behind Details. */
  error?: unknown;
}

/**
 * The selected task: its facts and the actions the engine allows this user now (`allowedActions`), with one primary
 * action. An action the user cannot take is shown unavailable with the reason in words.
 */
export function TaskInspector({ summary, overdue, onNotice, onDone }: {
  summary: TaskSummary;
  overdue: ReadonlySet<string> | undefined;
  onNotice: (notice: Notice | undefined) => void;
  onDone: (text: string) => void;
}) {
  const { t } = useI18n();
  const detail = useQueueTask(summary.id);
  return (
    <aside className="card tasks__inspector" aria-labelledby="task-inspector-title">
      <h2 id="task-inspector-title" className="card__title">{taskTitle(summary, t)}</h2>
      {detail.isPending ? <LoadingRows rows={4} /> : detail.isError ? (
        <AreaError error={detail.error} onRetry={() => { void detail.refetch(); }} />
      ) : (
        <Detail task={detail.data} summary={summary} overdue={overdue} onNotice={onNotice} onDone={onDone} refetch={async () => (await detail.refetch()).data} />
      )}
    </aside>
  );
}

function Detail({ task, summary, overdue, onNotice, onDone, refetch }: {
  task: Task;
  summary: TaskSummary;
  overdue: ReadonlySet<string> | undefined;
  onNotice: (notice: Notice | undefined) => void;
  onDone: (text: string) => void;
  refetch: () => Promise<Task | undefined>;
}) {
  const { t, locale } = useI18n();
  const { userId } = useProcessesSession();
  const personName = usePersonName();
  const assigneeLabel = useAssigneeLabel();
  const command = useQueueCommand(task);
  const [dialog, setDialog] = useState(false);
  const whyId = useId();
  const allowed = new Set(task.allowedActions);
  const title = taskTitle(task, t);
  const handOver = handOverCommand(task);
  const masked = task.review !== undefined && (containsMasked(task.review.inputs) || containsMasked(task.review.output));
  const pending = command.isPending ? command.variables : undefined;

  const run = async (next: QueueCommand) => {
    onNotice(undefined);
    try {
      await command.mutateAsync(next);
      onDone(doneText(next, title, personName, t));
      return true;
    } catch (error) {
      const host = toHostError(error);
      // Read the task again, so a claim lost to a colleague names them.
      const lost = next.kind === 'claim' && (refusalOf(host) === 'claimed-first' || refusalOf(host) === 'changed');
      const fresh = lost ? await refetch() : undefined;
      const text = fresh?.claimedBy && fresh.claimedBy !== userId
        ? t('tasks.refused.claimedFirst', { who: personName(fresh.claimedBy) })
        : refusalText(host, next, fresh, personName, t);
      if (next.kind === 'reassign' || next.kind === 'delegate') throw new RefusedHandOver(text, error);
      onNotice({ text, error });
      return false;
    }
  };

  // What the user cannot do, said in words beside the unavailable action.
  let why: string | undefined;
  if (task.status === 'OPEN' && !allowed.has('claim')) {
    why = task.fourEyes ? t('tasks.why.fourEyes') : handOver === undefined ? t('tasks.why.notYours', { assignee: assigneeLabel(task.assignee) }) : undefined;
  } else if (task.status === 'CLAIMED' && task.claimedBy !== userId && handOver === undefined) {
    why = t('tasks.why.claimedByOther', { who: personName(task.claimedBy ?? '') });
  }
  const mineToWork = task.status === 'CLAIMED' && task.claimedBy === userId;
  const nothing = !allowed.has('claim') && !mineToWork && handOver === undefined && why === undefined;

  return (
    <>
      <div className="tasks__state">
        <StatusMark status={task.status} claimedBy={task.claimedBy} />
        <SlaMark sla={slaOf({ id: task.id, dueAt: task.dueAt ?? summary.dueAt, slaState: summary.slaState }, overdue)} overdueBy={summary.overdueBy} />
      </div>
      <dl className="facts-list tasks__facts">
        {task.businessKey ? <div><dt>{t('tasks.fact.case')}</dt><dd>{task.businessKey}</dd></div> : null}
        <div><dt>{t('tasks.fact.assignee')}</dt><dd><AssigneeText assignee={task.assignee} /></dd></div>
        <div><dt>{t('tasks.fact.holder')}</dt><dd>{task.claimedBy ? (task.claimedBy === userId ? t('tasks.you') : personName(task.claimedBy)) : <span className="muted">{t('tasks.fact.nobody')}</span>}</dd></div>
        <div><dt>{t('tasks.fact.due')}</dt><dd><DueText dueAt={task.dueAt ?? summary.dueAt} /></dd></div>
        <div><dt>{t('tasks.fact.created')}</dt><dd className="tnum">{formatDateTime(task.createdAt, locale)}</dd></div>
      </dl>
      {task.fourEyes && why !== t('tasks.why.fourEyes') ? <p className="tasks__line"><span aria-hidden="true" className="tasks__line-icon">◎</span>{t('tasks.fourEyes')}</p> : null}
      {masked ? <p className="tasks__line"><span aria-hidden="true" className="tasks__line-icon">◍</span>{t('tasks.masked')}</p> : null}
      <div className="tasks__actions">
        {why !== undefined ? <p className="tasks__why" id={whyId}><span aria-hidden="true" className="tasks__line-icon">⊘</span>{why}</p> : null}
        {nothing ? <p className="tasks__why">{t('tasks.why.noAction')}</p> : null}
        <div className="tasks__buttons">
          {allowed.has('claim') ? (
            <button type="button" className="btn btn--primary" disabled={command.isPending} aria-busy={pending?.kind === 'claim'} onClick={() => { void run({ kind: 'claim' }); }}>
              {pending?.kind === 'claim' ? t('tasks.action.claiming') : t('tasks.action.claim')}
            </button>
          ) : why !== undefined && task.status === 'OPEN' ? (
            // Kept in place with its reason, not actionable: aria-disabled keeps it focusable so the reason is read.
            <button type="button" className="btn btn--primary" aria-disabled="true" aria-describedby={whyId} onClick={(event) => { event.preventDefault(); }}>{t('tasks.action.claim')}</button>
          ) : null}
          {mineToWork && allowed.has('complete') ? <a className="btn btn--primary" href={formatRoute({ name: 'task', taskId: task.id })}>{t('tasks.action.open')}</a> : null}
          {mineToWork && allowed.has('unclaim') ? (
            <button type="button" className="btn" disabled={command.isPending} aria-busy={pending?.kind === 'unclaim'} onClick={() => { void run({ kind: 'unclaim' }); }}>
              {pending?.kind === 'unclaim' ? t('tasks.action.releasing') : t('tasks.action.release')}
            </button>
          ) : null}
          {handOver !== undefined ? (
            <button type="button" className="btn" disabled={command.isPending} onClick={() => { onNotice(undefined); setDialog(true); }}>{t('tasks.action.reassign')}</button>
          ) : null}
        </div>
      </div>
      {dialog && handOver !== undefined ? (
        <ReassignDialog
          title={title}
          sending={command.isPending}
          onClose={() => { setDialog(false); }}
          onSubmit={async (handOverBody) => {
            try {
              await run({ kind: handOver, handOver: handOverBody });
              setDialog(false);
              return undefined;
            } catch (error) {
              return error instanceof RefusedHandOver ? { text: error.message, error: error.answer } : { text: t('tasks.refused.other'), error };
            }
          }}
        />
      ) : null}
    </>
  );
}

/** A refused hand-over: its sentence stays in the dialog beside the fields the user fixes. */
class RefusedHandOver extends Error {
  constructor(message: string, readonly answer: unknown) {
    super(message);
  }
}

function doneText(command: QueueCommand, title: string, personName: (login: string) => string, t: Translate): string {
  switch (command.kind) {
    case 'claim':
      return t('tasks.done.claimed', { task: title });
    case 'unclaim':
      return t('tasks.done.released', { task: title });
    case 'reassign':
    case 'delegate':
      return t('tasks.done.handedOver', { task: title, who: personName(command.handOver.assignee) });
  }
}

function refusalText(error: ReturnType<typeof toHostError>, command: QueueCommand, fresh: Task | undefined, personName: (login: string) => string, t: Translate): string {
  const refusal = refusalOf(error);
  switch (refusal) {
    case 'claimed-first':
      if (command.kind !== 'claim') return t('tasks.refused.changed');
      return fresh?.claimedBy ? t('tasks.refused.claimedFirst', { who: personName(fresh.claimedBy) }) : t('tasks.refused.claimedFirstUnknown');
    case 'assignee-not-allowed':
      return t('tasks.refused.assignee', { who: 'handOver' in command ? personName(command.handOver.assignee) : '' });
    case 'changed':
      return t('tasks.refused.changed');
    case 'not-assigned':
      return t('tasks.refused.notAssigned');
    case 'four-eyes':
      return t('tasks.refused.fourEyes');
    case 'not-found':
      return t('tasks.refused.notFound');
    case 'forbidden':
      return t('tasks.refused.forbidden');
    case 'other':
      return t('tasks.refused.other');
  }
}
