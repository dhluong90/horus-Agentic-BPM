import { useEffect, useRef } from 'react';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import type { RegistryHost } from '../app/config/registry';
import { availableAreas, landingArea, landingNote } from '../app/navigation';
import { areaOf, areaRoute, formatRoute, useRoute, type Area, type Route } from '../app/route';
import { EmptyState, PageHead } from '../components/PageParts';
import { ApprovalsPage } from '../features/approvals/ApprovalsPage';
import { DecisionPage } from '../features/decisions/DecisionPage';
import { DecisionLogLocked, DecisionsPage } from '../features/decisions/DecisionsPage';
import { HomePage } from '../features/home/HomePage';
import { DisplayNamesProvider } from '../features/processes/api/displayNames';
import { ProcessesProvider } from '../features/processes/api/processesContext';
import { DefinitionPage } from '../features/processes/DefinitionPage';
import { MigrationBuilder } from '../features/processes/migration/MigrationBuilder';
import { CalendarChangesPage } from '../features/processes/calendars/CalendarChangesPage';
import { MigrationPage } from '../features/processes/migration/MigrationPage';
import { InstancePage } from '../features/processes/InstancePage';
import { ProcessesPage } from '../features/processes/ProcessesPage';
import { ReviewTaskPage } from '../features/processes/review/ReviewTaskPage';
import { TasksPage } from '../features/tasks/TasksPage';
import { useFederation } from '../app/federation/federation';
import { MergedIncidentsPage } from '../features/federation/MergedIncidentsPage';
import { MergedInboxPage } from '../features/federation/MergedInboxPage';
import { IncidentsPage } from '../features/incidents/IncidentsPage';
import { resolveMode } from '../features/incidents/model/incidents';
import { RulesProvider, type ReportHostSettings } from '../features/rules/api/rulesContext';
import { RulesArea } from '../features/rules/RulesArea';
import { useI18n } from '../i18n/I18nProvider';
import { PeopleProvider } from '../app/people';
import { queueEngines, reviewCount, useApprovalQueue } from '../features/approvals/useApprovalQueue';
import type { Engine } from '../features/approvals/queue';
import { NavRail } from './NavRail';
import { hostReadingStyle } from '../preferences/readingStyle';
import type { ReadingStyle } from '../preferences/storage';
import '../components/frame.css';
import '../components/lists.css';

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

/** The host's reading style for rule readings, from the rules module of /meta (PD-077). */
export function readingStyleOf(meta: BpmMetaV1): ReadingStyle {
  return hostReadingStyle(meta.modules.find((module) => module.module === 'rules')?.settings ?? {});
}

/**
 * What the rules module of /meta says about templates and reports (PD-112, PD-115): the features the screens wait for,
 * and `lifecycle.require-simulation-report`, the comma-separated rule sets whose publication request must carry a report.
 */
export function reportHostOf(meta: BpmMetaV1): ReportHostSettings {
  const module = meta.modules.find((entry) => entry.module === 'rules');
  const required = module?.settings?.['lifecycle.require-simulation-report'];
  return {
    templates: module?.features.includes('rules.templates') === true,
    simulationReports: module?.features.includes('rules.simulation-reports') === true,
    historyReadings: module?.features.includes('rules.history-readings') === true,
    conditionBuilder: module?.features.includes('rules.condition-builder') === true,
    requireReport: typeof required === 'string' ? required.split(',').map((name) => name.trim()).filter((name) => name !== '') : [],
  };
}

export interface FrameProps {
  host: RegistryHost;
  meta: BpmMetaV1;
  me: BpmMeV1;
  fetchImpl?: typeof fetch | undefined;
  /** The registry's `refresh.inboxSeconds`: how often the task queue is read again (30 by default). */
  inboxSeconds?: number | undefined;
  /** The registry's `refresh.incidentsSeconds`: how often the incident queue is read again (60 by default). */
  incidentsSeconds?: number | undefined;
}

/** The signed-in cockpit: the menu of the areas this user may use and the current area beside it. */
export function AppFrame({ host, meta, me, fetchImpl, inboxSeconds = 30, incidentsSeconds = 60 }: FrameProps) {
  return (
    <RulesProvider baseUrl={host.baseUrl} hostId={host.id} me={me} cockpitDrafts={cockpitDraftsOn(meta)} readingStyle={readingStyleOf(meta)} host={reportHostOf(meta)} fetchImpl={fetchImpl}>
      <ProcessesProvider baseUrl={host.baseUrl} hostId={host.id} me={me} fetchImpl={fetchImpl}>
        <PeopleProvider me={me.user}>
          <DisplayNamesProvider>
            <Frame meta={meta} me={me} refresh={{ inboxSeconds, incidentsSeconds }} />
          </DisplayNamesProvider>
        </PeopleProvider>
      </ProcessesProvider>
    </RulesProvider>
  );
}

function Frame({ meta, me, refresh }: { meta: BpmMetaV1; me: BpmMeV1; refresh: Refresh }) {
  const areas = availableAreas(meta, me);
  const [route] = useRoute(areaRoute(landingArea(meta, areas)));
  const area = areaOf(route);
  // Choosing a task in the queue keeps the focus on the list; moving to another screen or tab sends it to main.
  const main = useFocusOnNavigation(formatRoute(route.name === 'tasks' ? { ...route, taskId: undefined } : route));
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
        {areas.includes(area) ? <AreaContent route={route} meta={meta} me={me} areas={areas} engines={engines} refresh={refresh} /> : route.name === 'decision' ? <DecisionLogLocked titleId="decision-refused-title" message="dlog.link.noPermission" /> : <NotAvailable />}
      </main>
    </div>
  );
}

/** How often the queues are read again, from the registry's `refresh` (cockpit DESIGN section 8). */
interface Refresh { inboxSeconds: number; incidentsSeconds: number }

function AreaContent({ route, meta, me, areas, engines, refresh }: { route: Route; meta: BpmMetaV1; me: BpmMeV1; areas: readonly Area[]; engines: readonly Engine[]; refresh: Refresh }) {
  const pageLimit = meta.limits.maxPageSize;
  const merged = useFederation() !== undefined && (route.name === 'tasks' || route.name === 'incidents') && route.scope === 'all';
  switch (route.name) {
    case 'home':
      return <HomePage pageLimit={pageLimit} me={me} areas={areas} engines={engines} refresh={refresh} />;
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
      return <DefinitionPage definitionKey={route.key} version={route.version} lens={route.lens} pageLimit={pageLimit} />;
    case 'migrate':
      return <MigrationBuilder definitionKey={route.key} target={route.version} from={route.from} />;
    case 'migration':
      return <MigrationPage migrationId={route.migrationId} pageLimit={pageLimit} />;
    case 'calendar-changes':
    case 'calendar-change':
      return <CalendarChangesPage changeId={route.name === 'calendar-change' ? route.changeId : undefined} pageLimit={pageLimit} />;
    case 'instance':
      return <InstancePage instanceId={route.instanceId} />;
    case 'tasks':
      if (merged) return <MergedInboxPage taskId={route.taskId} on={route.on} refreshSeconds={refresh.inboxSeconds} />;
      return <TasksPage view={route.view} taskId={route.taskId} overdue={route.overdue === true} refreshSeconds={refresh.inboxSeconds} />;
    case 'task':
      return <ReviewTaskPage taskId={route.taskId} />;
    case 'incidents':
      if (merged) return <MergedIncidentsPage view={route.view} incidentId={route.incidentId} on={route.on} refreshSeconds={refresh.incidentsSeconds} />;
      return <IncidentsPage view={route.view} incidentId={route.incidentId} refreshSeconds={refresh.incidentsSeconds}
        mode={resolveMode(meta.modules.find((module) => module.module === 'processes')?.settings)} />;
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
