import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';

const PERMISSION_ORDER = [
  'rules:read',
  'rules:author',
  'rules:approve',
  'rules:simulate',
  'rules:log:read',
  'rules:evaluate',
  'processes:read',
  'processes:start',
  'processes:deploy',
  'processes:task:work',
  'processes:task:manage',
  'processes:incident:manage',
  'processes:migration:manage',
  'processes:calendar:manage',
] as const;

type KnownPermission = (typeof PERMISSION_ORDER)[number];

/** Known permissions in a stable order, as human labels. A permission this cockpit does not know is not shown. */
export function knownPermissions(granted: readonly string[]): KnownPermission[] {
  const set = new Set(granted);
  return PERMISSION_ORDER.filter((permission) => set.has(permission));
}

const MODULE_LABEL: Record<BpmMetaV1['modules'][number]['module'], MessageKey> = {
  rules: 'module.rules',
  processes: 'module.processes',
};

export function ServiceOverview({ meta, me }: { meta: BpmMetaV1; me: BpmMeV1 }) {
  const { t } = useI18n();
  const permissions = knownPermissions(me.permissions);
  return (
    <section className="workspace" aria-labelledby="overview-title">
      <div className="page-head">
        <h1 id="overview-title">{t('overview.title')}</h1>
        <p className="page-head__sub">{t('overview.subtitle')}</p>
      </div>
      <div className="bento">
        <article className="card" aria-labelledby="engines-title">
          <h2 id="engines-title" className="card__title">{t('overview.engines')}</h2>
          <ul className="engine-list">
            {meta.modules.map((module) => (
              <li key={module.module} className="engine">
                <span className="status-dot status-dot--ok" aria-hidden="true" />
                <span className="engine__name">{t(MODULE_LABEL[module.module])}</span>
                <span className="engine__meta tnum">{t('overview.version', { version: module.engineVersion })}</span>
                <span className="chip">{t('overview.api', { apiVersion: module.apiVersion })}</span>
              </li>
            ))}
          </ul>
        </article>
        <article className="card" aria-labelledby="access-title">
          <h2 id="access-title" className="card__title">{t('overview.access')}</h2>
          {permissions.length === 0 ? (
            <p className="empty">{t('overview.noAccess')}</p>
          ) : (
            <ul className="pill-list" aria-label={t('overview.access')}>
              {permissions.map((permission) => (
                <li key={permission} className="pill">
                  <span aria-hidden="true">✓</span> {t(`permission.${permission}`)}
                </li>
              ))}
            </ul>
          )}
        </article>
      </div>
    </section>
  );
}
