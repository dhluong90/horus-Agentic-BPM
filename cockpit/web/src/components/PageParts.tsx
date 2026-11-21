import type { ReactNode } from 'react';
import { toHostError, type HostError } from '../api/hostError';
import type { Area } from '../app/route';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';
import { CopyButton } from './CopyButton';
import { AreaIcon } from './icons';

/** Page title, a subtitle of ten words or fewer, an optional Preview tag and the primary action. */
export function PageHead({ id, title, subtitle, preview = false, action }: { id: string; title: string; subtitle: string; preview?: boolean; action?: ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="page-head page-head--bar">
      <div className="page-head__text">
        <h1 id={id}>
          {title}
          {preview ? <> <span className="tag tag--preview">{t('menu.preview')}</span></> : null}
        </h1>
        <p className="page-head__sub">{subtitle}</p>
      </div>
      {action !== undefined ? <div className="page-head__action">{action}</div> : null}
    </div>
  );
}

export interface NextAction {
  label: string;
  href: string;
}

/** The empty state of a card or a page: a line icon, one sentence, and the next action when there is one. */
export function EmptyState({ text, action, icon }: { text: string; action?: NextAction | undefined; icon?: Area | undefined }) {
  return (
    <div className="empty-state">
      {icon !== undefined ? <span className="empty-state__icon"><AreaIcon area={icon} /></span> : null}
      <p>{text}</p>
      {action !== undefined ? (
        <a className="link-action" href={action.href}>
          {action.label} <span aria-hidden="true">→</span>
        </a>
      ) : null}
    </div>
  );
}

/** Skeleton rows at the height of the real rows while a list loads; no spinner inside a card. */
export function LoadingRows({ rows = 3 }: { rows?: number }) {
  const { t } = useI18n();
  return (
    <div className="skeleton" role="status" aria-label={t('area.loading')}>
      {Array.from({ length: rows }, (_, index) => (
        <span key={index} className="skeleton__row" />
      ))}
    </div>
  );
}

/** What a failed call means for the user. 404 and 405: the host's engine is older than this list. */
function errorKey(host: HostError): MessageKey {
  if (host.kind === 'signed-out') return 'area.error.signedOut';
  if (host.kind === 'no-access') return 'area.error.forbidden';
  const status = host.kind === 'problem' || host.kind === 'unexpected-response' ? host.status : undefined;
  if (status === 403) return 'area.error.forbidden';
  if (status === 404 || status === 405) return 'area.error.notOffered';
  if (status === 422 || status === 413) return 'area.error.invalidFile';
  if (host.kind === 'problem' && host.code === 'RULE_SET_EXISTS') return 'area.error.exists';
  return 'area.error.generic';
}

/**
 * A failed engine call inside a card: one plain sentence and Try again. The error code and reference, which
 * support needs, sit behind Details with a Copy button.
 */
export function AreaError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useI18n();
  const host = toHostError(error);
  const key = errorKey(host);
  // An engine older than this list is a state, not a failure: say so plainly; trying again cannot help.
  if (key === 'area.error.notOffered') return <EmptyState text={t(key)} />;
  const code = host.kind === 'problem' ? host.code : undefined;
  const reference = host.kind === 'problem' || host.kind === 'unexpected-response' ? host.correlationId : undefined;
  return (
    <div className="alert alert--error" role="alert">
      <span className="alert__icon" aria-hidden="true">✕</span>
      <div className="alert__body">
        <p>{t(key)}</p>
        {code !== undefined || reference !== undefined ? (
          <details className="alert__details">
            <summary>{t('area.error.details')}</summary>
            <dl>
              {code !== undefined ? <div><dt>{t('error.code')}</dt><dd className="mono">{code}</dd></div> : null}
              {reference !== undefined ? <div><dt>{t('error.correlationId')}</dt><dd className="mono">{reference} <CopyButton value={reference} /></dd></div> : null}
            </dl>
          </details>
        ) : null}
      </div>
      <button type="button" className="btn btn--quiet btn--small hit" onClick={host.kind === 'signed-out' ? () => { window.location.reload(); } : onRetry}>
        {host.kind === 'signed-out' ? t('action.signIn') : t('action.retry')}
      </button>
    </div>
  );
}
