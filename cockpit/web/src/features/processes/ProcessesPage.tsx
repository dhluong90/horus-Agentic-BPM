import { DOCS } from '../../app/links';
import { EmptyState, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * Processes, in preview. The Process Engine has no definition or instance API yet, so no process can be listed;
 * the page says so and points to how the engine works. No process data is invented.
 */
export function ProcessesPage() {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="processes-title">
      <PageHead id="processes-title" title={t('processes.title')} subtitle={t('processes.subtitle')} preview />
      <article className="card">
        <EmptyState icon="processes" text={t('processes.empty')} action={{ label: t('processes.guide'), href: DOCS.processEngine }} />
      </article>
    </section>
  );
}
