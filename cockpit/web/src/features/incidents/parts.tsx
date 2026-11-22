import type { Incident } from '../../api/processes/schemas';
import { usePersonName } from '../../app/people';
import { useI18n } from '../../i18n/I18nProvider';
import { fieldLabel } from '../processes/model/review';

/** The step an incident stopped on: its name in the process when known, otherwise its id in words. */
export function stepName(incident: Pick<Incident, 'nodeId'>, known?: string): string {
  return known ?? fieldLabel(incident.nodeId);
}

/** Where the incident stands, as a word and an icon. */
export function IncidentState({ incident }: { incident: Incident }) {
  const { t } = useI18n();
  const personName = usePersonName();
  if (incident.status === 'RESOLUTION_PENDING') {
    return <span className="badge badge--accent"><span aria-hidden="true">◷</span> {t('incidents.state.pending', { who: personName(incident.pendingResolution?.proposedBy ?? '') })}</span>;
  }
  if (incident.retrying) return <span className="badge badge--accent"><span aria-hidden="true">↻</span> {t('incidents.state.retrying')}</span>;
  if (incident.status === 'RESOLVED') return <span className="badge badge--ok"><span aria-hidden="true">✓</span> {t('incidents.state.resolved')}</span>;
  return <span className="badge badge--neutral"><span aria-hidden="true">○</span> {t('incidents.state.open')}</span>;
}

