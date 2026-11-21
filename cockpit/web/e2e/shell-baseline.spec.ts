import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { en } from '../src/i18n/en';
import type { Scenario } from '../src/mocks/fixtures';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * The shell's language and accessibility baseline (#10): CKP-009 and CKP-010 over the header and the error view,
 * in light and dark, at 1440x900 and 1440x1200. Every later screen re-runs the same checks in its own slice.
 */

const KEYS = Object.keys(en);
const THEMES = ['light', 'dark'] as const;
const HEIGHTS = [900, 1200] as const;
const VIEWS: ReadonlyArray<{ name: string; scenario: Scenario; ready: boolean }> = [
  { name: 'header', scenario: 'ready', ready: true },
  { name: 'error-view', scenario: 'me-forbidden', ready: false },
];

async function open(page: Page, scenario: Scenario, ready: boolean) {
  await mockHost(page, scenario);
  await mockRuleEngine(page, scenario);
  await page.goto('./#/home');
  await expect(ready ? page.getByTestId('header-user') : page.getByRole('alert')).toBeAttached();
}

/** Text on screen that is a message key rather than a message. */
async function rawKeys(page: Page): Promise<string[]> {
  const text = await page.locator('body').innerText();
  const labels = await page.evaluate(() => [...document.querySelectorAll('[aria-label],[title]')].flatMap((element) => [element.getAttribute('aria-label') ?? '', element.getAttribute('title') ?? '']));
  const shown = [text, ...labels].join('\n');
  return KEYS.filter((key) => new RegExp(`(^|[\\s"'(])${key.replaceAll('.', '\\.')}($|[\\s"'),.])`, 'm').test(shown));
}

test.describe('CKP-010 one language at a time, no raw key', () => {
  for (const view of VIEWS) {
    test(`CKP_010_en_and_vi_i18n_coverage_complete_${view.name}_switches_en_vi_en`, async ({ page }) => {
      await open(page, view.scenario, view.ready);
      const english = await page.locator('header').innerText();

      await page.getByRole('button', { name: 'VI', exact: true }).click();
      await expect(page.locator('html')).toHaveAttribute('lang', 'vi');
      expect(await rawKeys(page)).toEqual([]);
      const vietnamese = await page.locator('header').innerText();
      expect(vietnamese).not.toBe(english);
      await expect(page.locator('main')).not.toContainText(view.ready ? 'Rules' : 'The service refused the request');

      await page.getByRole('button', { name: 'EN', exact: true }).click();
      await expect(page.locator('html')).toHaveAttribute('lang', 'en');
      expect(await rawKeys(page)).toEqual([]);
      expect(await page.locator('header').innerText()).toBe(english);
    });
  }
});

test.describe('CKP-009 keyboard and contrast', () => {
  for (const view of VIEWS) {
    for (const theme of THEMES) {
      for (const height of HEIGHTS) {
        test(`CKP_009_wcag_aa_and_keyboard_navigation_pass_${view.name}_${theme}_1440x${String(height)}`, async ({ page }) => {
          await page.setViewportSize({ width: 1440, height });
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await open(page, view.scenario, view.ready);
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);

          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
          const serious = axe.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
          expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);

          // Tab reaches every control of the header and the error view, each with a visible focus ring
          const controls = await page.locator('header a:visible, header button:visible, [role="alert"] button:visible').count();
          const reached = new Set<string>();
          for (let step = 0; step < controls + 4; step += 1) {
            await page.keyboard.press('Tab');
            const focused = await page.evaluate(() => {
              const element = document.activeElement as HTMLElement | null;
              if (!element || element === document.body) return undefined;
              if (!element.closest('header, [role="alert"]')) return undefined;
              const style = getComputedStyle(element);
              const ring = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) >= 2;
              return { name: `${element.tagName}:${element.innerText.trim()}:${element.getAttribute('aria-label') ?? ''}`, ring };
            });
            if (focused) {
              expect(focused.ring, `${focused.name} shows a focus ring`).toBe(true);
              reached.add(focused.name);
            }
          }
          expect(reached.size).toBeGreaterThanOrEqual(controls);
        });
      }
    }
  }

  test('CKP_009_enter_opens_and_escape_closes_the_account_menu_returning_focus', async ({ page }) => {
    await open(page, 'ready', true);
    const avatar = page.locator('.avatar-btn');
    await avatar.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeHidden();
    await expect(avatar).toBeFocused();
  });
});

test.describe('CKP-010 above the fold in Vietnamese', () => {
  test('CKP_010_vietnamese_header_with_host_tenant_user_and_language_switch_is_inside_1440x900_and_stable_at_1440x1200', async ({ page }) => {
    await rememberLocale(page, 'vi');
    await page.setViewportSize({ width: 1440, height: 900 });
    await open(page, 'ready', true);
    const parts = [page.getByTestId('header-host'), page.getByTestId('header-tenant'), page.locator('.avatar-btn'), page.getByRole('button', { name: 'VI', exact: true })];
    const at900 = [];
    for (const part of parts) {
      const box = await part.boundingBox();
      expect(box).not.toBeNull();
      if (box) expect(box.y + box.height).toBeLessThanOrEqual(900);
      at900.push(box);
    }
    await page.setViewportSize({ width: 1440, height: 1200 });
    await page.reload();
    await expect(page.getByTestId('header-user')).toBeAttached();
    const at1200 = await Promise.all(parts.map((part) => part.boundingBox()));
    expect(at1200).toEqual(at900);
  });
});
