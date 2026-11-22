import type { DefinitionStatus } from '../../api/processes/schemas';
import { DOCS } from '../../app/links';
import { formatRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { formatDateTime } from '../rules/model/dates';
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
    <span className={`badge badge--${look.tone}`}>
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
      <PageHead id="processes-title" title={t('processes.title')} subtitle={t('processes.subtitle')} />
      <article className="card card--table" aria-labelledby="processes-title">
        {can('processes:read') ? <DefinitionTable pageLimit={pageLimit} /> : <div className="card__pad"><EmptyState icon="processes" text={t('processes.noRead')} /></div>}
      </article>
    </section>
  );
}

function DefinitionTable({ pageLimit }: { pageLimit: number }) {
  const { t, locale } = useI18n();
  const definitions = useDefinitions(pageLimit);
  if (definitions.isPending) return <div className="card__pad"><LoadingRows rows={4} /></div>;
  if (definitions.isError) return <div className="card__pad"><AreaError error={definitions.error} onRetry={() => { void definitions.refetch(); }} /></div>;
  if (definitions.data.length === 0) {
    return <div className="card__pad"><EmptyState icon="processes" text={t('processes.empty')} action={{ label: t('processes.guide'), href: DOCS.processEngine }} /></div>;
  }
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
        {definitions.data.map((item) => (
          <tr key={item.key}>
            <th scope="row">
              <a className="qtable__link" href={formatRoute({ name: 'process', key: item.key, version: item.effectiveVersion ?? item.latestVersion })}>
                {item.name ?? item.key}
              </a>
            </th>
            <td><DefinitionStatusPill status={item.latestStatus} /></td>
            <td className="tnum">{version(item.latestVersion)}</td>
            <td className="tnum">{item.effectiveVersion !== null && item.effectiveVersion !== undefined ? version(item.effectiveVersion) : <span className="muted" aria-label={t('rulesArea.none')}>—</span>}</td>
            <td className="tnum muted">{formatDateTime(item.lastChangedAt, locale)}</td>
            {item.latestStatus === 'PENDING_APPROVAL' ? (
              <td className="qtable__action">
                <a className="btn btn--small hit" href={formatRoute({ name: 'process', key: item.key, version: item.latestVersion, lens: 'changes' })}
                  aria-label={`${t('processes.reviewChange')}: ${item.name ?? item.key}, ${version(item.latestVersion)}`}>
                  {t('processes.reviewChange')}
                </a>
              </td>
            ) : <td className="qtable__chevron" aria-hidden="true">›</td>}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
