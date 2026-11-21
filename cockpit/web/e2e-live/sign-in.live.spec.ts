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

      test(`TR_UI_03_signed_out_line_passes_every_axe_rule_${locale}_${theme}_1440x${String(height)}`, async ({ page }) => {
        await page.setViewportSize({ width: 1440, height });
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await page.goto(`/login?lang=${locale}&logout`);
        await expect(page.getByRole('status')).toHaveText(locale === 'en' ? /You have signed out\./ : /Bạn đã đăng xuất/);
        const axe = await new AxeBuilder({ page }).analyze();
        expect(axe.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(' | ')}`)).toEqual([]);
        await page.screenshot({ path: `${SHOTS}/sign-in-signed-out-1440x${String(height)}-${theme}-${locale}.png` });
      });
    }
  }
}

for (const [locale, theme] of [['en', 'light'], ['vi', 'dark']] as const) {
  test(`TR_UI_03_a_demo_row_shows_hover_and_focus_and_fills_the_username_${locale}_${theme}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await page.goto(`/login?lang=${locale}`);
    const rows = page.locator('.demo__user');
    // Keyboard focus on the first row, the pointer over the second: both states read as actions.
    await rows.first().focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    await rows.nth(1).hover();
    const focusRing = await rows.first().evaluate((row) => getComputedStyle(row).outlineStyle);
    expect(focusRing).toBe('solid');
    const resting = await rows.nth(2).evaluate((row) => getComputedStyle(row).backgroundColor);
    await expect.poll(() => rows.nth(1).evaluate((row) => getComputedStyle(row).backgroundColor)).not.toBe(resting);
    await page.screenshot({ path: `${SHOTS}/sign-in-demo-rows-1440x900-${theme}-${locale}.png` });
    await page.keyboard.press('Enter');
    await expect(page.locator('#username')).toHaveValue(await rows.first().getAttribute('data-username') ?? '');
  });
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
  // The refusal offers the next step; the journey ends on a useful Home, not on the refusal
  await page.getByRole('link', { name: 'Go to Home' }).click();
  await expect(page).toHaveURL(/#\/home$/);
  await expect(page.getByRole('heading', { level: 1, name: /^Good (morning|afternoon|evening), / })).toBeVisible();
  await expect(page.locator('.home')).toBeVisible();
  await page.mouse.move(0, 0);
  await page.screenshot({ path: `${SHOTS}/journey-4-home-1440x900-light-en.png` });
});
