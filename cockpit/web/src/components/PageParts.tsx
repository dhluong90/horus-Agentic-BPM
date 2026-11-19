import type { ReactNode } from 'react';
import { toHostError, type HostError } from '../api/hostError';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';

/** Page title, a subtitle of ten words or fewer, an optional plain "Preview" label and the primary action. */
export function PageHead({ id, title, subtitle, preview = false, action }: { id: string; title: string; subtitle: string; preview?: boolean; action?: ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="page-head page-head--bar">
      <div className="page-head__text">
        <h1 id={id}>
          {title}
          {preview ? <> <span className="page-head__preview">{t('menu.preview')}</span></> : null}
        </h1>
        <p className="page-head__sub">{subtitle}</p>
      </div>
      {action !== undefined ? <div className="page-head__action">{action}</div> : null}
    </div>
  );
}

/** One plain line and the next action, never a blank panel. */
export function EmptyState({ text, action }: { text: string; action?: { label: string; href: string } | undefined }) {
  return (
    <div className="empty-state">
      <p>{text}</p>
      {action !== undefined ? (
        <a className="link-action" href={action.href}>
          {action.label} <span aria-hidden="true">→</span>
        </a>
      ) : null}
    </div>
  );
}

export function LoadingLine() {
  const { t } = useI18n();
  return (
    <p className="empty-state" role="status">
      {t('area.loading')}
    </p>
  );
}

/** What a failed call means for the user. 404 and 405: the host's engine is older than this list. */
function errorKey(host: HostError): MessageKey {
  if (host.kind === 'signed-out') return 'area.error.signedOut';
  const status = host.kind === 'problem' || host.kind === 'unexpected-response' ? host.status : undefined;
  if (status === 403) return 'area.error.forbidden';
  if (status === 404 || status === 405) return 'area.error.notOffered';
  return 'area.error.generic';
}

/** A failed engine call inside a card: what happened, the code and reference when known, and Try again. */
export function AreaError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useI18n();
  const host = toHostError(error);
  const key = errorKey(host);
  // An engine older than this list is a state, not a failure: say so plainly; trying again cannot help.
  if (key === 'area.error.notOffered') return <EmptyState text={t(key)} />;
  const code = host.kind === 'problem' ? host.code : undefined;
  const reference = host.kind === 'problem' || host.kind === 'unexpected-response' ? host.correlationId : undefined;
  return (
    <div className="empty-state empty-state--error" role="alert">
      <p>
        <span className="status-icon status-icon--bad" aria-hidden="true">!</span> {t(key)}
      </p>
      {code !== undefined || reference !== undefined ? (
        <p className="empty-state__facts">
          {code !== undefined ? <span className="mono">{code}</span> : null}
          {reference !== undefined ? <span>{t('error.correlationId')}: <span className="mono">{reference}</span></span> : null}
        </p>
      ) : null}
      <button type="button" className="btn btn--quiet btn--small hit" onClick={host.kind === 'signed-out' ? () => { window.location.reload(); } : onRetry}>
        {host.kind === 'signed-out' ? t('action.signIn') : t('action.retry')}
      </button>
    </div>
  );
}
