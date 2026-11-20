import { toHostError } from '../../../api/hostError';
import { CopyButton } from '../../../components/CopyButton';
import { useI18n } from '../../../i18n/I18nProvider';
import { ValueText } from '../../rules/components/CellValue';
import { valueLabel } from '../../rules/model/cells';
import { readResult } from '../../rules/model/reading';
import { maskedValue } from '../model/references';

/** A closed padlock, drawn with the text colour; decorative, the words beside it carry the meaning. */
export function LockIcon() {
  return (
    <svg className="lock-icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <rect x="3" y="7" width="10" height="7" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  );
}

/**
 * A value as the decision log holds it. A masked input reads as a deliberate state, "Masked" with a padlock and a
 * short keyed-hash reference, never as an error or a blank; the plain value is not available anywhere (RLE-031).
 */
export function LoggedValue({ value, missing = false }: { value: unknown; missing?: boolean }) {
  const { t, locale } = useI18n();
  const masked = maskedValue(value);
  if (masked !== undefined) {
    return (
      <span className="masked" title={t('dlog.maskedTitle')}>
        <LockIcon />
        <span>{t('dlog.masked')}</span>
        <span className="masked__ref mono" aria-label={t('dlog.maskedRef', { ref: masked.reference })}>{masked.reference}</span>
      </span>
    );
  }
  if (missing || value === null || value === undefined) return <span className="muted">{t('dlog.noValue')}</span>;
  // A code such as GOLD reads as a word, as in the rule table; other text and numbers as the engine sent them.
  if (typeof value === 'string') return <span>{valueLabel(value)}</span>;
  return <span className="tnum"><ValueText value={readResult(value, locale)} /></span>;
}

/**
 * A refused or failed call in an audit screen: one plain sentence the screen chose for the status, and the code
 * and reference support needs behind Details, with Copy.
 */
export function AuditError({ message, error, onRetry, retryLabel }: { message: string; error: unknown; onRetry?: (() => void) | undefined; retryLabel?: string | undefined }) {
  const { t } = useI18n();
  const host = toHostError(error);
  const code = host.kind === 'problem' ? host.code : undefined;
  const reference = host.kind === 'problem' || host.kind === 'unexpected-response' ? host.correlationId : undefined;
  return (
    <div className="alert alert--error audit-error" role="alert">
      <span className="alert__icon" aria-hidden="true">✕</span>
      <div className="alert__body">
        <p>{message}</p>
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
      {onRetry !== undefined ? <button type="button" className="btn btn--quiet btn--small hit" onClick={onRetry}>{retryLabel ?? t('action.retry')}</button> : null}
    </div>
  );
}

/** HTTP status of a failed call, when the host answered at all. */
export function statusOf(error: unknown): number | undefined {
  const host = toHostError(error);
  return host.kind === 'problem' || host.kind === 'unexpected-response' ? host.status : undefined;
}

/** Problem code of a failed call, when the host sent one. */
export function codeOf(error: unknown): string | undefined {
  const host = toHostError(error);
  return host.kind === 'problem' ? host.code : undefined;
}

/** Problem detail of a failed call, when the host sent one. Only searched for ids the screen already knows. */
export function detailOf(error: unknown): string {
  const host = toHostError(error);
  return host.kind === 'problem' ? (host.detail ?? '') : '';
}
