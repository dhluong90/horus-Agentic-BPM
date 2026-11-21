import { useEffect, useId, useRef, useState } from 'react';
import { hostLabel, type RegistryHost } from '../app/config/registry';
import { areaOf, areaRoute, formatRoute, parseRoute, splitHost, withHost } from '../app/route';
import { useI18n } from '../i18n/I18nProvider';
import { CheckIcon } from './icons';

export interface HostPickerProps {
  hosts: readonly RegistryHost[];
  current: RegistryHost | undefined;
  /** The tenant of the current host, once its /me has answered. */
  tenant: string | undefined;
  /** The dot is green only when the current host is ready. */
  ready: boolean;
}

/** The address of another host: the same area there, never an object of this host (CKP-015). */
export function hostSwitchHref(hash: string, hostId: string): string {
  const area = areaOf(parseRoute(splitHost(hash).rest));
  return withHost(formatRoute(areaRoute(area)), hostId);
}

/**
 * The service pill of the top bar (styleguide section 8). With several hosts it opens the list of services;
 * choosing one shows that service alone. One host: the plain pill.
 */
export function HostPicker({ hosts, current, tenant, ready }: HostPickerProps) {
  const { t, locale } = useI18n();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const label = current ? hostLabel(current.label, locale) || current.id : t('host.choose');
  const name = [
    `${t('header.service')}: ${label}`,
    ...(tenant !== undefined ? [`${t('header.tenant')}: ${tenant}`] : []),
    ...(current?.environment !== undefined ? [`${t('header.environment')}: ${current.environment}`] : []),
  ].join(', ');

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

  const pill = (
    <>
      <span className={ready ? 'service-pill__dot' : 'service-pill__dot service-pill__dot--idle'} aria-hidden="true" />
      <span className="service-pill__name" data-testid="header-host" aria-hidden="true">{label}</span>
      {tenant !== undefined ? <span className="service-pill__tenant" data-testid="header-tenant" aria-hidden="true">{tenant}</span> : null}
      {current?.environment !== undefined ? <span className="tag tag--env" aria-hidden="true">{current.environment}</span> : null}
    </>
  );

  if (hosts.length < 2) {
    return (
      <p className="service-pill" title={name}>
        <span className="sr-only">{name}</span>
        {pill}
      </p>
    );
  }

  return (
    <div className="host-picker" ref={root}>
      <button
        ref={button}
        type="button"
        className="service-pill service-pill--button"
        aria-expanded={open}
        aria-controls={menuId}
        data-testid="host-picker"
        title={name}
        onClick={() => {
          setOpen((value) => !value);
        }}
      >
        <span className="sr-only">{`${name}, ${t('host.switch')}`}</span>
        {pill}
        <span className="service-pill__caret" aria-hidden="true">▾</span>
      </button>
      <div className="host-picker__menu" id={menuId} hidden={!open}>
        <p className="host-picker__title" id={`${menuId}-title`}>{t('host.menuTitle')}</p>
        <ul aria-labelledby={`${menuId}-title`}>
          {hosts.map((host) => {
            const selected = host.id === current?.id;
            return (
              <li key={host.id}>
                <a
                  className={selected ? 'host-picker__item is-selected' : 'host-picker__item'}
                  href={hostSwitchHref(window.location.hash, host.id)}
                  aria-current={selected ? 'true' : undefined}
                  onClick={() => {
                    setOpen(false);
                  }}
                >
                  <span className="host-picker__label">{hostLabel(host.label, locale) || host.id}</span>
                  {host.environment !== undefined ? <span className="host-picker__env">{host.environment}</span> : null}
                  {selected ? <span className="host-picker__check" aria-hidden="true"><CheckIcon /></span> : null}
                </a>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
