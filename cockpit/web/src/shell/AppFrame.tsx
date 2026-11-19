import { useEffect, useRef } from 'react';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import type { RegistryHost } from '../app/config/registry';
import { availableAreas, landingArea } from '../app/navigation';
import { areaOf, areaRoute, formatRoute, useRoute, type Area, type Route } from '../app/route';
import { EmptyState, PageHead } from '../components/PageParts';
import { ApprovalsPage } from '../features/approvals/ApprovalsPage';
import { DecisionsPage } from '../features/decisions/DecisionsPage';
import { HomePage } from '../features/home/HomePage';
import { ProcessesPage } from '../features/processes/ProcessesPage';
import { RulesProvider } from '../features/rules/api/rulesContext';
import { RulesArea } from '../features/rules/RulesArea';
import { useI18n } from '../i18n/I18nProvider';
import { NavRail } from './NavRail';
import '../components/frame.css';

/**
 * The host's authoring.cockpit-drafts setting from the rules module of /meta (PD-075, PD-036). On by default;
 * a host that keeps production rule changes in Git turns it off and the cockpit offers no editing.
 */
export function cockpitDraftsOn(meta: BpmMetaV1): boolean {
  const settings = meta.modules.find((module) => module.module === 'rules')?.settings ?? {};
  const value = settings['authoring.cockpit-drafts'] ?? settings['rules.authoring.cockpit-drafts'];
  return value !== false && value !== 'false';
}

export interface FrameProps {
  host: RegistryHost;
  meta: BpmMetaV1;
  me: BpmMeV1;
  fetchImpl?: typeof fetch | undefined;
}

/** The signed-in cockpit: the menu of the areas this user may use and the current area beside it. */
export function AppFrame({ host, meta, me, fetchImpl }: FrameProps) {
  const areas = availableAreas(meta, me);
  const [route] = useRoute(areaRoute(landingArea(meta, me, areas)));
  const area = areaOf(route);
  const main = useFocusOnNavigation(formatRoute(route));
  return (
    <RulesProvider baseUrl={host.baseUrl} hostId={host.id} me={me} cockpitDrafts={cockpitDraftsOn(meta)} fetchImpl={fetchImpl}>
      <div className="frame">
        <NavRail areas={areas} current={area} />
        <main id="main" ref={main} className="main main--framed" tabIndex={-1}>
          {areas.includes(area) ? <AreaContent route={route} meta={meta} areas={areas} /> : <NotAvailable />}
        </main>
      </div>
    </RulesProvider>
  );
}

function AreaContent({ route, meta, areas }: { route: Route; meta: BpmMetaV1; areas: readonly Area[] }) {
  const pageLimit = meta.limits.maxPageSize;
  switch (route.name) {
    case 'home':
      return <HomePage pageLimit={pageLimit} showApprovals={areas.includes('approvals')} />;
    case 'rule-sets':
    case 'rule-set':
      return <RulesArea route={route} pageLimit={pageLimit} />;
    case 'approvals':
      return <ApprovalsPage pageLimit={pageLimit} />;
    case 'decisions':
      return <DecisionsPage rulesAvailable={areas.includes('rules')} />;
    case 'processes':
      return <ProcessesPage module={meta.modules.find((module) => module.module === 'processes')} />;
  }
}

/** A link to an area this user may not use, or one this host does not run: say so and offer Home. */
function NotAvailable() {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="unavailable-title">
      <PageHead id="unavailable-title" title={t('area.unavailable.title')} subtitle={t('area.unavailable.subtitle')} />
      <article className="card">
        <EmptyState text={t('area.unavailable.body')} action={{ label: t('menu.home'), href: formatRoute({ name: 'home' }) }} />
      </article>
    </section>
  );
}

/** After the user moves to another screen, focus goes to the main region, so keyboard and reader users start there. */
function useFocusOnNavigation(location: string) {
  const main = useRef<HTMLElement>(null);
  const previous = useRef(location);
  useEffect(() => {
    if (previous.current === location) return;
    previous.current = location;
    main.current?.focus({ preventScroll: false });
  }, [location]);
  return main;
}
