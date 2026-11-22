import { useState } from 'react';
import type { Incident } from '../../api/processes/schemas';
import { useFederation } from '../../app/federation/federation';
import { formatRoute, useRoute, type IncidentTab } from '../../app/route';
import { EmptyState, ErrorAlert, LoadingRows, PageHead } from '../../components/PageParts';
import { useKeepInView } from '../../components/useKeepInView';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { IncidentInspector, type IncidentNotice } from '../incidents/IncidentInspector';
import { IncidentState, stepName } from '../incidents/parts';
import { causeKey, resolveMode, statusOf } from '../incidents/model/incidents';
import { Tabs } from '../rules/components/Tabs';
import { Toast } from '../rules/components/Toast';
import { formatDateTime } from '../rules/model/dates';
import { HostScope } from './HostScope';
import { byNewest, type Row } from './merge';
import { HostTag, IncompleteNotice, ScopeSwitch } from './parts';
import { useMergedList, type MergedSpec } from './useMergedList';
import '../../components/tasks.css';
import '../../components/incidents.css';

/*
 * One incident list across hosts (CK-F-29, CKP-020): open incidents, and resolutions waiting for a second person, of
 * every host the user can reach, newest first, each row labelled with its host. Retry, resolve, approve and reject go
 * to the host that owns the incident, with its own Idempotency-Key and If-Match, and its own four-eyes rule.
 */

export const MERGED_INCIDENT_LIMIT = 50;
const TABS: readonly IncidentTab[] = ['open', 'pending'];
const TAB_LABEL: Record<IncidentTab, MessageKey> = { open: 'incidents.tab.open', pending: 'incidents.tab.pending' };
const EMPTY: Record<IncidentTab, MessageKey> = { open: 'federation.incidents.empty.open', pending: 'federation.incidents.empty.pending' };

const specOf = (tab: IncidentTab): Omit<MergedSpec<Incident>, 'refreshSeconds'> => ({
  kind: 'incidents',
  variant: tab,
  permission: 'processes:read',
  pageLimit: MERGED_INCIDENT_LIMIT,
  compare: byNewest,
  readPage: (client, { cursor, signal }, etag) => client.readIncidents({ status: statusOf(tab), limit: MERGED_INCIDENT_LIMIT, cursor, signal }, etag),
});

export function MergedIncidentsPage({ view, incidentId, on, refreshSeconds }: { view: IncidentTab | undefined; incidentId: string | undefined; on: string | undefined; refreshSeconds: number }) {
  const { t } = useI18n();
  const federation = useFederation();
  const [, navigate] = useRoute();
  const active = view ?? 'open';
  const list = useMergedList<Incident>({ ...specOf(active), refreshSeconds });
  const [notice, setNotice] = useState<IncidentNotice | undefined>(undefined);
  const [toast, setToast] = useState<string | undefined>(undefined);
  const rows = list.status === 'ready' ? list.rows : [];
  const selected = rows.find((row) => row.item.id === incidentId && (on === undefined || row.hostId === on)) ?? rows[0];
  const selectedHost = federation?.hosts.find((host) => host.id === selected?.hostId);
  const labelOf = (hostId: string) => federation?.hosts.find((host) => host.id === hostId)?.label ?? hostId;
  const modeOf = selectedHost?.state === 'ready' ? resolveMode(selectedHost.meta.modules.find((module) => module.module === 'processes')?.settings) : 'four-eyes';

  return (
    <section className="workspace tasks incidents" aria-labelledby="incidents-title">
      <PageHead id="incidents-title" title={t('incidents.title')} subtitle={t('federation.incidents.subtitle')} action={<ScopeSwitch area="incidents" merged />} />
      <Tabs label={t('incidents.tabs')} items={TABS.map((tab) => ({ id: tab, label: t(TAB_LABEL[tab]) }))} selected={active} className="tasks__tabs"
        onSelect={(next) => { setNotice(undefined); navigate({ name: 'incidents', scope: 'all', view: next }); }}>
        {notice !== undefined ? (
          <ErrorAlert className="tasks__notice" text={notice.text} error={notice.error}
            action={<button type="button" className="btn btn--quiet btn--small hit" onClick={() => { setNotice(undefined); }}>{t('toast.close')}</button>} />
        ) : null}
        {list.status === 'loading' ? (
          <article className="card"><LoadingRows rows={4} /></article>
        ) : list.status === 'none' ? (
          <article className="card tasks__empty"><EmptyState icon="incidents" text={t('federation.none')} /></article>
        ) : (
          <>
            <IncompleteNotice hosts={list.incomplete} onRetry={list.refresh} />
            {rows.length === 0 ? (
              <article className="card tasks__empty"><EmptyState icon="incidents" text={list.answered.length === 0 ? t('federation.allFailed') : t(EMPTY[active])} /></article>
            ) : (
              <div className="tasks__grid">
                <IncidentTable rows={rows} tab={active} hasMore={list.hasMore} loadingMore={list.loadingMore} onMore={list.loadMore} labelOf={labelOf}
                  selected={selected} onSelect={(row) => { navigate({ name: 'incidents', scope: 'all', view: active, on: row.hostId, incidentId: row.item.id }); }} />
                {selected !== undefined && selectedHost?.state === 'ready' ? (
                  <HostScope host={selectedHost} key={`${selected.hostId}|${selected.item.id}`}>
                    <IncidentInspector summary={selected.item} step={stepName(selected.item)} mode={modeOf} onNotice={setNotice}
                      onDone={(text, next) => {
                        setNotice(undefined);
                        setToast(text);
                        if (next !== undefined) navigate({ name: 'incidents', scope: 'all', view: next.view, on: selected.hostId, incidentId: next.incidentId });
                      }} />
                  </HostScope>
                ) : null}
              </div>
            )}
          </>
        )}
      </Tabs>
      <Toast text={toast} onClose={() => { setToast(undefined); }} />
    </section>
  );
}

function IncidentTable({ rows, tab, hasMore, loadingMore, onMore, labelOf, selected, onSelect }: {
  rows: readonly Row<Incident>[];
  tab: IncidentTab;
  hasMore: boolean;
  loadingMore: boolean;
  onMore: () => void;
  labelOf: (hostId: string) => string;
  selected: Row<Incident> | undefined;
  onSelect: (row: Row<Incident>) => void;
}) {
  const { t, locale } = useI18n();
  const selectedRow = useKeepInView<HTMLTableRowElement>(selected?.item.id);
  const shown = hasMore ? t('tasks.more', { count: String(rows.length) }) : String(rows.length);
  const count = rows.length === 1 && !hasMore ? t('incidents.summaryOne') : t('incidents.summary', { count: shown });
  return (
    <article className="card card--table tasks__list" aria-labelledby="incidents-list-title">
      <div className="card__head card__head--padded"><h2 id="incidents-list-title" className="tasks__count"><span className="tnum">{count}</span></h2></div>
      <table className="qtable qtable--links tasks__table">
        <thead>
          <tr>
            <th scope="col">{t('incidents.col.step')}</th>
            <th scope="col">{t('federation.col.service')}</th>
            <th scope="col">{t('incidents.col.cause')}</th>
            <th scope="col" className="num">{t('incidents.col.retries')}</th>
            <th scope="col">{t('incidents.col.raised')}</th>
            <th scope="col">{t('incidents.col.state')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const { item, hostId } = row;
            const isSelected = selected?.item.id === item.id && selected.hostId === hostId;
            return (
              <tr key={`${hostId}|${item.id}`} className={isSelected ? 'is-selected' : undefined} ref={isSelected ? selectedRow : undefined}>
                <th scope="row">
                  <a className="qtable__link tasks__name" href={formatRoute({ name: 'incidents', scope: 'all', view: tab, on: hostId, incidentId: item.id })} aria-current={isSelected ? 'true' : undefined}
                    onClick={(event) => { event.preventDefault(); onSelect(row); }}>
                    {stepName(item)}
                  </a>
                </th>
                <td><HostTag label={labelOf(hostId)} /></td>
                <td>{t(causeKey(item.errorCode))}</td>
                <td className="num tnum">{item.retryCount}</td>
                <td className="tnum">{formatDateTime(item.createdAt, locale)}</td>
                <td><IncidentState incident={item} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {hasMore ? (
        <div className="merged__more">
          <button type="button" className="btn btn--quiet hit" disabled={loadingMore} onClick={onMore}>{loadingMore ? t('federation.loadMore.busy') : t('federation.loadMore')}</button>
        </div>
      ) : null}
    </article>
  );
}
