import { useState } from 'react';
import type { Incident } from '../../api/processes/schemas';
import { formatRoute, useRoute, type IncidentTab, type Route } from '../../app/route';
import { AreaError, EmptyState, ErrorAlert, LoadingRows, PageHead } from '../../components/PageParts';
import { useKeepInView } from '../../components/useKeepInView';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { formatDateTime } from '../rules/model/dates';
import { ScopeSwitch } from '../federation/parts';
import { Tabs } from '../rules/components/Tabs';
import { Toast } from '../rules/components/Toast';
import { INCIDENT_LIMIT, useIncidentList, useStepNames } from './api/useIncidents';
import { IncidentInspector, type IncidentNotice } from './IncidentInspector';
import { readableName } from '../tasks/model/queue';
import { useDefinitionGraph, useDefinitionVersion } from '../processes/api/useProcesses';
import { IncidentState, stepName } from './parts';
import { causeKey, statusOf } from './model/incidents';
import '../../components/tasks.css';
import '../../components/incidents.css';

/*
 * The incident queue (CK-F-15, CKP-008, PD-126): open incidents with their cause and retry count, and resolutions
 * waiting for a second person. One list at a time, newest first, the selected incident beside it with the actions the
 * engine allows. The tab and the incident live in the URL; nothing moves before the engine answers.
 */

const TABS: readonly IncidentTab[] = ['open', 'pending'];
const TAB_LABEL: Record<IncidentTab, MessageKey> = { open: 'incidents.tab.open', pending: 'incidents.tab.pending' };
const EMPTY: Record<IncidentTab, MessageKey> = { open: 'incidents.empty.open', pending: 'incidents.empty.pending' };

export function IncidentsPage({ view, incidentId, process, version, step, refreshSeconds, mode }: {
  view: IncidentTab | undefined; incidentId: string | undefined; process?: string | undefined; version?: number | undefined; step?: string | undefined; refreshSeconds: number; mode: 'four-eyes' | 'single';
}) {
  const { t } = useI18n();
  const [, navigate] = useRoute();
  const active = view ?? 'open';
  // Opened from a count on the process diagram (#91): one process, one version, one step; the engine narrows by process and version.
  const narrowed = process === undefined ? undefined : { key: process, version };
  const lists = { open: useIncidentList(statusOf('open'), refreshSeconds, true, narrowed), pending: useIncidentList(statusOf('pending'), refreshSeconds, true, narrowed) };
  const [notice, setNotice] = useState<IncidentNotice | undefined>(undefined);
  const [toast, setToast] = useState<string | undefined>(undefined);

  const kept = (rows: readonly Incident[]) => rows.filter((item) => step === undefined || item.nodeId === step);
  const where = process === undefined ? {} : { process, ...(version !== undefined ? { version } : {}), ...(step !== undefined ? { step } : {}) };
  const tabLabel = (tab: IncidentTab) => {
    const data = lists[tab].data;
    const label = t(TAB_LABEL[tab]);
    if (data === undefined) return label;
    const total = kept(data.data).length;
    const count = data.hasNext ? t('tasks.more', { count: String(total) }) : String(total);
    return t('tasks.tab.withCount', { label, count });
  };
  const list = lists[active];
  const items = kept(list.data?.data ?? []);
  const selected = items.find((item) => item.id === incidentId) ?? items[0];
  const named = useStepNames(items);
  const nameOf = (incident: Incident) => stepName(incident, named(incident));

  return (
    <section className="workspace tasks incidents" aria-labelledby="incidents-title">
      <PageHead id="incidents-title" title={t('incidents.title')} subtitle={t('incidents.subtitle')} action={<ScopeSwitch area="incidents" merged={false} />} />
      {process !== undefined ? <NarrowedTo process={process} version={version} step={step} /> : null}
      <Tabs label={t('incidents.tabs')} items={TABS.map((tab) => ({ id: tab, label: tabLabel(tab) }))} selected={active} className="tasks__tabs"
        onSelect={(next) => { setNotice(undefined); navigate({ name: 'incidents', view: next, ...where }); }}>
        {notice !== undefined ? (
          <ErrorAlert className="tasks__notice" text={notice.text} error={notice.error}
            action={<button type="button" className="btn btn--quiet btn--small hit" onClick={() => { setNotice(undefined); }}>{t('toast.close')}</button>} />
        ) : null}
        {list.isPending ? (
          <article className="card"><LoadingRows rows={4} /></article>
        ) : list.isError ? (
          <article className="card"><AreaError error={list.error} onRetry={() => { void list.refetch(); }} /></article>
        ) : items.length === 0 ? (
          <article className="card tasks__empty"><EmptyState icon="incidents" text={t(EMPTY[active])} /></article>
        ) : (
          <div className="tasks__grid">
            <IncidentTable items={items} hasMore={list.data.hasNext} tab={active} where={where} selectedId={selected?.id} nameOf={nameOf}
              onSelect={(id) => { navigate({ name: 'incidents', view: active, incidentId: id, ...where }); }} />
            {selected !== undefined ? (
              <IncidentInspector key={selected.id} summary={selected} step={nameOf(selected)} mode={mode} onNotice={setNotice}
                onDone={(text, next) => {
                  setNotice(undefined);
                  setToast(text);
                  if (next !== undefined) navigate({ name: 'incidents', view: next.view, incidentId: next.incidentId, ...where });
                }} />
            ) : null}
          </div>
        )}
      </Tabs>
      <Toast text={toast} onClose={() => { setToast(undefined); }} />
    </section>
  );
}

/** The line that says the list is narrowed to one process and step, with the way back to every incident. */
function NarrowedTo({ process, version, step }: { process: string; version: number | undefined; step: string | undefined }) {
  const { t } = useI18n();
  const details = useDefinitionVersion(process, version ?? 0, version !== undefined);
  const graph = useDefinitionGraph(process, version ?? 0, version !== undefined);
  const stepLabel = step === undefined ? undefined : graph.data?.nodes.find((node) => node.id === step)?.name ?? readableName(step);
  return (
    <p className="incidents__narrowed">
      <span className="muted">{t('incidents.filter.label')}</span>{' '}
      <strong>{details.data?.name ?? readableName(process)}</strong>
      {stepLabel !== undefined ? <> <span className="muted">{t('incidents.filter.step', { step: stepLabel })}</span></> : null}
      <a className="incidents__narrowed-clear" href={formatRoute({ name: 'incidents' })}>{t('incidents.filter.clear')}</a>
    </p>
  );
}

function IncidentTable({ items, hasMore, tab, where, selectedId, nameOf, onSelect }: {
  items: readonly Incident[]; hasMore: boolean; tab: IncidentTab; where: Pick<Extract<Route, { name: 'incidents' }>, 'process' | 'version' | 'step'>; selectedId: string | undefined; nameOf: (incident: Incident) => string; onSelect: (id: string) => void;
}) {
  const { t, locale } = useI18n();
  const selectedRow = useKeepInView<HTMLTableRowElement>(selectedId);
  const shown = hasMore ? t('tasks.more', { count: String(items.length) }) : String(items.length);
  const count = items.length === 1 && !hasMore ? t('incidents.summaryOne') : t('incidents.summary', { count: shown });
  return (
    <article className="card card--table tasks__list" aria-labelledby="incidents-list-title">
      <div className="card__head card__head--padded">
        <h2 id="incidents-list-title" className="tasks__count"><span className="tnum">{count}</span></h2>
      </div>
      <table className="qtable qtable--links tasks__table">
        <thead>
          <tr>
            <th scope="col">{t('incidents.col.step')}</th>
            <th scope="col">{t('incidents.col.cause')}</th>
            <th scope="col" className="num">{t('incidents.col.retries')}</th>
            <th scope="col">{t('incidents.col.raised')}</th>
            <th scope="col">{t('incidents.col.state')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const isSelected = item.id === selectedId;
            return (
              <tr key={item.id} className={isSelected ? 'is-selected' : undefined} ref={isSelected ? selectedRow : undefined}>
                <th scope="row">
                  <a className="qtable__link tasks__name" href={formatRoute({ name: 'incidents', view: tab, incidentId: item.id, ...where })} aria-current={isSelected ? 'true' : undefined}
                    onClick={(event) => { event.preventDefault(); onSelect(item.id); }}>
                    {nameOf(item)}
                  </a>
                </th>
                <td>{t(causeKey(item.errorCode))}</td>
                <td className="num tnum">{item.retryCount}</td>
                <td className="tnum">{formatDateTime(item.createdAt, locale)}</td>
                <td><IncidentState incident={item} compact /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {hasMore ? <p className="dock__hint incidents__more">{t('incidents.more', { count: String(INCIDENT_LIMIT) })}</p> : null}
    </article>
  );
}
