import { useEffect } from 'react';
import type { Task } from '../../../api/processes/schemas';
import { setCrumbTrail } from '../../../app/crumbs';
import { formatRoute } from '../../../app/route';
import { useI18n } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { formatDateTime } from '../../rules/model/dates';
import { useProcessesSession } from '../api/processesContext';
import { useDefinitionVersion } from '../api/useProcesses';

/**
 * The way back from the review: Processes / the process / the case / Agent review. The process crumb waits for its
 * display name rather than show the key; the case crumb reads "Case APP-1042", or "Case" without a business key.
 */
export function useReviewCrumbs(task: Task, last?: string) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const definition = useDefinitionVersion(task.definitionKey, task.definitionVersion, can('processes:read'));
  const processName = definition.data?.name ?? undefined;
  const caseLabel = task.businessKey ? t('instance.title', { key: task.businessKey }) : t('instance.crumb');
  const reviewLabel = last ?? t('review.crumb');
  const readable = can('processes:read');
  useEffect(() => {
    setCrumbTrail([
      ...(processName !== undefined ? [{ label: processName, href: formatRoute({ name: 'process', key: task.definitionKey, version: task.definitionVersion, lens: 'cases' }) }] : []),
      ...(readable ? [{ label: caseLabel, href: formatRoute({ name: 'instance', instanceId: task.instanceId }) }] : []),
      { label: reviewLabel },
    ]);
  }, [processName, caseLabel, reviewLabel, readable, task.definitionKey, task.definitionVersion, task.instanceId]);
  useEffect(() => () => { setCrumbTrail(undefined); }, []);
}

/** Which case, step and process the review belongs to, by their names; a key is shown only when no name is known. */
export function ReviewMeta({ task, createdKey = 'review.created' }: { task: Task; createdKey?: MessageKey }) {
  const { t, locale } = useI18n();
  const { can } = useProcessesSession();
  const definition = useDefinitionVersion(task.definitionKey, task.definitionVersion, can('processes:read'));
  const processName = definition.data?.name ?? undefined;
  return (
    <p className="review__meta">
      {task.businessKey ? <span>{t('review.case', { key: task.businessKey })}</span> : null}
      {task.name ? <span>{t('review.step', { name: task.name })}</span> : null}
      {processName !== undefined ? <span>{t('review.process', { name: processName, version: String(task.definitionVersion) })}</span> : null}
      <span>{t(createdKey, { when: formatDateTime(task.createdAt, locale) ?? '' })}</span>
    </p>
  );
}
