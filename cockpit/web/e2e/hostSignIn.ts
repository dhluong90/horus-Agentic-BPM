import { readFileSync } from 'node:fs';
import type { Page } from '@playwright/test';

/*
 * The example host's own sign-in page (examples/rule-engine-only): its template, styles, script and message files,
 * filled in as the host's SignInPage fills them on the dev profile, so the journey's first screen is the real form
 * and not a stand-in. Only the substitution is repeated here; every word and every rule of layout is the host's.
 */

const ROOT = new URL('../../../examples/rule-engine-only/src/main/resources/', import.meta.url);
const read = (path: string) => readFileSync(new URL(path, ROOT), 'utf8');

/** The demo users of the host's dev profile and their roles (HostSecurityConfiguration.DEMO_USERS). */
const DEMO_USERS: readonly { username: string; roles: readonly string[] }[] = [
  { username: 'lan', roles: ['PRICING_AUTHOR'] },
  { username: 'minh', roles: ['PRICING_APPROVER'] },
  { username: 'hoa', roles: ['PRICING_AUTHOR', 'PRICING_APPROVER'] },
  { username: 'tuan', roles: ['TELLER'] },
];

function messages(locale: 'en' | 'vi'): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of read(`sign-in/messages${locale === 'vi' ? '_vi' : ''}.properties`).split('\n')) {
    const match = /^([^#=\s][^=]*)=(.*)$/.exec(line);
    if (match?.[1] !== undefined && match[2] !== undefined) out[match[1].trim()] = match[2];
  }
  return out;
}

const escape = (value: string) => value.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c);
const capitalise = (text: string) => (text === '' ? text : text.charAt(0).toUpperCase() + text.slice(1));

/** The page as the host renders it for `locale`, with `service` as the host label. */
export function hostSignInHtml(locale: 'en' | 'vi', service: string): string {
  const m = messages(locale);
  const text: Record<string, string> = Object.fromEntries(Object.entries(m).filter(([key]) => !key.includes('.')));
  Object.assign(text, {
    lang: locale, assets: '/sign-in', action: '/login', csrfParameter: '_csrf', csrfToken: 'demo', service,
    subtitle: (m['subtitle'] ?? '').replace('{service}', service),
  });
  const users = DEMO_USERS.map((user) => {
    const role = capitalise(user.roles.map((r) => m[`role.${r}`] ?? r).join(` ${m['roleSeparator'] ?? ''} `));
    const pick = (m['demoPick'] ?? '').replace('{user}', user.username).replace('{role}', role);
    return `<li><button type="button" class="demo__user" data-username="${escape(user.username)}" aria-label="${escape(pick)}"><span class="demo__name">${escape(user.username)}</span><span class="demo__role">${escape(role)}</span></button></li>`;
  }).join('');
  const markup: Record<string, string> = {
    environmentTag: '',
    languageLinks: (['en', 'vi'] as const).map((l) => `<a href="/login?lang=${l}" lang="${l}" data-lang="${l}" hreflang="${l}"${l === locale ? ' aria-current="true"' : ''} title="${l === 'vi' ? 'Tiếng Việt' : 'English'}">${l.toUpperCase()}</a>`).join(''),
    alert: '', usernameInvalid: '', passwordInvalid: '',
    demo: `<aside class="demo" aria-labelledby="demo-body"><p class="demo__head"><span class="tag tag--demo">${escape(m['demoTag'] ?? '')}</span><span id="demo-body">${escape(m['demoBody'] ?? '')}</span></p><ul class="demo__users">${users}</ul></aside>`,
  };
  return read('sign-in/sign-in.html').replace(/\{\{\{(\w+)\}\}\}|\{\{(\w+)\}\}/g, (whole, raw: string | undefined, name: string | undefined) => {
    if (raw !== undefined) return markup[raw] ?? whole;
    const value = name !== undefined ? text[name] : undefined;
    if (value === undefined) throw new Error(`unknown placeholder ${whole}`);
    return escape(value);
  });
}

/** Serves the host's sign-in page at /login (any ?lang) and its assets at /sign-in/. */
export async function routeHostSignIn(page: Page, service: string): Promise<void> {
  await page.route('**/login*', async (route) => {
    const lang = new URL(route.request().url()).searchParams.get('lang') === 'vi' ? 'vi' : 'en';
    await route.fulfill({ contentType: 'text/html; charset=utf-8', body: hostSignInHtml(lang, service) });
  });
  await page.route('**/sign-in/sign-in.css', (route) => route.fulfill({ contentType: 'text/css; charset=utf-8', body: read('static/sign-in/sign-in.css') }));
  await page.route('**/sign-in/sign-in.js', (route) => route.fulfill({ contentType: 'text/javascript; charset=utf-8', body: read('static/sign-in/sign-in.js') }));
}
