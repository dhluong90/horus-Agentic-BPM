import { expect, test, type Locator, type Page } from '@playwright/test';
import { UNKNOWN_MODULE } from '../src/mocks/fixtures';
import { mockHost, mockRuleEngine } from './mockHost';

/** Tolerant reading of /api/bpm/meta (cockpit DESIGN 5.2): a module this cockpit does not know is skipped. */

async function expectAboveTheFold(page: Page, locator: Locator) {
  const viewport = page.viewportSize() ?? { width: 0, height: 0 };
  const rect = await locator.boundingBox();
  expect(rect, 'element is rendered').not.toBeNull();
  if (rect === null) return;
  expect(rect.y).toBeGreaterThanOrEqual(0);
  expect(rect.y + rect.height).toBeLessThanOrEqual(viewport.height);
}

/** Console warnings about /meta, collected from the first navigation on. */
function metaNotes(page: Page): string[] {
  const notes: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'warning' && message.text().includes('/api/bpm/meta')) notes.push(message.text());
  });
  return notes;
}

test.describe('tolerant reading of /meta modules', () => {
  for (const height of [900, 1200]) {
    test(`CKP_001_meta_with_an_unknown_module_shows_rules_and_processes_and_notes_it_once_at_1440x${height}`, async ({ page }) => {
      const notes = metaNotes(page);
      await page.setViewportSize({ width: 1440, height });
      await mockHost(page, 'meta-unknown-module');
      await mockRuleEngine(page, 'meta-unknown-module');
      await page.goto('./#/service');

      const rail = page.getByTestId('nav-rail');
      await expect(page.getByTestId('session-header').getByTestId('header-user')).toHaveText('Nguyễn Thị Lan');
      await expect(page.getByRole('alert')).toHaveCount(0);
      const rules = rail.getByRole('link', { name: /^Rules/ });
      const processes = rail.getByRole('link', { name: /^Processes/ });
      await expect(rules).toBeVisible();
      await expect(processes).toBeVisible();
      await expectAboveTheFold(page, rules);
      await expectAboveTheFold(page, processes);
      // Nothing is shown for the skipped module.
      await expect(rail.getByRole('link', { name: /cases/i })).toHaveCount(0);
      await page.screenshot({ path: `e2e/screenshots/meta-unknown-module-1440x${height}-light-en.png` });

      expect(notes).toHaveLength(1);
      expect(notes[0]).toContain(`"${UNKNOWN_MODULE}"`);
    });
  }

  test('CKP_001_meta_with_a_malformed_known_module_still_shows_the_unexpected_answer_error', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page, 'meta-bad-known-module');
    await page.goto('./#/service');

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('The service sent an unexpected answer');
    const retry = page.getByRole('button', { name: 'Try again' });
    await expect(retry).toBeVisible();
    await expectAboveTheFold(page, retry);
    await page.screenshot({ path: 'e2e/screenshots/meta-bad-known-module-1440x900-light-en.png' });
  });
});
