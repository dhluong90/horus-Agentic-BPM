import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { BOOKED_INSTANCE_ID, INCIDENT_INSTANCE_ID, KYC_INSTANCE_ID } from '../src/mocks/caseDemo';
import { DEMO_DEFINITIONS, type DemoDefinition } from '../src/mocks/processDemo';
import { createFakeProcessEngine, type ProcessSeed } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * "Fit" shows the whole model (CKP-069, CKP-017): in Fit mode no element of the drawing, whether a step, a caption,
 * a flow label, a data symbol, a boundary event, a pool, a lane or a badge, lies outside the visible frame, and the
 * frame does not scroll. Checked on the case page and the definition page, on small and large models, at both
 * laptop sizes, in both languages. Screenshots go to the directory in FIT_SHOTS when it is set.
 */

const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const EXAMPLES = resolve(process.cwd(), '../../examples/rule-and-process/src/main/resources/processes');

function large(key: string, name: string, file: string): DemoDefinition {
  return { key, name, version: 1, status: 'ACTIVE', effectiveVersion: 1, nodeCount: 20, lastChangedAt: '2026-11-18T09:20:00Z', bpmn: readFileSync(resolve(EXAMPLES, file), 'utf8') };
}
const LARGE = [
  large('loan-payout', 'Loan payout', 'loan-payout.bpmn'),
  large('order-payment-pools', 'Order payment (collaboration)', 'order-payment-pools.bpmn'),
  large('data-objects', 'Data objects', 'conformance/data-objects.bpmn'),
  large('data-store', 'Data store', 'conformance/data-store.bpmn'),
];

async function open(page: Page, hash: string, viewport: { width: number; height: number }, locale: 'en' | 'vi', seed: ProcessSeed = {}) {
  const processes = createFakeProcessEngine({ ...seed, locale });
  const rules = createFakeEngine(demoSeed());
  await page.setViewportSize(viewport);
  await rememberLocale(page, locale);
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
  await page.goto(`./${hash}`);
  await expect(page.locator('[data-layout="auto"]')).toBeVisible();
}

/** Every drawn element's box against the frame's visible box, and whether the frame scrolls. */
async function fitFacts(page: Page) {
  return page.locator('[data-layout="auto"]').evaluate((svg) => {
    const frame = svg.closest('.dg__frame') as HTMLElement;
    const view = frame.getBoundingClientRect();
    const selector = '[data-node], [data-edge], [data-data], [data-data-link], [data-message-flow], .dg-pool, .dg-lane, .dg-edge__label, .dg-badge, text';
    const outside: string[] = [];
    let count = 0;
    for (const element of Array.from(svg.querySelectorAll(selector))) {
      const box = element.getBoundingClientRect();
      if (box.width === 0 && box.height === 0) continue;
      count += 1;
      if (box.left < view.left - 1 || box.right > view.right + 1 || box.top < view.top - 1 || box.bottom > view.bottom + 1) {
        outside.push(`${element.getAttribute('data-node') ?? element.getAttribute('data-data') ?? element.getAttribute('data-edge') ?? String(element.getAttribute('class'))} ${[box.left - view.left, view.right - box.right, box.top - view.top, view.bottom - box.bottom].map(Math.round).join('/')}`);
      }
    }
    return {
      count, outside,
      scrollsX: frame.scrollWidth > frame.clientWidth + 1, scrollsY: frame.scrollHeight > frame.clientHeight + 1,
      overflow: getComputedStyle(frame).overflow, bottom: view.bottom, pressed: document.querySelector('.dg__tools [aria-pressed="true"]')?.textContent,
    };
  });
}

async function expectWholeModel(page: Page, viewportHeight: number) {
  await expect(async () => {
    const facts = await fitFacts(page);
    expect(facts.pressed).toMatch(/Fit|Vừa/);
    expect(facts.count).toBeGreaterThan(3);
    expect(facts.outside).toEqual([]);
    expect(facts.scrollsX).toBe(false);
    expect(facts.scrollsY).toBe(false);
    expect(facts.overflow).toBe('hidden');
    expect(facts.bottom).toBeLessThanOrEqual(viewportHeight);
  }).toPass({ timeout: 5_000 });
}

async function shot(page: Page, name: string) {
  const dir = process.env['FIT_SHOTS'];
  if (dir !== undefined) await page.screenshot({ path: `${dir}/${name}.png` });
}

for (const viewport of VIEWPORTS) {
  const size = `${String(viewport.width)}x${String(viewport.height)}`;
  for (const locale of ['en', 'vi'] as const) {
    for (const [name, id] of [['kyc', KYC_INSTANCE_ID], ['incident', INCIDENT_INSTANCE_ID], ['booked', BOOKED_INSTANCE_ID]] as const) {
      test(`CKP_069_fit_shows_the_whole_case_${name}_${size}_${locale}`, async ({ page }) => {
        await open(page, `#/instances/${id}`, viewport, locale);
        await expectWholeModel(page, viewport.height);
        await shot(page, `case-${name}-${size}-${locale}`);
      });
    }
    for (const hash of ['#/processes/loan-origination/3', '#/processes/kyc-onboarding/2']) {
      test(`CKP_069_fit_shows_the_whole_definition_${hash.split('/')[2] ?? ''}_${size}_${locale}`, async ({ page }) => {
        await open(page, hash, viewport, locale);
        await expectWholeModel(page, viewport.height);
        await shot(page, `definition-${hash.split('/')[2] ?? ''}-${size}-${locale}`);
      });
    }
    for (const model of LARGE) {
      test(`CKP_069_fit_shows_the_whole_large_model_${model.key}_${size}_${locale}`, async ({ page }) => {
        await open(page, `#/processes/${model.key}/1`, viewport, locale, { definitions: [...DEMO_DEFINITIONS, model] });
        await expectWholeModel(page, viewport.height);
        await shot(page, `large-${model.key}-${size}-${locale}`);
      });
    }
  }
}

test('CKP_069_fit_is_refitted_when_the_window_resizes_and_100_percent_pans', async ({ page }) => {
  await open(page, `#/instances/${KYC_INSTANCE_ID}`, VIEWPORTS[0], 'en');
  await expectWholeModel(page, VIEWPORTS[0].height);
  await page.setViewportSize({ width: 1180, height: 800 });
  await expectWholeModel(page, 800);
  await page.getByRole('button', { name: '100 %' }).click();
  await expect(page.locator('.dg__frame')).not.toHaveCSS('overflow-x', 'hidden');
  await page.getByRole('button', { name: 'Fit' }).click();
  await expectWholeModel(page, 800);
});

test('CKP_069_fit_never_scales_a_small_model_past_100_percent_and_shows_the_scale', async ({ page }) => {
  for (const viewport of VIEWPORTS) {
    await open(page, '#/processes/kyc-onboarding/2', viewport, 'en');
    const facts = await page.locator('[data-layout="auto"]').evaluate((svg) => {
      const box = svg.getBoundingClientRect();
      const view = svg.getAttribute('viewBox')?.split(' ').map(Number) ?? [0, 0, 0, 0];
      return { scale: Number(svg.getAttribute('data-scale')), drawnWidth: box.width, width: view[2] ?? 0 };
    });
    expect(facts.scale).toBeLessThanOrEqual(1);
    expect(facts.drawnWidth).toBeLessThanOrEqual(facts.width + 1);
    const pct = Math.round(facts.scale * 100);
    await expect(page.getByRole('button', { name: `Fit · ${String(pct)} %` })).toHaveAttribute('aria-pressed', 'true');
  }
});
