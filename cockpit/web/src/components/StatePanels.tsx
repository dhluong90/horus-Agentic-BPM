import type { HostError } from '../api/hostError';
import type { ConfigProblem } from '../app/session/useHostSession';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';
import { CopyButton } from './CopyButton';

export function LoadingPanel() {
  const { t } = useI18n();
  return (
    <section className="panel panel--center" aria-busy="true">
      <span className="spinner" aria-hidden="true" />
      <p role="status">{t('loading.service')}</p>
    </section>
  );
}

const ERROR_TEXT: Record<HostError['kind'], { title: MessageKey; body: MessageKey }> = {
  problem: { title: 'error.problem.title', body: 'error.problem.body' },
  'signed-out': { title: 'error.signedOut.title', body: 'error.signedOut.body' },
  unreachable: { title: 'error.unreachable.title', body: 'error.unreachable.body' },
  'unexpected-response': { title: 'error.unexpected.title', body: 'error.unexpected.body' },
};

const NOT_FOUND_TEXT = { title: 'error.notFound.title', body: 'error.notFound.body' } as const satisfies { title: MessageKey; body: MessageKey };

/** A host that answers with an error shows its code and correlation id, never a blank page. */
export function HostErrorPanel({ error, onRetry }: { error: HostError; onRetry: () => void }) {
  const { t } = useI18n();
  // 404 on the metadata endpoints: the host has no engine metadata module, or an engine too old for it.
  const text = error.kind === 'problem' && error.status === 404 ? NOT_FOUND_TEXT : ERROR_TEXT[error.kind];
  const code = error.kind === 'problem' || error.kind === 'signed-out' ? error.code : undefined;
  const correlationId = error.kind === 'unreachable' ? undefined : error.correlationId;
  const status = error.kind === 'problem' || error.kind === 'unexpected-response' ? error.status : undefined;
  return (
    <section className="panel panel--error" role="alert" aria-labelledby="host-error-title">
      <h1 id="host-error-title" className="panel__title">
        <span className="status-icon status-icon--bad" aria-hidden="true">!</span>
        {t(text.title)}
      </h1>
      <p className="panel__body">{t(text.body)}</p>
      {error.kind !== 'unreachable' ? (
        <dl className="facts">
          <div>
            <dt>{t('error.code')}</dt>
            <dd data-testid="error-code">
              <FactValue value={code} />
            </dd>
          </div>
          <div>
            <dt>{t('error.correlationId')}</dt>
            <dd className="facts__with-action">
              <span data-testid="error-correlation-id">
                <FactValue value={correlationId} />
              </span>
              {correlationId !== undefined ? <CopyButton value={correlationId} /> : null}
            </dd>
          </div>
          {status !== undefined ? (
            <div>
              <dt>{t('error.status')}</dt>
              <dd className="mono">{status}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      <div className="panel__actions">
        {error.kind === 'signed-out' ? (
          <button type="button" className="btn btn--primary" onClick={() => {
              window.location.reload();
            }}>
            {t('action.signIn')}
          </button>
        ) : (
          <button type="button" className="btn btn--primary" onClick={onRetry}>
            {t('action.retry')}
          </button>
        )}
      </div>
    </section>
  );
}

/** A machine value in monospace, or a quiet "Not provided" in the UI font. */
function FactValue({ value }: { value: string | undefined }) {
  const { t } = useI18n();
  if (value === undefined) return <span className="facts__missing">{t('error.notProvided')}</span>;
  return <span className="mono">{value}</span>;
}

function configMessage(problem: ConfigProblem): { key: MessageKey; params?: Record<string, string> } {
  switch (problem.reason) {
    case 'unavailable':
      return { key: 'config.unavailable' };
    case 'not-json':
      return { key: 'config.notJson' };
    case 'invalid':
      return { key: 'config.invalid' };
    case 'duplicate-id':
      return { key: 'config.duplicateId', params: { value: problem.value } };
    case 'duplicate-base-url':
      return { key: 'config.duplicateBaseUrl', params: { value: problem.value || '""' } };
    case 'no-enabled-host':
      return { key: 'config.noEnabledHost' };
    case 'oidc-not-available':
      return { key: 'config.oidcNotAvailable' };
    default:
      return assertNever(problem);
  }
}

export function ConfigErrorPanel({ problem }: { problem: ConfigProblem }) {
  const { t } = useI18n();
  const message = configMessage(problem);
  const pointer = 'pointer' in problem ? problem.pointer : undefined;
  return (
    <section className="panel panel--error" role="alert" aria-labelledby="config-error-title">
      <h1 id="config-error-title" className="panel__title">
        <span className="status-icon status-icon--bad" aria-hidden="true">!</span>
        {t('config.title')}
      </h1>
      <p className="panel__body">{t(message.key, message.params)}</p>
      <p className="panel__hint">{t('config.body')}</p>
      {pointer !== undefined && pointer !== '' ? (
        <dl className="facts">
          <div>
            <dt>{t('config.location')}</dt>
            <dd className="mono" data-testid="config-pointer">{pointer}</dd>
          </div>
        </dl>
      ) : null}
    </section>
  );
}

function assertNever(value: never): never {
  throw new Error(`Unhandled variant: ${JSON.stringify(value)}`);
}
