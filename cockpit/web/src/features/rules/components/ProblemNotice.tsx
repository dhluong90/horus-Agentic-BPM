import { toHostError, type HostError } from '../../../api/hostError';
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
 * An engine refusal in place, next to the action that caused it: a message in the user's language, the
 * error code and the correlation id with Copy. The engine's own sentence is in English in this release, so it
 * shows directly in English and behind a disclosure in Vietnamese, keeping one language on screen.
 */
export function ProblemNotice({ error, onReload, onRestart, showDetail = true }: { error: unknown; onReload?: () => void; onRestart?: () => void; showDetail?: boolean }) {
  const { t, locale } = useI18n();
  const hostError = toHostError(error);
  const problem = hostError.kind === 'problem' ? hostError : undefined;
  const correlationId = hostError.kind === 'problem' || hostError.kind === 'unexpected-response' ? hostError.correlationId : undefined;
  const detail = showDetail ? problem?.detail : undefined;
  return (
    <div className="notice notice--bad" role="alert">
      <p className="notice__title">
        <span className="status-icon status-icon--bad" aria-hidden="true">!</span>
        {t(messageKey(hostError))}
      </p>
      {detail !== undefined && locale === 'en' ? <p className="notice__detail">{detail}</p> : null}
      {detail !== undefined && locale !== 'en' ? (
        <details className="notice__more">
          <summary>{t('problem.engineDetail')}</summary>
          <p lang="en">{detail}</p>
        </details>
      ) : null}
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
