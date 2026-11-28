import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { signOutOfHost } from '../api/hostClient';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';
import { BrandMark } from './BrandMark';
import { EnvironmentTag, environmentLabel } from './EnvironmentTag';
import { PreferenceControls } from './PreferenceControls';

/** What the header shows about the session. Absent until /meta and /me have both answered. */
export interface SessionSummary {
  hostLabel: string;
  environment: string | undefined;
  tenant: string;
  userName: string;
  userId: string;
  /** What the user may do, in words, from /me permissions (never role names). */
  capabilities: readonly MessageKey[];
  /** The host's base URL, for sign-out on the host. */
  baseUrl: string;
  /** Standalone mode signs out at the identity provider; embedded mode at the host. */
  signOut?: () => Promise<boolean>;
}

export function initials(name: string): string {
  const words = name.trim().split(/\s+/u).filter(Boolean);
  const first = words[0]?.[0] ?? '';
  const last = words.length > 1 ? (words[words.length - 1]?.[0] ?? '') : '';
  return (first + last).toLocaleUpperCase();
}

/** Capabilities in words, in a stable order, from the permissions /me lists. */
export function capabilities(permissions: readonly string[]): MessageKey[] {
  const granted = new Set(permissions);
  const words: [string, MessageKey][] = [
    ['rules:author', 'capability.author'],
    ['rules:approve', 'capability.approver'],
    ['rules:log:read', 'capability.auditor'],
  ];
  const found = words.filter(([permission]) => granted.has(permission)).map(([, key]) => key);
  if (found.length === 0 && granted.has('rules:read')) return ['capability.reader'];
  return found;
}

/**
 * The top bar (styleguide section 8): product mark over the menu column, the breadcrumb over the content, then
 * one service line, the language and theme switches and the account menu.
 */
export function SessionHeader({ session, banner, breadcrumb, service }: {
  session: SessionSummary | undefined;
  banner: string | undefined;
  breadcrumb?: ReactNode;
  /** Standalone mode: the host picker, shown even before or without a session on the current host. */
  service?: ReactNode;
}) {
  const { t } = useI18n();
  return (
    <header className="topbar" data-testid="session-header">
      <a className="skip" href="#main">
        {t('app.skipToMain')}
      </a>
      <div className="brand">
        <BrandMark />
        <span className="brand-name">{t('app.name')}</span>
      </div>
      <div className="topbar__crumbs">{breadcrumb}</div>
      {banner !== undefined ? (
        <span className="env-banner" title={banner}>
          {banner}
        </span>
      ) : null}
      {service ?? (session ? <ServiceLine session={session} /> : null)}
      <PreferenceControls />
      {session ? <AccountMenu session={session} /> : null}
    </header>
  );
}

function ServiceLine({ session }: { session: SessionSummary }) {
  const { t, locale } = useI18n();
  const name = [
    `${t('header.service')}: ${session.hostLabel}`,
    `${t('header.tenant')}: ${session.tenant}`,
    ...(session.environment !== undefined ? [`${t('header.environment')}: ${environmentLabel(session.environment, locale)}`] : []),
  ].join(', ');
  return (
    <p className="service" title={name}>
      <span className="sr-only">{name}</span>
      <span className="service__dot" aria-hidden="true" />
      <span className="service__name" data-testid="header-host" title={session.hostLabel} aria-hidden="true">{session.hostLabel}</span>
      <span className="service__tenant" data-testid="header-tenant" aria-hidden="true">{session.tenant}</span>
      {session.environment !== undefined ? <EnvironmentTag environment={session.environment} /> : null}
    </p>
  );
}

/** Avatar button and its menu: full name, what the user may do in words, Sign out. */
function AccountMenu({ session }: { session: SessionSummary }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [signOutFailed, setSignOutFailed] = useState(false);
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
    <div className="account" ref={root}>
      <button
        ref={button}
        type="button"
        className="avatar-btn"
        aria-expanded={open}
        aria-controls={menuId}
        title={session.userName}
        onClick={() => {
          setOpen((value) => !value);
        }}
      >
        <span aria-hidden="true">{initials(session.userName)}</span>
        <span className="sr-only" data-testid="header-user">{session.userName}</span>
        <span className="sr-only">, {t('header.accountMenu')}</span>
      </button>
      <div className="account__menu" id={menuId} hidden={!open}>
        <p className="account__name">{session.userName}</p>
        <p className="account__id">{session.userId}</p>
        {session.capabilities.length > 0 ? <p className="account__roles">{session.capabilities.map((key) => t(key)).join(' · ')}</p> : null}
        <button type="button" className="btn btn--quiet account__signout" onClick={() => {
          setSignOutFailed(false);
          if (session.signOut) {
            // Standalone: the identity provider ends the session and sends the browser back.
            void session.signOut().then((leaving) => {
              if (!leaving) setSignOutFailed(true);
            });
            return;
          }
          void signOutOfHost(session.baseUrl).then((signedOut) => {
            // Reload only once the host has ended the session; otherwise the user would still be signed in.
            if (signedOut) window.location.reload();
            else setSignOutFailed(true);
          });
        }}>
          {t('header.signOut')}
        </button>
        {signOutFailed ? <p className="account__error" role="alert">{t('header.signOutFailed')}</p> : null}
      </div>
    </div>
  );
}
