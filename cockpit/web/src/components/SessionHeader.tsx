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
}

export function initials(name: string): string {
  const words = name.trim().split(/\s+/u).filter(Boolean);
  const first = words[0]?.[0] ?? '';
  const last = words.length > 1 ? (words[words.length - 1]?.[0] ?? '') : '';
  return (first + last).toLocaleUpperCase();
}

export function SessionHeader({ session, banner }: { session: SessionSummary | undefined; banner: string | undefined }) {
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
      {banner !== undefined ? (
        <span className="env-banner" title={banner}>
          {banner}
        </span>
      ) : null}
      {session ? <SessionContext session={session} /> : null}
      <div className="spacer" />
      <PreferenceControls />
    </header>
  );
}

function SessionContext({ session }: { session: SessionSummary }) {
  const { t } = useI18n();
  return (
    <dl className="ctx" aria-label={t('header.session')}>
      <div className="ctx__item ctx__item--host">
        <dt>{t('header.service')}</dt>
        <dd>
          <span data-testid="header-host" title={session.hostLabel}>
            {session.hostLabel}
          </span>
          {session.environment !== undefined ? (
            <span className="chip" title={`${t('header.environment')}: ${session.environment}`}>
              {session.environment}
            </span>
          ) : null}
        </dd>
      </div>
      <div className="ctx__item ctx__item--tenant">
        <dt>{t('header.tenant')}</dt>
        <dd>
          <span data-testid="header-tenant" title={session.tenant}>
            {session.tenant}
          </span>
        </dd>
      </div>
      <div className="ctx__item ctx__item--user">
        <dt>{t('header.user')}</dt>
        <dd>
          <span className="avatar" aria-hidden="true">
            {initials(session.userName)}
          </span>
          <span data-testid="header-user" title={session.userId}>
            {session.userName}
          </span>
        </dd>
      </div>
    </dl>
  );
}
