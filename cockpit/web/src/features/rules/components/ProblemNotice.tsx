import { toHostError, type HostError, type Violation } from '../../../api/hostError';
import { CopyButton } from '../../../components/CopyButton';
import type { MessageKey } from '../../../i18n/en';
import { useI18n } from '../../../i18n/I18nProvider';

const KNOWN_CODES = [
  'VALIDATION_FAILED',
  'SELF_APPROVAL_FORBIDDEN',
  'VERSION_CONFLICT',
  'INVALID_STATE',
  'PERMISSION_DENIED',
  'RULE_SET_EXISTS',
  'NO_EFFECTIVE_VERSION',
  'INPUT_INVALID',
  'EVALUATION_ERROR',
  'QUERY_INVALID',
  'PAYLOAD_TOO_LARGE',
] as const;

function messageKey(error: HostError): MessageKey {
  switch (error.kind) {
    case 'problem': {
      const code = KNOWN_CODES.find((known) => known === error.code);
      return code ? `problem.${code}` : 'error.problem.title';
    }
    case 'signed-out':
      return 'error.signedOut.title';
    case 'unreachable':
      return 'error.unreachable.title';
    case 'unexpected-response':
      return 'error.unexpected.title';
  }
}

/**
 * An engine refusal in place, next to the action that caused it: one plain sentence in the user's language.
 * The error code, the correlation id with Copy and the engine's own (English) sentence stay behind Details.
 */
function Located({ violations, english }: { violations: readonly Violation[]; english: boolean }) {
  const { t } = useI18n();
  const place = (v: Violation) =>
    v.ruleId !== null && v.column !== null ? t('editor.errorCell', { row: v.ruleId, column: v.column }) : v.ruleId !== null ? t('editor.errorRow', { row: v.ruleId }) : undefined;
  return (
    <ul className="notice__located" aria-label={t('problem.locatedTitle')}>
      {violations.map((v, index) => {
        const where = place(v);
        return (
          <li key={index}>
            {where !== undefined ? <strong>{where}</strong> : null}
            {english ? <span>{where !== undefined ? `: ${v.message}` : v.message}</span> : null}
          </li>
        );
      })}
    </ul>
  );
}

/** `located` lists every violation of a refused document by row and column, for screens without the editor grid. */
export function ProblemNotice({ error, onReload, onRestart, showDetail = true, located = false }: { error: unknown; onReload?: () => void; onRestart?: () => void; showDetail?: boolean; located?: boolean }) {
  const { t, locale } = useI18n();
  const hostError = toHostError(error);
  const problem = hostError.kind === 'problem' ? hostError : undefined;
  const correlationId = hostError.kind === 'problem' || hostError.kind === 'unexpected-response' ? hostError.correlationId : undefined;
  const detail = showDetail ? problem?.detail : undefined;
  const hasDetails = problem?.code !== undefined || correlationId !== undefined || detail !== undefined;
  return (
    <div className="notice notice--bad" role="alert">
      <p className="notice__title">
        <span className="status-icon status-icon--bad" aria-hidden="true">✕</span>
        {t(messageKey(hostError))}
      </p>
      {located && problem?.violations ? <Located violations={problem.violations} english={locale === 'en'} /> : null}
      {hasDetails ? (
        <details className="notice__more">
          <summary>{t('try.details')}</summary>
          {detail !== undefined && !(located && problem?.violations) ? <p className="notice__detail" lang="en">{detail}</p> : null}
          <dl className="notice__facts">
            {problem?.code !== undefined ? (
              <div>
                <dt>{t('error.code')}</dt>
                <dd className="mono" data-testid="problem-code">{problem.code}</dd>
              </div>
            ) : null}
            {correlationId !== undefined ? (
              <div>
                <dt>{t('error.correlationId')}</dt>
                <dd className="facts__with-action">
                  <span className="mono">{correlationId}</span>
                  <CopyButton value={correlationId} />
                </dd>
              </div>
            ) : null}
          </dl>
        </details>
      ) : null}
      {onReload && problem?.code === 'VERSION_CONFLICT' ? (
        <button type="button" className="btn btn--small hit" onClick={onReload}>
          {t('problem.reload')}
        </button>
      ) : null}
      {onRestart ? (
        <button type="button" className="btn btn--small hit" onClick={onRestart}>
          {t('problem.restart')}
        </button>
      ) : null}
    </div>
  );
}
