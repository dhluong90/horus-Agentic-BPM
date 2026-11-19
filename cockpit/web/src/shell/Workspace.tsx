import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import { useCallback } from 'react';
import { formatRoute, useRoute } from '../app/route';
import { ServiceOverview } from '../components/ServiceOverview';
import { RulesArea } from '../features/rules/RulesArea';
import { useI18n } from '../i18n/I18nProvider';

/** Areas follow what the host runs (/meta) and what the user may do (/me); nothing is hard-coded per role. */
function rulesAvailable(meta: BpmMetaV1, me: BpmMeV1): boolean {
  return meta.modules.some((module) => module.module === 'rules') && me.permissions.includes('rules:read');
}

/**
 * The host's authoring.cockpit-drafts setting from the rules module of /meta (PD-075, PD-036). On by default;
 * a host that keeps production rule changes in Git turns it off and the cockpit offers no editing.
 */
export function cockpitDraftsOn(meta: BpmMetaV1): boolean {
  const settings = meta.modules.find((module) => module.module === 'rules')?.settings ?? {};
  const value = settings['authoring.cockpit-drafts'] ?? settings['rules.authoring.cockpit-drafts'];
  return value !== false && value !== 'false';
}

export function Workspace({ hostId, baseUrl, meta, me, fetchImpl }: { hostId: string; baseUrl: string; meta: BpmMetaV1; me: BpmMeV1; fetchImpl?: typeof fetch | undefined }) {
  const { t } = useI18n();
  const [route, navigate] = useRoute();
  const rules = rulesAvailable(meta, me);
  const area = !rules || route.name === 'service' ? 'service' : 'rules';
  const go = useCallback((path: string) => { navigate({ name: 'rules', path }); }, [navigate]);
  const href = useCallback((path: string) => formatRoute({ name: 'rules', path }), []);
  return (
    <div className="layout">
      <nav className="rail" aria-label={t('nav.label')}>
        {rules ? (
          <a className="rail__item hit" href="#/rules" aria-current={area === 'rules' ? 'page' : undefined}>
            <span aria-hidden="true" className="rail__glyph">▦</span> {t('nav.ruleSets')}
          </a>
        ) : null}
        <a className="rail__item hit" href="#/service" aria-current={area === 'service' ? 'page' : undefined}>
          <span aria-hidden="true" className="rail__glyph">◎</span> {t('nav.service')}
        </a>
      </nav>
      <div className="layout__content">
        {area === 'rules' && route.name === 'rules' ? (
          <RulesArea path={route.path} navigate={go} href={href} baseUrl={baseUrl} hostId={hostId} me={me} cockpitDrafts={cockpitDraftsOn(meta)} fetchImpl={fetchImpl} />
        ) : (
          <ServiceOverview meta={meta} me={me} />
        )}
      </div>
    </div>
  );
}
