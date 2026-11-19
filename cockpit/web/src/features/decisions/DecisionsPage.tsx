import { formatRoute } from '../../app/route';
import { EmptyState, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';

/**
 * Decisions, in preview. The engine has no decision-log read API in this release, and decision records hold
 * inputs that can be personal data until masking ships, so the page states when it arrives and links onwards.
 */
export function DecisionsPage({ rulesAvailable }: { rulesAvailable: boolean }) {
  const { t } = useI18n();
  return (
    <section className="workspace" aria-labelledby="decisions-title">
      <PageHead id="decisions-title" title={t('decisions.title')} subtitle={t('decisions.subtitle')} preview />
      <article className="card">
        <EmptyState icon="decisions" text={t('decisions.empty')} action={rulesAvailable ? { label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) } : undefined} />
      </article>
    </section>
  );
}
