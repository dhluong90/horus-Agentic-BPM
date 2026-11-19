import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { formatRoute } from '../../app/route';
import { EmptyState, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * Processes, in preview. The Process Engine has no definition or instance API yet, so the page names what
 * the host runs, from /meta, and what comes next. No process data is invented.
 */
export function ProcessesPage({ module }: { module: BpmMetaV1['modules'][number] | undefined }) {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="processes-title">
      <PageHead id="processes-title" title={t('processes.title')} subtitle={t('processes.subtitle')} preview />
      <article className="card">
        {module !== undefined ? <p className="card__line tnum">{t('processes.engine', { version: module.engineVersion })}</p> : null}
        <EmptyState text={t('processes.empty')} action={{ label: t('menu.home'), href: formatRoute({ name: 'home' }) }} />
      </article>
    </section>
  );
}
