import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { signOutOfHost } from '../api/hostClient';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';
import { BrandMark } from './BrandMark';
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
 * one service pill, the language and theme switches and the account menu.
 */
export function SessionHeader({ session, banner, breadcrumb }: { session: SessionSummary | undefined; banner: string | undefined; breadcrumb?: ReactNode }) {
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
      {session ? <ServicePill session={session} /> : null}
      <PreferenceControls />
      {session ? <AccountMenu session={session} /> : null}
    </header>
  );
}

function ServicePill({ session }: { session: SessionSummary }) {
  const { t } = useI18n();
  const name = [
    `${t('header.service')}: ${session.hostLabel}`,
    `${t('header.tenant')}: ${session.tenant}`,
    ...(session.environment !== undefined ? [`${t('header.environment')}: ${session.environment}`] : []),
  ].join(', ');
  return (
    <p className="service-pill" title={name}>
      <span className="sr-only">{name}</span>
      <span className="service-pill__dot" aria-hidden="true" />
      <span className="service-pill__name" data-testid="header-host" title={session.hostLabel} aria-hidden="true">{session.hostLabel}</span>
      <span className="service-pill__tenant" data-testid="header-tenant" aria-hidden="true">{session.tenant}</span>
      {session.environment !== undefined ? <span className="tag tag--env" aria-hidden="true">{session.environment}</span> : null}
    </p>
  );
}

/** Avatar button and its menu: full name, what the user may do in words, Sign out. */
function AccountMenu({ session }: { session: SessionSummary }) {
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
        <button type="button" className="btn btn--quiet account__signout" onClick={() => void signOut(session.baseUrl)}>
          {t('header.signOut')}
        </button>
      </div>
    </div>
  );
}

async function signOut(baseUrl: string): Promise<void> {
  await signOutOfHost(baseUrl);
  window.location.reload();
}
