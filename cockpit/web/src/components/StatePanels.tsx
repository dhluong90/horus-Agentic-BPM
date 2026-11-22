import type { ReactNode } from 'react';
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

/** One fact behind Details: a machine value in monospace, with Copy when support needs to paste it. */
interface Fact { label: MessageKey; value: string | undefined; testId?: string; copy?: { label: MessageKey; copied: MessageKey } }

const COPY_CODE = { label: 'action.copyCode', copied: 'action.codeCopied' } as const;
const COPY_REFERENCE = { label: 'action.copyReference', copied: 'action.referenceCopied' } as const;

/**
 * The code, the reference and the HTTP status of a failed call, one click away behind Details (styleguide section
 * 9, alerts): support needs them, the person reading the panel does not.
 */
function ProblemFacts({ facts }: { facts: readonly Fact[] }) {
  const { t } = useI18n();
  if (facts.length === 0) return null;
  return (
    <details className="alert__details panel__details">
      <summary className="hit">{t('area.error.details')}</summary>
      <dl>
        {facts.map((fact) => (
          <div key={fact.label}>
            <dt>{t(fact.label)}</dt>
            <dd className="facts__with-action">
              <span data-testid={fact.testId}><FactValue value={fact.value} /></span>
              {fact.copy !== undefined && fact.value !== undefined ? <CopyButton value={fact.value} label={t(fact.copy.label)} copiedLabel={t(fact.copy.copied)} /> : null}
            </dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

/**
 * The calm alert shape: soft fill, a 3 px edge, an icon and a title, then one plain sentence. `calm` is for a state
 * that is nobody's fault (signed out): a plain panel announced as a status, with no alarm colour or icon.
 */
function AlertPanel({ tone, titleId, title, children }: { tone: 'bad' | 'warn' | 'calm'; titleId: string; title: string; children: ReactNode }) {
  const calm = tone === 'calm';
  return (
    <section className={`panel panel--${tone === 'bad' ? 'error' : tone}`} role={calm ? 'status' : 'alert'} data-testid={calm ? 'signed-out' : undefined} aria-labelledby={titleId}>
      <h1 id={titleId} className="panel__title">
        {calm ? null : <span className={`status-icon status-icon--${tone}`} aria-hidden="true">{tone === 'bad' ? '✕' : '!'}</span>}
        {title}
      </h1>
      {children}
    </section>
  );
}

/** A host that answers with an error says so in one sentence; its code and reference wait behind Details. */
export function HostErrorPanel({ error, onRetry }: { error: HostError; onRetry: () => void }) {
  const { t } = useI18n();
  const text = errorText(error);
  const answered = error.kind === 'problem' || error.kind === 'unexpected-response';
  const facts: Fact[] = answered
    ? [
        { label: 'error.code', value: error.kind === 'problem' ? error.code : undefined, testId: 'error-code', copy: COPY_CODE },
        { label: 'error.correlationId', value: error.correlationId, testId: 'error-correlation-id', copy: COPY_REFERENCE },
        { label: 'error.status', value: String(error.status) },
      ]
    : [];
  // A signed-out user has done nothing wrong: one calm line and the way back in, no alarm colour or icon.
  const calm = error.kind === 'signed-out';
  return (
    <AlertPanel tone={calm ? 'calm' : 'bad'} titleId="host-error-title" title={t(text.title)}>
      <p className="panel__body">{t(text.body)}</p>
      <ProblemFacts facts={facts} />
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
    </AlertPanel>
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
    <AlertPanel tone="bad" titleId="config-error-title" title={t('config.title')}>
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
    </AlertPanel>
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
    <AlertPanel tone="bad" titleId="sign-in-error-title" title={t('signIn.failed.title')}>
      <p className="panel__body">{t(SIGN_IN_TEXT[problem.reason])}</p>
      <ProblemFacts facts={code !== undefined ? [{ label: 'error.code', value: code, testId: 'error-code', copy: COPY_CODE }] : []} />
      <div className="panel__actions">
        <button type="button" className="btn btn--primary" onClick={onRetry}>
          {t('action.signIn')}
        </button>
      </div>
    </AlertPanel>
  );
}

/** A host on an engine line this cockpit does not support: its version and the range, and no screens (CKP-014). */
export function UnsupportedVersionPanel({ hostName, engineVersion, lines }: { hostName: string; engineVersion: string; lines: readonly string[] }) {
  const { t } = useI18n();
  return (
    <AlertPanel tone="warn" titleId="version-title" title={t('version.title')}>
      <p className="panel__body" data-testid="version-text">{t('version.body', { service: hostName, version: engineVersion, supported: lines.join(t('version.and')) })}</p>
      <p className="panel__hint">{t('version.hint')}</p>
      <ProblemFacts facts={[{ label: 'error.code', value: 'UNSUPPORTED_VERSION', testId: 'error-code', copy: COPY_CODE }]} />
    </AlertPanel>
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
