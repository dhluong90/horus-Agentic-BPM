import type { DefinitionStatus, DefinitionSummary } from '../../api/processes/schemas';
import { DOCS } from '../../app/links';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { RecentCard } from '../../components/RecentCard';
import { InitialDisc, TintTiles } from '../../components/TintTiles';
import { useI18n } from '../../i18n/I18nProvider';
import { formatDate, formatDateTime } from '../rules/model/dates';
import { readableName } from '../tasks/model/queue';
import { useProcessesSession } from './api/processesContext';
import { useDefinitions } from './api/useProcesses';

const LOOK: Record<DefinitionStatus, { glyph: string; tone: string }> = {
  DRAFT: { glyph: '✎', tone: 'neutral' },
  PENDING_APPROVAL: { glyph: '◷', tone: 'accent' },
  ACTIVE: { glyph: '✓', tone: 'ok' },
  RETIRED: { glyph: '■', tone: 'muted' },
};

/** A definition version's status in a word with its icon. */
export function DefinitionStatusPill({ status }: { status: DefinitionStatus }) {
  const { t } = useI18n();
  const look = LOOK[status];
  return (
    <span className={`status status--${look.tone}`}>
      <span aria-hidden="true">{look.glyph}</span> {t(`processStatus.${status}`)}
    </span>
  );
}

/**
 * Processes: the definition keys of the tenant (listDefinitions), each row opening the diagram of the version a start
 * uses now, or of the latest version when none is in effect. A key whose latest version waits for approval offers
 * Review change, which opens that version's Changes (CKP-024).
 */
export function ProcessesPage({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  return (
    <section className="workspace" aria-labelledby="processes-title">
      <PageHead id="processes-title" title={t('processes.title')} subtitle={t('processes.subtitle')}
        action={can('processes:read') ? <a className="btn" href={formatRoute({ name: 'calendar-changes' })}>{t('processes.calendarChanges')}</a> : undefined} />
      {can('processes:read') ? <DefinitionList pageLimit={pageLimit} /> : (
        <article className="card card--table" aria-labelledby="processes-title"><div className="card__pad"><EmptyState icon="processes" text={t('processes.noRead')} /></div></article>
      )}
    </section>
  );
}

/** The name people read: the definition's own name, or its key in words when it has none. */
const nameOf = (item: DefinitionSummary) => item.name ?? readableName(item.key);

function DefinitionList({ pageLimit }: { pageLimit: number }) {
  const { t, locale } = useI18n();
  const definitions = useDefinitions(pageLimit);
  if (definitions.isPending) return <article className="card card--table"><div className="card__pad"><LoadingRows rows={4} /></div></article>;
  if (definitions.isError) return <article className="card card--table"><div className="card__pad"><AreaError error={definitions.error} onRetry={() => { void definitions.refetch(); }} /></div></article>;
  if (definitions.data.length === 0) {
    return <article className="card card--table"><div className="card__pad"><EmptyState icon="processes" text={t('processes.empty')} action={{ label: t('processes.guide'), href: DOCS.processEngine }} /></div></article>;
  }
  const count = (status: DefinitionStatus) => definitions.data.filter((item) => item.latestStatus === status).length;
  const recent = [...definitions.data].sort((a, b) => Date.parse(b.lastChangedAt) - Date.parse(a.lastChangedAt)).slice(0, 4);
  return (
    <>
      <TintTiles label={t('list.tiles')} tiles={[
        { key: 'active', value: String(count('ACTIVE')), label: t('processes.tile.active'), tint: 'lime' },
        { key: 'pending', value: String(count('PENDING_APPROVAL')), label: t('processes.tile.pending'), tint: 'amber' },
        { key: 'draft', value: String(count('DRAFT')), label: t('processes.tile.draft'), tint: 'mint' },
      ]} />
      <div className="listing">
        <article className="card card--table dense" aria-labelledby="processes-title">
          <DefinitionTable items={definitions.data} />
        </article>
        <RecentCard title={t('processes.recent.title')} empty={t('list.recent.none')}
          items={recent.map((item) => ({
            key: item.key, name: nameOf(item),
            status: t(`processStatus.${item.latestStatus}`), when: formatDateTime(item.lastChangedAt, locale) ?? '',
          }))} />
      </div>
    </>
  );
}

function DefinitionTable({ items }: { items: readonly DefinitionSummary[] }) {
  const { t, locale } = useI18n();
  const version = (value: number) => t('rules.version', { version: String(value) });
  return (
    <table className="qtable qtable--links">
      <thead>
        <tr>
          <th scope="col">{t('processes.col.name')}</th>
          <th scope="col">{t('processes.col.status')}</th>
          <th scope="col">{t('processes.col.latest')}</th>
          <th scope="col">{t('processes.col.effective')}</th>
          <th scope="col">{t('processes.col.changed')}</th>
          <th scope="col"><span className="sr-only">{t('processes.col.open')}</span></th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.key}>
            <th scope="row">
              <div className="dense__name">
                <InitialDisc name={nameOf(item)} />
                <a className="qtable__link" href={formatRoute({ name: 'process', key: item.key, version: item.effectiveVersion ?? item.latestVersion })} title={item.key}>{nameOf(item)}</a>
              </div>
            </th>
            <td><DefinitionStatusPill status={item.latestStatus} /></td>
            <td className="tnum">{version(item.latestVersion)}</td>
            <td className="tnum">{item.effectiveVersion !== null && item.effectiveVersion !== undefined ? version(item.effectiveVersion) : <span className="muted" aria-label={t('rulesArea.none')}>—</span>}</td>
            <td className="tnum muted">{formatDate(item.lastChangedAt, locale)}</td>
            <td className="qtable__action">
              {item.latestStatus === 'PENDING_APPROVAL' ? (
                <a className="btn btn--small hit" href={formatRoute({ name: 'process', key: item.key, version: item.latestVersion, lens: 'changes' })}
                  aria-label={`${t('processes.reviewChange')}: ${nameOf(item)}, ${version(item.latestVersion)}`}>
                  {t('processes.reviewChange')}
                </a>
              ) : (
                <a className="btn btn--small btn--quiet hit" href={formatRoute({ name: 'process', key: item.key, version: item.effectiveVersion ?? item.latestVersion })} aria-label={t('list.openName', { name: nameOf(item) })}>
                  {t('list.open')}
                </a>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
