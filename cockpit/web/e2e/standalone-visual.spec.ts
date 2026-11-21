import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { meHa, meLan } from '../src/mocks/fixtures';
import { rememberLocale } from './mockHost';
import { ISSUER, mockStandalone, type HostAnswer } from './standaloneHosts';

/*
 * Screenshots of every standalone state for the visual check, at 1440x900 and 1440x1200, light and dark, English
 * and Vietnamese, each with the axe audit. Written to e2e/screenshots/ (SCREENSHOT_DIR overrides it).
 */

const DIR = process.env['SCREENSHOT_DIR'] ?? 'e2e/screenshots';
const DECISION = '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9';

interface State {
  name: string;
  hash: string;
  loans?: HostAnswer;
  kyc?: HostAnswer;
  /** Breaks sign-in at the identity provider. */
  refuseSignIn?: boolean;
  /** Holds the provider's discovery answer, so the signing-in state stays on screen. */
  holdSignIn?: boolean;
  ready: (page: Page) => Promise<void>;
  after?: (page: Page) => Promise<void>;
}

const STATES: readonly State[] = [
  { name: 'signing-in', hash: '#/rules', holdSignIn: true, ready: async (page) => { await expect(page.locator('[aria-busy="true"]')).toBeVisible(); } },
  { name: 'sign-in-failed', hash: '#/rules', refuseSignIn: true, ready: async (page) => { await expect(page.getByRole('alert')).toBeVisible(); } },
  { name: 'loans-rules', hash: '#/hosts/loans/rules', ready: async (page) => { await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible(); } },
  {
    name: 'host-picker-open', hash: '#/hosts/loans/rules',
    ready: async (page) => { await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible(); },
    after: async (page) => { await page.getByTestId('host-picker').click(); await expect(page.locator('.host-picker__menu')).toBeVisible(); },
  },
  { name: 'kyc-rules', hash: '#/hosts/kyc/rules', ready: async (page) => { await expect(page.getByRole('link', { name: 'kyc-risk' })).toBeVisible(); } },
  { name: 'kyc-home', hash: '#/hosts/kyc/home', ready: async (page) => { await expect(page.getByTestId('header-user')).toBeAttached(); } },
  {
    name: 'unsupported-version', hash: '#/hosts/kyc/rules',
    kyc: { meta: { modules: [{ module: 'rules', engineVersion: '0.1.9', apiVersion: 'v1', features: [] }] } },
    ready: async (page) => { await expect(page.getByTestId('version-text')).toBeVisible(); },
  },
  {
    name: 'host-error', hash: '#/hosts/kyc/rules',
    kyc: { meProblem: { status: 403, code: 'PERMISSION_DENIED', correlationId: '01JD3A7Q2M5N8P1R4S6T9V0W2X' } },
    ready: async (page) => { await expect(page.getByTestId('error-code')).toBeVisible(); },
  },
  { name: 'decision-choose-host', hash: `#/decisions/${DECISION}`, ready: async (page) => { await expect(page.locator('.host-choice')).toBeVisible(); } },
  { name: 'decision-from-link', hash: `#/decisions/${DECISION}?host=loans`, loans: { me: meHa }, ready: async (page) => { await expect(page.locator('.dlog-detail')).toBeVisible(); } },
  { name: 'decision-no-permission', hash: `#/decisions/${DECISION}?host=loans`, loans: { me: meLan }, ready: async (page) => { await expect(page.locator('.workspace .card[role="note"]')).toBeVisible(); } },
  { name: 'decision-invalid-link', hash: '#/decisions/not-a-decision?host=loans', loans: { me: meHa }, ready: async (page) => { await expect(page.locator('.notice-line')).toBeVisible(); } },
];

for (const state of STATES) {
  for (const height of [900, 1200] as const) {
    for (const theme of ['light', 'dark'] as const) {
      for (const locale of ['en', 'vi'] as const) {
        test(`CKP_015_visual_${state.name}_1440x${String(height)}_${theme}_${locale}`, async ({ page }) => {
          await page.setViewportSize({ width: 1440, height });
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockStandalone(page, { ...(state.loans ? { loans: state.loans } : {}), ...(state.kyc ? { kyc: state.kyc } : {}) });
          if (state.holdSignIn) {
            // The provider's discovery never answers, so the cockpit keeps showing that it is signing in.
            await page.route(`${ISSUER}/.well-known/openid-configuration`, () => undefined);
          }
          if (state.refuseSignIn) {
            await page.route(`${ISSUER}/protocol/openid-connect/auth**`, async (route) => {
              const url = new URL(route.request().url());
              const back = new URL(url.searchParams.get('redirect_uri') ?? '');
              back.searchParams.set('error', 'access_denied');
              back.searchParams.set('state', url.searchParams.get('state') ?? '');
              await route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><meta http-equiv="refresh" content="0;url=${back.toString().replaceAll('&', '&amp;')}"><title>Sign in</title>` });
            });
          }
          await page.goto(`./${state.hash}`);
          await state.ready(page);
          await state.after?.(page);
          await expect(page.locator('html')).toHaveAttribute('lang', locale);
          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
          expect(axe.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `${DIR}/standalone-${state.name}-1440x${String(height)}-${theme}-${locale}.png`, fullPage: false });
        });
      }
    }
  }
}
