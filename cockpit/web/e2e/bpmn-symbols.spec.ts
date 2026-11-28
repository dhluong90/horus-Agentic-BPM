import { expect, test } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { SYMBOL_GALLERY, SYMBOL_GALLERY_LAYOUT } from '../src/mocks/symbolGallery';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * One of every BPMN symbol (#209): the gallery definition is drawn at a stored layout, and each marker is looked for by
 * its own attribute. Screenshots go to BPMN_SYMBOLS_SHOTS_DIR, or e2e/screenshots/ by default.
 */

const SHOTS = process.env['BPMN_SYMBOLS_SHOTS_DIR'] ?? 'e2e/screenshots';

for (const size of [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const) {
  test(`TR_UI_209_every_bpmn_symbol_is_drawn_with_its_marker_${String(size.height)}`, async ({ page }) => {
    await page.setViewportSize(size);
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    await rememberLocale(page, 'en');
    const processes = createFakeProcessEngine({ definitions: [SYMBOL_GALLERY], layouts: { 'symbol-gallery:1': SYMBOL_GALLERY_LAYOUT }, locale: 'en' });
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
    await page.goto('./#/processes/symbol-gallery/1');
    await expect(page.locator('svg[data-layout="stored"]')).toBeVisible();
    const svg = page.locator('svg[data-layout="stored"]');
    // No step is drawn as the dashed "other step" box.
    await expect(svg.locator('.dg-node--generic')).toHaveCount(0);
    // Call activity: thick border. Transaction: a second, inner border.
    await expect(svg.locator('[data-node="m-call"] > rect').first()).toHaveCSS('stroke-width', '3.5px');
    await expect(svg.locator('[data-node="p-transaction"] .dg-double')).toHaveCount(1);
    // Event gateways: a double ring waits inside a process, a single ring starts it; a plus waits for all.
    await expect(svg.locator('[data-node="g-event"] .dg-node__event-gateway circle')).toHaveCount(2);
    await expect(svg.locator('[data-node="g-event-start"] .dg-node__event-gateway circle')).toHaveCount(1);
    await expect(svg.locator('[data-node="g-event-all"] .dg-node__event-gateway')).toHaveAttribute('data-parallel', 'true');
    // Compensation marker on any activity; loop markers; the conditional diamond on a flow leaving an activity only.
    await expect(svg.locator('[data-node="m-undo-user"] [data-compensation]')).toHaveCount(1);
    await expect(svg.locator('[data-node="m-undo-loop"] [data-compensation]')).toHaveCount(1);
    await expect(svg.locator('[data-node="m-undo-loop"] [data-loop="loop"]')).toHaveCount(1);
    await expect(svg.locator('[data-edge="flow-if"] [data-condition]')).toHaveCount(1);
    await expect(svg.locator('[data-edge="flow-else"] [data-condition]')).toHaveCount(0);
    // Interrupting and non-interrupting boundaries and event sub-processes.
    await expect(svg.locator('[data-node="b-message"]')).toHaveAttribute('data-interrupting', 'true');
    await expect(svg.locator('[data-node="b-timer"]')).toHaveAttribute('data-interrupting', 'false');
    await expect(svg.locator('[data-node="e-none"] circle')).toHaveCount(1);
    await expect(svg.locator('[data-node="t-none"] circle')).toHaveCount(2);
    const name = `${SHOTS}/symbols-${String(size.width)}x${String(size.height)}`;
    await page.screenshot({ path: `${name}.png` });
    // At 100 % the symbols are drawn at full size: the frame is read in three bands.
    await page.getByRole('button', { name: '100 %' }).click();
    const frame = page.locator('.dg__frame');
    const height = await frame.evaluate((el) => el.scrollHeight - el.clientHeight);
    for (const [band, at] of [['top', 0], ['middle', height / 2], ['bottom', height]] as const) {
      await frame.evaluate((el, top) => { el.scrollTop = top; el.scrollLeft = 0; }, at);
      await page.screenshot({ path: `${name}-100-${band}.png` });
    }
  });
}
