import { expect, test, type Page } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * A definition drawn at the coordinates of its own BPMN diagram (#209), and one with no diagram laid out by the
 * cockpit as before. The host engine is the fake one; only the layout it serves for loan-origination v3 differs.
 * Screenshots go to BPMN_DI_SHOTS_DIR, or e2e/screenshots/ by default.
 */

const SHOTS = process.env['BPMN_DI_SHOTS_DIR'] ?? 'e2e/screenshots';
const box = (x: number, y: number, width: number, height: number) => ({ x, y, width, height });
const event = (x: number, y: number) => box(x, y, 36, 36);
const task = (x: number, y: number) => box(x, y, 116, 80);

/** The loan process drawn in two rows, as a modeller would: the happy path along the top, the declines below. */
const LOAN_DI = {
  schemaVersion: 1,
  nodes: {
    received: event(40, 112), capture: task(130, 94), 'assess-documents': task(300, 94), 'credit-decision': task(470, 94),
    approved: box(640, 105, 50, 50), 'book-loan': task(740, 94), 'compute-offer': task(910, 94), 'send-offer': task(1080, 94), booked: event(1250, 112),
    'reverse-booking': task(740, 250), 'send-decline': task(610, 330), declined: event(780, 348),
    remind: task(300, 330), reminded: event(470, 348),
  },
  edges: { 'approved->send-decline': [{ x: 665, y: 155 }, { x: 665, y: 330 }] },
};

async function open(page: Page, layouts: Record<string, unknown>, size: { width: number; height: number }, locale: 'en' | 'vi' = 'en') {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  const processes = createFakeProcessEngine({ locale, layouts });
  const rules = createFakeEngine(demoSeed());
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: meHoa }));
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = rules.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meHoa.user.id, permissions: meHoa.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = processes.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meHoa.user.id, permissions: meHoa.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto('./#/processes/loan-origination/3');
  await expect(page.locator('.skeleton')).toHaveCount(0);
}

const centre = async (page: Page, id: string) => {
  const rect = await page.locator(`svg [data-node="${id}"]`).first().boundingBox();
  if (!rect) throw new Error(`${id} is not drawn`);
  return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
};

for (const size of [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const) {
  test(`TR_UI_209_a_definition_with_a_diagram_is_drawn_at_its_coordinates_${String(size.height)}`, async ({ page }) => {
    await open(page, { 'loan-origination:3': LOAN_DI }, size);
    await expect(page.locator('svg[data-layout="stored"]')).toBeVisible();
    await expect(page.getByText('Version 3, drawn as in its diagram')).toBeVisible();
    // Two rows: the declines sit below the happy path, which automatic left-to-right layout would not do for the end.
    const approved = await centre(page, 'approved');
    const decline = await centre(page, 'send-decline');
    const booked = await centre(page, 'booked');
    expect(decline.y).toBeGreaterThan(approved.y + 80);
    expect(Math.abs(booked.y - approved.y)).toBeLessThan(10);
    expect(booked.x).toBeGreaterThan(approved.x);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    // A flow name never sits on a step or on another name, and the flows from Capture application and its reminder timer start on different lines.
    const rects = await page.locator('svg [data-edge] .dg-edge__label rect').evaluateAll((els) => els.map((el) => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; }));
    const steps = await page.locator('svg [data-node]').evaluateAll((els) => els.filter((el) => el.getAttribute('data-type') !== 'subProcess').map((el) => { const r = (el.querySelector('rect, circle, path') ?? el).getBoundingClientRect(); return { id: el.getAttribute('data-node') ?? '', x: r.x, y: r.y, w: r.width, h: r.height }; }));
    const hit = (a: { x: number; y: number; w: number; h: number }, b: { x: number; y: number; w: number; h: number }) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

    expect(rects.length).toBeGreaterThan(0);
    for (const [i, rect] of rects.entries()) {
      for (const step of steps) expect(hit(rect, step), `a flow name meets ${step.id}`).toBe(false);
      for (const other of rects.slice(i + 1)) expect(hit(rect, other)).toBe(false);
    }
    const startOf = async (id: string) => page.locator(`svg [data-edge="${id}"] path`).first().getAttribute('d');
    const toAssess = await startOf('f2');
    const toRemind = await startOf('f8');
    expect(toAssess).not.toBeNull();
    expect(toRemind).not.toBeNull();
    const leg = (d: string | null) => (d ?? '').split(/[ML]/).filter(Boolean).slice(0, 2).join('|');
    expect(leg(toAssess)).not.toBe(leg(toRemind));
    await page.screenshot({ path: `${SHOTS}/di-stored-${String(size.width)}x${String(size.height)}.png` });
  });
}

test('TR_UI_209_a_definition_with_no_diagram_is_laid_out_automatically', async ({ page }) => {
  await open(page, {}, { width: 1440, height: 900 });
  await expect(page.locator('svg[data-layout="auto"]')).toBeVisible();
  await expect(page.locator('svg[data-layout="stored"]')).toHaveCount(0);
  await expect(page.getByText('Version 3, laid out automatically')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/di-auto-1440x900.png` });
});
