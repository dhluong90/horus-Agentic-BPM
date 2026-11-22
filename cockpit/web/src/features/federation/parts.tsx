import { useRoute } from '../../app/route';
import { useFederation } from '../../app/federation/federation';
import { useI18n } from '../../i18n/I18nProvider';
import type { HostName } from './useMergedList';

/** This service or all services; offered only when the cockpit has more than one host to merge (DESIGN section 8). */
export function ScopeSwitch({ area, merged }: { area: 'tasks' | 'incidents'; merged: boolean }) {
  const { t } = useI18n();
  const federation = useFederation();
  const [, navigate] = useRoute();
  if (federation === undefined) return null;
  const go = (all: boolean) => { navigate(all ? { name: area, scope: 'all' } : { name: area }); };
  return (
    <div className="seg seg--small merged__scope" role="group" aria-label={t('federation.scope')}>
      <button type="button" className="hit" aria-pressed={!merged} onClick={() => { go(false); }}>{t('federation.scope.here')}</button>
      <button type="button" className="hit" aria-pressed={merged} onClick={() => { go(true); }}>{t('federation.scope.all')}</button>
    </div>
  );
}

/** Which service a row belongs to, in words. */
export function HostTag({ label }: { label: string }) {
  return <span className="badge badge--neutral merged__host" title={label}>{label}</span>;
}

/** A host that could not be read: named, and the list says it is incomplete. The other hosts' rows stay. */
export function IncompleteNotice({ hosts, onRetry }: { hosts: readonly HostName[]; onRetry: () => void }) {
  const { t } = useI18n();
  if (hosts.length === 0) return null;
  return (
    <div className="alert alert--warn" role="status">
      <span className="alert__icon" aria-hidden="true">▲</span>
      <div className="alert__body"><p>{t('federation.incomplete', { hosts: hosts.map((host) => host.label).join(', ') })}</p></div>
      <button type="button" className="btn btn--quiet btn--small hit" onClick={onRetry}>{t('federation.incomplete.retry')}</button>
    </div>
  );
}
