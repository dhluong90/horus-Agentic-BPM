import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

/*
 * The example host's sign-in page and the journey into the embedded cockpit, against the running host on the
 * dev profile (#423, #41). Every axe rule runs, label-content-name-mismatch included. Screenshots go to
 * e2e/screenshots/live/ (SCREENSHOT_DIR overrides it).
 */

const PASSWORD = process.env['LIVE_PASSWORD'] ?? 'demo-pass';
const SHOTS = process.env['SCREENSHOT_DIR'] ?? 'e2e/screenshots/live';
const DECISION = '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9';

for (const locale of ['en', 'vi'] as const) {
  for (const theme of ['light', 'dark'] as const) {
    for (const height of [900, 1200] as const) {
      test(`TR_UI_03_sign_in_page_passes_every_axe_rule_${locale}_${theme}_1440x${String(height)}`, async ({ page }) => {
        await page.setViewportSize({ width: 1440, height });
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await page.goto(`/login?lang=${locale}`);
        await expect(page.locator('.demo__user').first()).toBeVisible();

        const axe = await new AxeBuilder({ page }).analyze();
        expect(axe.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(' | ')}`)).toEqual([]);
        // Voice control: each demo button's name starts with what it shows
        for (const button of await page.locator('.demo__user').all()) {
          const user = await button.locator('.demo__name').innerText();
          const role = await button.locator('.demo__role').innerText();
          expect(await button.getAttribute('aria-label')).toMatch(new RegExp(`^${user}, ${role}, `));
        }
        await page.screenshot({ path: `${SHOTS}/sign-in-1440x${String(height)}-${theme}-${locale}.png` });
      });
    }
  }
}

test('CKP_041_a_decision_link_survives_the_host_sign_in_of_the_embedded_cockpit', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/bpm/cockpit/#/decisions/${DECISION}`);
  await expect(page).toHaveURL(/\/login/);
  await page.screenshot({ path: `${SHOTS}/journey-1-sign-in-1440x900-light-en.png` });
  await page.locator('.demo__user', { hasText: 'lan' }).click();
  await page.locator('#password').fill(PASSWORD);
  await page.screenshot({ path: `${SHOTS}/journey-2-filled-1440x900-light-en.png` });
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();

  // Spring adds ?continue to the saved page; the fragment rides through the sign-in redirects
  await expect(page).toHaveURL(new RegExp(`/bpm/cockpit/(\\?continue)?#/decisions/${DECISION}$`));
  // Lan holds no rules:log:read on the example host: the link is refused in words and nothing is requested
  await expect(page.getByText('You do not have permission to open decisions.')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/journey-3-decision-link-1440x900-light-en.png` });
  await page.getByRole('link', { name: 'Home' }).first().click();
  await expect(page.getByTestId('header-user')).toBeAttached();
  await page.screenshot({ path: `${SHOTS}/journey-4-home-1440x900-light-en.png` });
});
