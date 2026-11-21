import { useEffect, useRef } from 'react';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import type { RegistryHost } from '../app/config/registry';
import { availableAreas, landingArea, landingNote } from '../app/navigation';
import { areaOf, areaRoute, formatRoute, useRoute, type Area, type Route } from '../app/route';
import { EmptyState, PageHead } from '../components/PageParts';
import { ApprovalsPage } from '../features/approvals/ApprovalsPage';
import { DecisionPage } from '../features/decisions/DecisionPage';
import { DecisionsPage } from '../features/decisions/DecisionsPage';
import { HomePage } from '../features/home/HomePage';
import { ProcessesProvider } from '../features/processes/api/processesContext';
import { DefinitionPage } from '../features/processes/DefinitionPage';
import { ProcessesPage } from '../features/processes/ProcessesPage';
import { ReviewTaskPage } from '../features/processes/review/ReviewTaskPage';
import { RulesProvider } from '../features/rules/api/rulesContext';
import { RulesArea } from '../features/rules/RulesArea';
import { useI18n } from '../i18n/I18nProvider';
import { PeopleProvider } from '../app/people';
import { queueEngines, reviewCount, useApprovalQueue } from '../features/approvals/useApprovalQueue';
import type { Engine } from '../features/approvals/queue';
import { NavRail } from './NavRail';
import '../components/frame.css';

/**
 * The host's authoring.cockpit-drafts setting from the rules module of /meta (PD-075, PD-036, PD-094).
 * Off by default in 0.1.0 (PD-094): the cockpit edits drafts only where the host says true. Import from a
 * file is not affected.
 */
export function cockpitDraftsOn(meta: BpmMetaV1): boolean {
  const settings = meta.modules.find((module) => module.module === 'rules')?.settings ?? {};
  const value = settings['authoring.cockpit-drafts'] ?? settings['rules.authoring.cockpit-drafts'];
  // Off by default in 0.1.0 (PD-094)
  return value === true || value === 'true';
}

export interface FrameProps {
  host: RegistryHost;
  meta: BpmMetaV1;
  me: BpmMeV1;
  fetchImpl?: typeof fetch | undefined;
}

/** The signed-in cockpit: the menu of the areas this user may use and the current area beside it. */
export function AppFrame({ host, meta, me, fetchImpl }: FrameProps) {
  return (
    <RulesProvider baseUrl={host.baseUrl} hostId={host.id} me={me} cockpitDrafts={cockpitDraftsOn(meta)} fetchImpl={fetchImpl}>
      <ProcessesProvider baseUrl={host.baseUrl} hostId={host.id} me={me} fetchImpl={fetchImpl}>
        <PeopleProvider me={me.user}>
          <Frame meta={meta} me={me} />
        </PeopleProvider>
      </ProcessesProvider>
    </RulesProvider>
  );
}

function Frame({ meta, me }: { meta: BpmMetaV1; me: BpmMeV1 }) {
  const areas = availableAreas(meta, me);
  const [route] = useRoute(areaRoute(landingArea(meta, areas)));
  const area = areaOf(route);
  const main = useFocusOnNavigation(formatRoute(route));
  const engines = areas.includes('approvals') ? queueEngines(meta, me) : NO_ENGINES;
  const review = useReviewCount(engines, meta.limits.maxPageSize);
  const note = landingNote(meta);
  useEffect(() => {
    // A start-up log line for the host's operators; the user simply lands on Home.
    if (note !== undefined) console.warn(note);
  }, [note]);
  return (
    <div className="frame">
      <NavRail areas={areas} current={area} reviewCount={review.count} reviewMore={review.more} />
      <main id="main" ref={main} className="main main--framed" tabIndex={-1}>
        {areas.includes(area) ? <AreaContent route={route} meta={meta} me={me} areas={areas} engines={engines} /> : <NotAvailable />}
      </main>
    </div>
  );
}

function AreaContent({ route, meta, me, areas, engines }: { route: Route; meta: BpmMetaV1; me: BpmMeV1; areas: readonly Area[]; engines: readonly Engine[] }) {
  const pageLimit = meta.limits.maxPageSize;
  switch (route.name) {
    case 'home':
      return <HomePage pageLimit={pageLimit} me={me} areas={areas} engines={engines} />;
    case 'rule-sets':
    case 'rule-set':
      return <RulesArea route={route} pageLimit={pageLimit} />;
    case 'approvals':
      return <ApprovalsPage engines={engines} pageLimit={pageLimit} />;
    case 'decisions':
      return <DecisionsPage query={route.query ?? {}} pageLimit={pageLimit} />;
    case 'decision':
      return <DecisionPage decisionId={route.decisionId} />;
    case 'processes':
      return <ProcessesPage pageLimit={pageLimit} />;
    case 'process':
      return <DefinitionPage definitionKey={route.key} version={route.version} lens={route.lens} />;
    case 'task':
      return <ReviewTaskPage taskId={route.taskId} />;
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

const NO_ENGINES: readonly Engine[] = [];

/** How many requests this user may approve now, across both engines: the count on the Approvals menu item. */
function useReviewCount(engines: readonly Engine[], pageLimit: number): { count: number; more: boolean } {
  return reviewCount(useApprovalQueue(engines, pageLimit).state);
}
