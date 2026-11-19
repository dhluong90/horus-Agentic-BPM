import { useState } from 'react';
import { PREVIEW_AREAS } from '../app/navigation';
import { areaRoute, formatRoute, type Area } from '../app/route';
import { AreaIcon, CollapseIcon } from '../components/icons';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';
import { readMenuCollapsed, storeMenuCollapsed } from '../preferences/storage';

const TEXT: Record<Area, { title: MessageKey; hint: MessageKey }> = {
  home: { title: 'menu.home', hint: 'menu.home.hint' },
  rules: { title: 'menu.rules', hint: 'menu.rules.hint' },
  approvals: { title: 'menu.approvals', hint: 'menu.approvals.hint' },
  decisions: { title: 'menu.decisions', hint: 'menu.decisions.hint' },
  processes: { title: 'menu.processes', hint: 'menu.processes.hint' },
};

/**
 * The left menu: one link per area this user may use, with icon, name and a short hint. It collapses to
 * icons; the choice is a display preference kept in the browser.
 */
export function NavRail({ areas, current }: { areas: readonly Area[]; current: Area }) {
  const { t } = useI18n();
  const [collapsed, setCollapsed] = useState(readMenuCollapsed);
  const toggleLabel = collapsed ? t('menu.expand') : t('menu.collapse');
  return (
    <nav className={collapsed ? 'navrail navrail--collapsed' : 'navrail'} aria-label={t('menu.label')} data-testid="nav-rail">
      <div className="navrail__sticky">
        <ul className="navrail__list" id="navrail-list">
          {areas.map((area) => {
            const title = t(TEXT[area].title);
            const preview = PREVIEW_AREAS.has(area);
            return (
              <li key={area}>
                <a
                  className="navrail__item"
                  href={formatRoute(areaRoute(area))}
                  aria-current={area === current ? 'page' : undefined}
                  title={collapsed ? title : undefined}
                  // Expanded, the name is the visible title; collapsed, the same words as a label. The hint is a visual aid.
                  aria-label={collapsed ? (preview ? `${title} · ${t('menu.preview')}` : title) : undefined}
                >
                  <AreaIcon area={area} />
                  <span className="navrail__title">
                    {title}
                    {preview ? <> <span className="sr-only">· {t('menu.preview')}</span></> : null}
                  </span>
                  <span className="navrail__hint" aria-hidden="true">
                    {preview ? <span className="navrail__preview">{t('menu.preview')} · </span> : null}
                    {t(TEXT[area].hint)}
                  </span>
                </a>
              </li>
            );
          })}
        </ul>
        <div className="navrail__foot">
          <button
            type="button"
            className="navrail__toggle"
            aria-controls="navrail-list"
            aria-expanded={!collapsed}
            aria-label={toggleLabel}
            title={toggleLabel}
            onClick={() => {
              const next = !collapsed;
              storeMenuCollapsed(next);
              setCollapsed(next);
            }}
          >
            <CollapseIcon collapsed={collapsed} />
            <span className="navrail__toggle-label">{t('menu.collapse')}</span>
          </button>
        </div>
      </div>
    </nav>
  );
}
