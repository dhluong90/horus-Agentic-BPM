import { useEffect, useId, useRef, useState } from 'react';
import { useI18n } from '../i18n/I18nProvider';
import { CheckIcon } from './icons';

/** One tenant of the menu: its id and the display name the host gives it. */
export interface TenantChoice {
  id: string;
  label: string;
}

/**
 * The tenant switcher of the top bar (CKP-022): the current tenant, and a menu of the tenants the host allows this
 * user. Shown only when the host has switching on and the user has more than one tenant.
 */
export function TenantSwitcher({ tenants, current, label, onSelect }: { tenants: readonly TenantChoice[]; current: string; label: string; onSelect: (tenant: string) => void }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        button.current?.focus();
      }
    };
    const onClick = (event: MouseEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onClick);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  return (
    <div className="host-picker tenant-switcher" ref={root}>
      <button
        ref={button}
        type="button"
        className="service-pill service-pill--button tenant-switcher__button"
        aria-expanded={open}
        aria-controls={menuId}
        data-testid="tenant-switcher"
        title={`${t('header.tenant')}: ${label}`}
        onClick={() => { setOpen((value) => !value); }}
      >
        <span className="sr-only">{`${t('header.tenant')}: ${label}, ${t('tenant.switch')}`}</span>
        <span className="service-pill__tenant" aria-hidden="true">{label}</span>
        <svg className="service-pill__caret" viewBox="0 0 12 12" width="12" height="12" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M3 4.5 6 7.5 9 4.5" /></svg>
      </button>
      <div className="host-picker__menu" id={menuId} hidden={!open}>
        <p className="host-picker__title" id={`${menuId}-title`}>{t('tenant.menuTitle')}</p>
        <ul aria-labelledby={`${menuId}-title`}>
          {tenants.map((tenant) => {
            const selected = tenant.id === current;
            return (
              <li key={tenant.id}>
                <button
                  type="button"
                  className={selected ? 'host-picker__item tenant-switcher__item is-selected' : 'host-picker__item tenant-switcher__item'}
                  aria-current={selected ? 'true' : undefined}
                  onClick={() => {
                    setOpen(false);
                    if (!selected) onSelect(tenant.id);
                  }}
                >
                  <span className="host-picker__label">{tenant.label}</span>
                  {tenant.label !== tenant.id ? <span className="mono tenant-switcher__id">{tenant.id}</span> : null}
                  {selected ? <span className="host-picker__check" aria-hidden="true"><CheckIcon /></span> : null}
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

/**
 * A refused tenant: one sentence, the reference behind Details, and a way to dismiss it (CKP-022). It names no
 * tenant: the host's answer does not say which one it refused, and a proxy may have changed the one sent.
 */
export function TenantRefusedNotice({ correlationId, onDismiss }: { correlationId: string | undefined; onDismiss: () => void }) {
  const { t } = useI18n();
  return (
    <div className="alert alert--error tenant-refusal" role="alert" data-testid="tenant-refused">
      <span className="alert__icon" aria-hidden="true">✕</span>
      <div className="alert__body">
        <p>{t('tenant.refused')}</p>
        <p className="tenant-refusal__back">{t('tenant.refusedBack')}</p>
        {correlationId !== undefined ? (
          <details className="alert__details">
            <summary>{t('area.error.details')}</summary>
            <dl>
              <div><dt>{t('error.code')}</dt><dd className="mono">TENANT_NOT_ALLOWED</dd></div>
              <div><dt>{t('error.correlationId')}</dt><dd className="mono" data-testid="tenant-refused-reference">{correlationId}</dd></div>
            </dl>
          </details>
        ) : null}
      </div>
      <button type="button" className="btn btn--quiet btn--small hit" onClick={onDismiss}>{t('tenant.dismiss')}</button>
    </div>
  );
}
