import type { SlaState, Task, TaskSummary } from '../../api/processes/schemas';
import type { Translate } from '../../i18n/I18nProvider';
import { useI18n } from '../../i18n/I18nProvider';
import { useNames } from '../processes/api/displayNames';
import { useProcessesSession } from '../processes/api/processesContext';
import { formatDateTime } from '../rules/model/dates';
import { overdueParts } from './model/queue';

/** The task's step name; a task without one reads "Untitled task", never its node id. */
export function taskTitle(task: Pick<TaskSummary, 'name'>, t: Translate): string {
  return task.name !== null && task.name !== undefined && task.name.trim() !== '' ? task.name : t('tasks.unnamed');
}

/** Who a task is for, in words: "Role: Credit officer", "Group: Loans operations", or a person's name. */
export function useAssigneeLabel(): (assignee: string) => string {
  return useNames().assignee;
}

export function AssigneeText({ assignee }: { assignee: string }) {
  const label = useAssigneeLabel();
  return <span className="tasks__assignee">{label(assignee)}</span>;
}

export function DueText({ dueAt }: { dueAt: string | null | undefined }) {
  const { t, locale } = useI18n();
  const when = formatDateTime(dueAt, locale);
  return when === undefined ? <span className="muted">{t('tasks.sla.none')}</span> : <span className="tnum">{when}</span>;
}

/** "2 h 30 min" from the engine's overdueBy, in the user's language; undefined when the engine sent none. */
export function useOverdueText(): (overdueBy: string | null | undefined) => string | undefined {
  const { t } = useI18n();
  return (overdueBy) => {
    const parts = overdueParts(overdueBy);
    if (parts === undefined) return undefined;
    const words = [
      ...(parts.days > 0 ? [t('tasks.duration.days', { count: String(parts.days) })] : []),
      ...(parts.hours > 0 ? [t('tasks.duration.hours', { count: String(parts.hours) })] : []),
      ...(parts.minutes > 0 ? [t('tasks.duration.minutes', { count: String(parts.minutes) })] : []),
    ];
    return words.length > 0 ? words.join(' ') : undefined;
  };
}

/**
 * The deadline state, calm: overdue is amber text with an icon; due soon is a word with a clock in the
 * text colour; on time and no deadline add nothing beside the due time.
 */
export function SlaMark({ sla, overdueBy }: { sla: SlaState; overdueBy: string | null | undefined }) {
  const { t } = useI18n();
  const overdueText = useOverdueText();
  if (sla === 'BREACHED') {
    const time = overdueText(overdueBy);
    return <span className="status status--warn"><span aria-hidden="true">▲</span> {time !== undefined ? t('tasks.sla.overdueBy', { time }) : t('tasks.sla.overdue')}</span>;
  }
  if (sla === 'DUE_SOON') return <span className="tasks__soon"><span aria-hidden="true">◷</span> {t('tasks.sla.dueSoon')}</span>;
  return null;
}

export function StatusMark({ status, claimedBy }: { status: Task['status']; claimedBy: string | null | undefined }) {
  const { t } = useI18n();
  const { user: personName } = useNames();
  const { userId } = useProcessesSession();
  if (status === 'CLAIMED') {
    const mine = claimedBy === userId;
    return (
      <span className={mine ? 'status status--accent' : 'status'}>
        <span aria-hidden="true">●</span> {mine ? t('tasks.status.claimedByYou') : t('tasks.status.claimedBy', { who: personName(claimedBy ?? '') })}
      </span>
    );
  }
  if (status === 'COMPLETED') return <span className="status status--ok"><span aria-hidden="true">✓</span> {t('tasks.status.completed')}</span>;
  if (status === 'CANCELLED') return <span className="status"><span aria-hidden="true">■</span> {t('tasks.status.cancelled')}</span>;
  return <span className="status"><span aria-hidden="true">○</span> {t('tasks.status.open')}</span>;
}
