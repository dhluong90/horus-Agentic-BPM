import type { Incident } from '../../api/processes/schemas';
import { usePersonName } from '../../app/people';
import { useI18n } from '../../i18n/I18nProvider';
import { fieldLabel } from '../processes/model/review';

/** The step an incident stopped on: its name in the process when known, otherwise its id in words. */
export function stepName(incident: Pick<Incident, 'nodeId'>, known?: string): string {
  return known ?? fieldLabel(incident.nodeId);
}

/** Where the incident stands, as a word and an icon. */
/** `compact` is for table cells: the proposer is named in the inspector beside the list. */
export function IncidentState({ incident, compact = false }: { incident: Incident; compact?: boolean }) {
  const { t } = useI18n();
  const personName = usePersonName();
  if (incident.status === 'RESOLUTION_PENDING') {
    return <span className="status status--accent"><span aria-hidden="true">◷</span> {compact ? t('incidents.state.pendingShort') : t('incidents.state.pending', { who: personName(incident.pendingResolution?.proposedBy ?? '') })}</span>;
  }
  if (incident.retrying) return <span className="status status--accent"><span aria-hidden="true">↻</span> {compact ? t('incidents.state.retryingShort') : t('incidents.state.retrying')}</span>;
  if (incident.status === 'RESOLVED') return <span className="status status--ok"><span aria-hidden="true">✓</span> {t('incidents.state.resolved')}</span>;
  return <span className="status"><span aria-hidden="true">○</span> {t('incidents.state.open')}</span>;
}

