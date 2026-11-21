import type { HostError } from '../api/hostError';
import type { SignInProblem } from '../app/auth/oidc';
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
  'no-access': { title: 'error.noAccess.title', body: 'error.noAccess.body' },
  unreachable: { title: 'error.unreachable.title', body: 'error.unreachable.body' },
  'unexpected-response': { title: 'error.unexpected.title', body: 'error.unexpected.body' },
};

function errorText(error: HostError): { title: MessageKey; body: MessageKey } {
  if (error.kind !== 'problem') return ERROR_TEXT[error.kind];
  // 404 on the metadata endpoints: the host has no engine metadata module, or an engine too old for it.
  if (error.status === 404) return { title: 'error.notFound.title', body: 'error.notFound.body' };
  // 401 while the page itself was served: page and API authenticate differently. A reload cannot fix it.
  if (error.status === 401) return { title: 'error.notAccepted.title', body: 'error.problem.body' };
  return ERROR_TEXT.problem;
}

/** A host that answers with an error shows its code and correlation id, never a blank page. */
export function HostErrorPanel({ error, onRetry }: { error: HostError; onRetry: () => void }) {
  const { t } = useI18n();
  const text = errorText(error);
  const code = error.kind === 'problem' ? error.code : undefined;
  const correlationId = error.kind === 'problem' || error.kind === 'unexpected-response' ? error.correlationId : undefined;
  const status = error.kind === 'problem' || error.kind === 'unexpected-response' ? error.status : undefined;
  return (
    <section className="panel panel--error" role="alert" aria-labelledby="host-error-title">
      <h1 id="host-error-title" className="panel__title">
        <span className="status-icon status-icon--bad" aria-hidden="true">!</span>
        {t(text.title)}
      </h1>
      <p className="panel__body">{t(text.body)}</p>
      {error.kind === 'problem' || error.kind === 'unexpected-response' ? (
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

/** While the browser goes to the identity provider and back. */
export function SigningInPanel() {
  const { t } = useI18n();
  return (
    <section className="panel panel--center" aria-busy="true">
      <span className="spinner" aria-hidden="true" />
      <p role="status">{t('signIn.progress')}</p>
    </section>
  );
}

const SIGN_IN_TEXT: Record<SignInProblem['reason'], MessageKey> = {
  'provider-unreachable': 'signIn.failed.unreachable',
  'provider-refused': 'signIn.failed.refused',
  'state-mismatch': 'signIn.failed.expired',
  'token-refused': 'signIn.failed.refused',
  'id-token-invalid': 'signIn.failed.invalid',
};

/** Sign-in did not finish. Nothing is retried by itself; Sign in again starts over from the route asked for. */
export function SignInFailedPanel({ problem, onRetry }: { problem: SignInProblem; onRetry: () => void }) {
  const { t } = useI18n();
  const code = problem.reason === 'provider-refused' ? problem.error : problem.reason === 'token-refused' ? problem.error : undefined;
  return (
    <section className="panel panel--error" role="alert" aria-labelledby="sign-in-error-title">
      <h1 id="sign-in-error-title" className="panel__title">
        <span className="status-icon status-icon--bad" aria-hidden="true">!</span>
        {t('signIn.failed.title')}
      </h1>
      <p className="panel__body">{t(SIGN_IN_TEXT[problem.reason])}</p>
      {code !== undefined ? (
        <dl className="facts">
          <div>
            <dt>{t('error.code')}</dt>
            <dd className="mono" data-testid="error-code">{code}</dd>
          </div>
        </dl>
      ) : null}
      <div className="panel__actions">
        <button type="button" className="btn btn--primary" onClick={onRetry}>
          {t('action.signIn')}
        </button>
      </div>
    </section>
  );
}

/** A host on an engine line this cockpit does not support: its version and the range, and no screens (CKP-014). */
export function UnsupportedVersionPanel({ hostName, engineVersion, lines }: { hostName: string; engineVersion: string; lines: readonly string[] }) {
  const { t } = useI18n();
  return (
    <section className="panel panel--warn" role="alert" aria-labelledby="version-title">
      <h1 id="version-title" className="panel__title">
        <span className="status-icon status-icon--warn" aria-hidden="true">!</span>
        {t('version.title')}
      </h1>
      <p className="panel__body" data-testid="version-text">{t('version.body', { service: hostName, version: engineVersion, supported: lines.join(t('version.and')) })}</p>
      <p className="panel__hint">{t('version.hint')}</p>
      <dl className="facts">
        <div>
          <dt>{t('error.code')}</dt>
          <dd className="mono" data-testid="error-code">UNSUPPORTED_VERSION</dd>
        </div>
      </dl>
    </section>
  );
}

/** A decision link without its host while several are registered: ask, and request nothing until chosen (PD-108). */
export function HostChooserPanel({ hosts }: { hosts: readonly { id: string; label: string; href: string }[] }) {
  const { t } = useI18n();
  return (
    <section className="panel" aria-labelledby="choose-host-title">
      <h1 id="choose-host-title" className="panel__title">{t('host.chooseForDecision')}</h1>
      <p className="panel__body">{t('host.chooseForDecisionBody')}</p>
      <ul className="host-choice">
        {hosts.map((host) => (
          <li key={host.id}>
            <a className="btn btn--quiet host-choice__item" href={host.href}>{host.label}</a>
          </li>
        ))}
      </ul>
    </section>
  );
}
