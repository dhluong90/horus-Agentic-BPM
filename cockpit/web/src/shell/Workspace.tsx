import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import { useRoute, type Route } from '../app/route';
import { ServiceOverview } from '../components/ServiceOverview';
import { RulesProvider } from '../features/rules/api/rulesContext';
import { RuleSetList } from '../features/rules/RuleSetList';
import { RuleSetPage } from '../features/rules/RuleSetPage';
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

function Content({ route, navigate, meta, me, rules }: { route: Route; navigate: (route: Route) => void; meta: BpmMetaV1; me: BpmMeV1; rules: boolean }) {
  if (!rules || route.name === 'service') return <ServiceOverview meta={meta} me={me} />;
  if (route.name === 'rule-set') return <RuleSetPage name={route.ruleSet} versionNumber={route.version} lens={route.lens} navigate={navigate} />;
  return <RuleSetList navigate={navigate} />;
}

export function Workspace({ hostId, baseUrl, meta, me, fetchImpl }: { hostId: string; baseUrl: string; meta: BpmMetaV1; me: BpmMeV1; fetchImpl?: typeof fetch | undefined }) {
  const { t } = useI18n();
  const [route, navigate] = useRoute();
  const rules = rulesAvailable(meta, me);
  const area = !rules || route.name === 'service' ? 'service' : 'rules';
  return (
    <RulesProvider baseUrl={baseUrl} hostId={hostId} me={me} cockpitDrafts={cockpitDraftsOn(meta)} fetchImpl={fetchImpl}>
      <div className="layout">
        <nav className="rail" aria-label={t('nav.label')}>
          {rules ? (
            <a className="rail__item hit" href="#/rule-sets" aria-current={area === 'rules' ? 'page' : undefined}>
              <span aria-hidden="true" className="rail__glyph">▦</span> {t('nav.ruleSets')}
            </a>
          ) : null}
          <a className="rail__item hit" href="#/service" aria-current={area === 'service' ? 'page' : undefined}>
            <span aria-hidden="true" className="rail__glyph">◎</span> {t('nav.service')}
          </a>
        </nav>
        <div className="layout__content">
          <Content route={route} navigate={navigate} meta={meta} me={me} rules={rules} />
        </div>
      </div>
    </RulesProvider>
  );
}
