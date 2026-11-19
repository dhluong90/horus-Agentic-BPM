import { useI18n } from '../../../i18n/I18nProvider';
import type { RuleSetVersion } from '../../../api/rules/schemas';
import { useSource } from '../api/useRules';
import { ProblemNotice } from './ProblemNotice';

function download(version: RuleSetVersion, text: string) {
  const extension = version.sourceFormat === 'JSON' ? 'json' : 'yaml';
  const url = URL.createObjectURL(new Blob([text], { type: version.sourceFormat === 'JSON' ? 'application/json' : 'application/yaml' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `${version.ruleSet}-v${version.version}.${extension}`;
  link.click();
  URL.revokeObjectURL(url);
}

/** The source document, only when the user opens this view on purpose. */
export function SourceView({ version }: { version: RuleSetVersion }) {
  const { t } = useI18n();
  const source = useSource(version.ruleSet, version.version, true);
  if (source.isPending) return <p className="empty" role="status">{t('ruleSets.loading')}</p>;
  if (source.isError) return <ProblemNotice error={source.error} />;
  const text = source.data.sourceDocument ?? '';
  return (
    <section className="source" aria-labelledby="source-title">
      <div className="source__head">
        <h2 id="source-title" className="card__title">{t('source.title')}</h2>
        <span className="source__hint">{t('source.hint')}</span>
        <button type="button" className="btn btn--quiet btn--small hit" onClick={() => { download(version, text); }}>
          {t('source.download')}
        </button>
      </div>
      <pre className="source__text mono" tabIndex={0}>{text}</pre>
    </section>
  );
}
