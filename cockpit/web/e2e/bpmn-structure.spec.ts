import { expect, test } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { SYMBOL_STRUCTURE, SYMBOL_STRUCTURE_LAYOUT } from '../src/mocks/symbolStructure';
import { mockHost, rememberLocale } from './mockHost';

/*
 * Pool and lane bands, text annotations, groups and the receive task that starts the process (#209). Screenshots go to
 * BPMN_SYMBOLS_SHOTS_DIR, or e2e/screenshots/ by default.
 */

const SHOTS = process.env['BPMN_SYMBOLS_SHOTS_DIR'] ?? 'e2e/screenshots';

for (const size of [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const) {
  test(`TR_UI_209_pools_lanes_notes_and_groups_are_drawn_${String(size.height)}`, async ({ page }) => {
    await page.setViewportSize(size);
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    await rememberLocale(page, 'en');
    const processes = createFakeProcessEngine({ definitions: [SYMBOL_STRUCTURE], layouts: { 'symbol-structure:1': SYMBOL_STRUCTURE_LAYOUT }, locale: 'en' });
    const rules = createFakeEngine(demoSeed());
    await mockHost(page, 'ready');
    await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
    await page.route('**/api/bpm/me', (route) => route.fulfill({ json: meHoa }));
    await page.route('**/api/bpm/rules/v1/**', async (route) => {
      const r = route.request();
      const a = rules.handle({ method: r.method(), path: r.url(), headers: r.headers(), body: r.postData() ?? '', user: meHoa.user.id, permissions: meHoa.permissions });
      await route.fulfill({ status: a.status, headers: a.headers, body: a.body });
    });
    await page.route('**/api/bpm/processes/v1/**', async (route) => {
      const r = route.request();
      const a = processes.handle({ method: r.method(), path: r.url(), headers: r.headers(), body: r.postData() ?? '', user: meHoa.user.id, permissions: meHoa.permissions });
      await route.fulfill({ status: a.status, headers: a.headers, body: a.body });
    });
    await page.goto('./#/processes/symbol-structure/1');
    const svg = page.locator('svg[data-layout="stored"]');
    await expect(svg).toBeVisible();
    await expect(svg.locator('.dg-node--generic')).toHaveCount(0);
    // Pool and lanes: a vertical name band each; the child lanes are inside Sales.
    await expect(svg.locator('.dg-pool__band')).toHaveCount(1);
    await expect(svg.locator('.dg-lane')).toHaveCount(4);
    await expect(svg.locator('[data-lane="lane-front"]')).toHaveAttribute('data-depth', '1');
    const sales = await svg.locator('[data-lane="lane-sales"] rect').first().boundingBox();
    const front = await svg.locator('[data-lane="lane-front"] rect').first().boundingBox();
    if (!sales || !front) throw new Error('lanes are not drawn');
    expect(front.x).toBeGreaterThan(sales.x);
    expect(front.y + front.height).toBeLessThanOrEqual(sales.y + sales.height + 1);
    await expect(svg.locator('.dg-lane__name').first()).toHaveAttribute('transform', /rotate\(-90/);
    // Notes with their lines, the group with its name, the receive task with the start marker.
    await expect(svg.locator('[data-note]')).toHaveCount(2);
    await expect(svg.locator('[data-note-link]')).toHaveCount(2);
    await expect(svg.locator('[data-group="group-fraud"]')).toHaveAttribute('aria-label', 'Group: Fraud screening');
    await expect(svg.locator('[data-node="receive-order"]')).toHaveAttribute('data-type', 'receiveTask:instantiate');
    await expect(svg.locator('[data-node="receive-order"]')).toHaveAttribute('aria-label', /starts the process/);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: `${SHOTS}/structure-${String(size.width)}x${String(size.height)}-v4.png` });
  });
}
