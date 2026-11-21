import { useState } from 'react';
import { PREVIEW_AREAS } from '../app/navigation';
import { areaRoute, formatRoute, type Area } from '../app/route';
import { AreaIcon, CollapseIcon } from '../components/icons';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';
import { readMenuCollapsed, storeMenuCollapsed } from '../preferences/storage';

const TITLE: Record<Area, MessageKey> = {
  home: 'menu.home',
  rules: 'menu.rules',
  approvals: 'menu.approvals',
  decisions: 'menu.decisions',
  processes: 'menu.processes',
};

/**
 * The left menu (styleguide section 8): icon and label per area, the review count on Approvals, and the
 * preview areas under one "Preview" heading. Collapsed, it shows icons with a tooltip; the count becomes a dot.
 */
export function NavRail({ areas, current, reviewCount, reviewMore = false }: { areas: readonly Area[]; current: Area; reviewCount: number; reviewMore?: boolean }) {
  const { t } = useI18n();
  const [collapsed, setCollapsed] = useState(readMenuCollapsed);
  const toggleLabel = collapsed ? t('menu.expand') : t('menu.collapse');
  const main = areas.filter((area) => !PREVIEW_AREAS.has(area));
  const preview = areas.filter((area) => PREVIEW_AREAS.has(area));

  const item = (area: Area) => {
    const title = t(TITLE[area]);
    const isPreview = PREVIEW_AREAS.has(area);
    // A host with more requests than one page shows "200+" (cockpit DESIGN section 3).
    const count = area === 'approvals' && reviewCount > 0 ? `${String(reviewCount)}${reviewMore ? '+' : ''}` : undefined;
    const label = isPreview ? t('menu.withPreview', { title }) : title;
    const name = count !== undefined ? `${label}, ${t('menu.toReview', { count })}` : label;
    return (
      <li key={area}>
        <a
          className="navrail__item"
          href={formatRoute(areaRoute(area))}
          aria-current={area === current ? 'page' : undefined}
          data-tip={collapsed ? label : undefined}
          // Collapsed, the label is not visible, so the link carries it; expanded, the visible label is the name.
          aria-label={collapsed ? name : undefined}
        >
          <AreaIcon area={area} />
          <span className="navrail__title">{title}</span>
          {count !== undefined ? <span className="navrail__count tnum" aria-hidden="true">{count}</span> : null}
          {count !== undefined && !collapsed ? <span className="sr-only">, {t('menu.toReview', { count })}</span> : null}
        </a>
      </li>
    );
  };

  return (
    <nav className={collapsed ? 'navrail navrail--collapsed' : 'navrail'} aria-label={t('menu.label')} data-testid="nav-rail">
      <div className="navrail__sticky">
        <ul className="navrail__list" id="navrail-list">
          {main.map(item)}
        </ul>
        {preview.length > 0 ? (
          <div className="navrail__group">
            <p className="navrail__heading" id="navrail-preview">{t('menu.preview')}</p>
            <ul className="navrail__list" aria-labelledby="navrail-preview">
              {preview.map(item)}
            </ul>
          </div>
        ) : null}
        <div className="navrail__foot">
          <button
            type="button"
            className="navrail__toggle"
            aria-controls="navrail-list"
            aria-expanded={!collapsed}
            aria-label={toggleLabel}
            data-tip={collapsed ? toggleLabel : undefined}
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
