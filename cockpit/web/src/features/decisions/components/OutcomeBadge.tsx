import type { DecisionOutcome } from '../../../api/rules/auditSchemas';
import { useI18n } from '../../../i18n/I18nProvider';

/**
 * A decision's outcome as an icon and words, never colour alone: the matched rule, no match, or a failure. Blue
 * marks a matched row as in Try it; red only a failed decision.
 */
export function OutcomeBadge({ outcome, matched }: { outcome: DecisionOutcome | 'OTHER'; matched: readonly string[] }) {
  const { t } = useI18n();
  if (outcome === 'UNIQUE_HIT_VIOLATION') return <span className="badge badge--bad"><span aria-hidden="true">✕</span> {t('dlog.outcome.unique')}</span>;
  if (outcome === 'EVALUATION_ERROR' || outcome === 'OTHER') return <span className="badge badge--bad"><span aria-hidden="true">✕</span> {t('dlog.outcome.failed')}</span>;
  if (outcome === 'NO_MATCH' || matched.length === 0) return <span className="badge badge--neutral"><span aria-hidden="true">–</span> {t('dlog.outcome.noMatch')}</span>;
  return <span className="badge badge--accent"><span aria-hidden="true">✓</span> {t('dlog.outcome.matched', { rules: matched.join(', ') })}</span>;
}
