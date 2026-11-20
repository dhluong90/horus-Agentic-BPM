import { useCrumbLabel } from '../app/crumbs';
import { formatRoute, useRoute, type Route } from '../app/route';
import { lastDecisionQuery } from '../features/decisions/lastQuery';
import { shortId } from '../features/decisions/model/references';
import type { MessageKey } from '../i18n/en';
import { useI18n } from '../i18n/I18nProvider';

const AREA_TITLE: Record<Exclude<Route['name'], 'home' | 'rule-set' | 'decision'>, MessageKey> = {
  'rule-sets': 'menu.rules',
  approvals: 'menu.approvals',
  decisions: 'menu.decisions',
  processes: 'menu.processes',
};

/** Where the user is, over the content: `Rules / card-fee / Version 2`. Nothing on Home. */
export function Breadcrumb() {
  const { t } = useI18n();
  const [route] = useRoute();
  const override = useCrumbLabel();
  const crumbs = trail(route, t);
  const last = crumbs.at(-1);
  if (override !== undefined && route.name === 'rule-set' && last) crumbs[crumbs.length - 1] = { label: override };
  if (crumbs.length === 0) return null;
  return (
    <nav aria-label={t('header.breadcrumb')} className="crumbs">
      <ol>
        {crumbs.map((crumb, index) => (
          <li key={crumb.label}>
            {index < crumbs.length - 1 && crumb.href !== undefined ? <a href={crumb.href}>{crumb.label}</a> : <span aria-current="page">{crumb.label}</span>}
          </li>
        ))}
      </ol>
    </nav>
  );
}

function trail(route: Route, t: (key: MessageKey, params?: Record<string, string>) => string): { label: string; href?: string }[] {
  switch (route.name) {
    case 'home':
      return [];
    case 'rule-set': {
      const items: { label: string; href?: string }[] = [
        { label: t('menu.rules'), href: formatRoute({ name: 'rule-sets' }) },
        { label: route.ruleSet, href: formatRoute({ name: 'rule-set', ruleSet: route.ruleSet, version: undefined, lens: undefined }) },
      ];
      if (route.version !== undefined) items.push({ label: t('rules.version', { version: String(route.version) }) });
      return items;
    }
    case 'decision':
      return [
        { label: t('menu.decisions'), href: formatRoute({ name: 'decisions', query: lastDecisionQuery() }) },
        { label: t('dlog.crumb', { id: shortId(route.decisionId) }) },
      ];
    default:
      return [{ label: t(AREA_TITLE[route.name]) }];
  }
}
