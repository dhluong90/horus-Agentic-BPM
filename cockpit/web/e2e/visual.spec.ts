import { expect, test } from '@playwright/test';
import type { Scenario } from '../src/mocks/fixtures';
import { mockHost, rememberLocale } from './mockHost';

// Screenshots for the visual check, written to e2e/screenshots/ (attached to the pull request).
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
const STATES: ReadonlyArray<{ name: string; scenario: Scenario }> = [
  { name: 'ready', scenario: 'ready' },
  { name: 'me-error', scenario: 'me-forbidden' },
];

for (const { name, scenario } of STATES) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `shell-${name}-${viewport.width}x${viewport.height}-${theme}-${locale}.png`;
        test(`CKP_001_visual_${name}_${viewport.width}x${viewport.height}_${theme}_${locale}`, async ({ page }) => {
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockHost(page, scenario);
          await page.goto('./#/service');
          await expect(name === 'ready' ? page.getByTestId('header-user') : page.getByRole('alert')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await expect(page.locator('html')).toHaveAttribute('lang', locale);
          const header = await page.getByTestId('session-header').boundingBox();
          expect(header && header.y + header.height <= viewport.height).toBe(true);
          await page.evaluate(async () => {
            await document.fonts.ready;
          });
          await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
        });
      }
    }
  }
}
