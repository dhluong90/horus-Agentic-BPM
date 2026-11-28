import { readdirSync, readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { JSDOM } from 'jsdom';
import { expect, test, type Page } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import type { DemoDefinition } from '../src/mocks/processDemo';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * Every reference model of the BPMN MIWG suite (#209), imported as a definition with the model's own diagram as its
 * stored layout and drawn by the cockpit. Each model must draw every shape its diagram lists, with no step falling back
 * to the dashed "other step" box, and no flow name or step caption may sit on a flow line.
 *
 * The suite is build-time data and is never committed: `tools/conformance/fetch.sh miwg` unpacks the pinned copy to
 * target/conformance/suites/bpmn-miwg. MIWG_DIR points at another copy. Without a copy the checks skip on a
 * developer machine and fail in CI, so a missing download cannot pass as green.
 */

const SUITE = resolve(process.env['MIWG_DIR'] ?? '../../target/conformance/suites/bpmn-miwg', 'Reference');
/**
 * What the cockpit does not yet draw or place cleanly in each model, by file name: a ratchet. A model's gaps must equal this
 * list, so a new gap fails and a fixed one fails until it is removed here. MIWG_UPDATE_GAPS=1 with --workers=1 rewrites the
 * file from the current run (see cockpit/docs/TEST-PLAN.md, "BPMN MIWG render spec").
 */
const GAPS_FILE = resolve(import.meta.dirname, 'miwg-known-gaps.json');
const KNOWN_GAPS = new Map<string, string[]>(Object.entries(JSON.parse(readFileSync(GAPS_FILE, 'utf8')) as Record<string, string[]>));
const update = process.env['MIWG_UPDATE_GAPS'] === '1';
const SHOTS = process.env['MIWG_SHOTS_DIR'] ?? 'e2e/screenshots';
const available = existsSync(SUITE);
const MODELS = available ? readdirSync(SUITE).filter((file) => file.endsWith('.bpmn')).sort() : [];

const NS = { bpmndi: 'http://www.omg.org/spec/BPMN/20100524/DI', dc: 'http://www.omg.org/spec/DD/20100524/DC', di: 'http://www.omg.org/spec/DD/20100524/DI' };

type Box = { x: number; y: number; width: number; height: number };
interface Model { bpmn: string; layout: { schemaVersion: 1; nodes: Record<string, Box>; edges: Record<string, { x: number; y: number }[]>; labels: Record<string, Box> }; shapes: { id: string; label: string }[] }

/** The stored layout the engine keeps for an imported file (PRE-215): shape bounds, flow waypoints and flow label bounds. */
function readModel(xml: string): Model {
  const doc = new JSDOM(xml, { contentType: 'text/xml' }).window.document;
  const sources = new Map<string, string>();
  for (const flow of [...doc.getElementsByTagNameNS('*', 'sequenceFlow')]) sources.set(flow.getAttribute('id') ?? '', `${flow.getAttribute('sourceRef') ?? ''}->${flow.getAttribute('targetRef') ?? ''}`);
  const nodes: Model['layout']['nodes'] = {};
  const edges: Model['layout']['edges'] = {};
  const labels: Model['layout']['labels'] = {};
  const shapes: string[] = [];
  for (const shape of [...doc.getElementsByTagNameNS(NS.bpmndi, 'BPMNShape')]) {
    const id = shape.getAttribute('bpmnElement') ?? '';
    const bounds = shape.getElementsByTagNameNS(NS.dc, 'Bounds')[0];
    if (id === '' || bounds === undefined) continue;
    nodes[id] = { x: Number(bounds.getAttribute('x')), y: Number(bounds.getAttribute('y')), width: Number(bounds.getAttribute('width')), height: Number(bounds.getAttribute('height')) };
    shapes.push(id);
  }
  for (const edge of [...doc.getElementsByTagNameNS(NS.bpmndi, 'BPMNEdge')]) {
    const key = sources.get(edge.getAttribute('bpmnElement') ?? '');
    if (key === undefined) continue;
    edges[key] = [...edge.getElementsByTagNameNS(NS.di, 'waypoint')].map((p) => ({ x: Number(p.getAttribute('x')), y: Number(p.getAttribute('y')) }));
    const label = edge.getElementsByTagNameNS(NS.bpmndi, 'BPMNLabel')[0]?.getElementsByTagNameNS(NS.dc, 'Bounds')[0];
    if (label !== undefined) labels[key] = { x: Number(label.getAttribute('x')), y: Number(label.getAttribute('y')), width: Number(label.getAttribute('width')), height: Number(label.getAttribute('height')) };
  }
  // The cockpit draws one process of a file, the first executable one or else the first; the shapes of the others are not owed.
  const processes = [...doc.getElementsByTagNameNS('*', 'process')];
  const drawn = processes.find((p) => p.getAttribute('isExecutable') === 'true') ?? processes[0];
  const element = (id: string) => [...doc.querySelectorAll('[id]')].find((e) => e.getAttribute('id') === id);
  const label = (id: string) => `${element(id)?.localName ?? 'unknown'} ${id}`;
  const others = new Set(processes.filter((p) => p !== drawn).flatMap((p) => [p.getAttribute('id') ?? '', ...[...p.querySelectorAll('[id]')].map((e) => e.getAttribute('id') ?? '')]));
  return { bpmn: xml, layout: { schemaVersion: 1, nodes, edges, labels }, shapes: shapes.filter((id) => !others.has(id)).map((id) => ({ id, label: label(id) })) };
}

async function open(page: Page, key: string, model: Model, size: { width: number; height: number }) {
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await rememberLocale(page, 'en');
  const definition: DemoDefinition = { key, name: key, version: 1, status: 'ACTIVE', effectiveVersion: 1, nodeCount: model.shapes.length, lastChangedAt: '2026-11-28T09:00:00Z', bpmn: model.bpmn };
  const processes = createFakeProcessEngine({ definitions: [definition], layouts: { [`${key}:1`]: model.layout }, locale: 'en' });
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
  await page.goto(`./#/processes/${key}/1`);
}

/** Flow names and step captions that a line of the drawing runs through, in the diagram's own coordinates. */
async function labelsOnLines(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const svg = document.querySelector('svg[data-layout]');
    if (svg === null) return ['no drawing'];
    const lines = [...svg.querySelectorAll<SVGPathElement>('[data-edge] > path:first-of-type, [data-message-flow] > path:first-of-type')].map((path) => {
      const total = path.getTotalLength();
      const points: { x: number; y: number }[] = [];
      for (let at = 0; at <= total; at += 2) points.push(path.getPointAtLength(at));
      points.push(path.getPointAtLength(total));
      return { id: path.parentElement?.getAttribute('data-edge') ?? path.parentElement?.getAttribute('data-message-flow') ?? '', points };
    });
    const texts = [...svg.querySelectorAll<SVGTextElement>('.dg-edge__label text, .dg-node__caption')].filter((text) => text.textContent.trim() !== '');
    const hits: string[] = [];
    for (const text of texts) {
      const b = text.getBBox();
      // The box is shrunk a little: a line may graze the padding of a label, never its letters.
      const inner = { left: b.x + 2, right: b.x + b.width - 2, top: b.y + 2, bottom: b.y + b.height - 2 };
      for (const line of lines) {
        if (line.points.some((p) => p.x > inner.left && p.x < inner.right && p.y > inner.top && p.y < inner.bottom)) {
          hits.push(`"${text.textContent}" on ${line.id}`);
          break;
        }
      }
    }
    return hits;
  });
}

test.describe('TR_UI_209_miwg_reference_models_render', () => {
  test('TR_UI_209_the_miwg_suite_is_present', () => {
    if (!available) {
      if (process.env['CI'] !== undefined) throw new Error(`The BPMN MIWG suite is not at ${SUITE}: run tools/conformance/fetch.sh miwg`);
      test.skip(true, `The BPMN MIWG suite is not at ${SUITE}: run tools/conformance/fetch.sh miwg`);
    }
    expect(MODELS.length).toBeGreaterThan(0);
  });

  for (const file of MODELS) {
    for (const size of [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const) {
      test(`TR_UI_209_miwg_${file.replace(/\.bpmn$/, '').replaceAll('.', '_')}_draws_every_shape_${String(size.height)}`, async ({ page }) => {
        const model = readModel(readFileSync(join(SUITE, file), 'utf8'));
        const key = `miwg-${file.replace(/\.bpmn$/, '').replaceAll('.', '-').toLowerCase()}`;
        await open(page, key, model, size);
        await expect(page.locator('.skeleton')).toHaveCount(0);
        const svg = page.locator('svg[data-layout]');
        await expect(svg).toBeVisible();

        // A sub-process is drawn closed with a plus until it is opened: open every one, so the steps inside are drawn too.
        const closed = svg.locator('.dg-toggle[aria-expanded="false"]');
        for (let opened = 0; (await closed.count()) > 0 && opened < 60; opened += 1) await closed.first().click();
        await expect(closed).toHaveCount(0);
        await page.screenshot({ path: `${SHOTS}/miwg-${file.replace(/\.bpmn$/, '')}-${String(size.width)}x${String(size.height)}.png` });

        // No step falls back to the dashed "other step" box.
        await expect(svg.locator('.dg-node--generic')).toHaveCount(0);

        // Every shape the model's own diagram lists is drawn: a step, a pool or lane, a data item, a note or a group.
        const drawn = await page.evaluate(() => {
          const attrs = ['data-node', 'data-pool', 'data-lane', 'data-data', 'data-note', 'data-group'];
          return [...document.querySelectorAll('svg[data-layout] *')].flatMap((element) => attrs.map((a) => element.getAttribute(a))).filter((v): v is string => v !== null);
        });
        const drawnIds = new Set(drawn);
        const missing = model.shapes.filter(({ id }) => !drawnIds.has(id)).map(({ label }) => `not drawn: ${label}`);
        const overlaps = (await labelsOnLines(page)).map((hit) => `label on a line: ${hit.replace(/ on [^ ]+$/, '').replace(/\s+/g, ' ')}`);
        const gaps = [...missing, ...overlaps].sort();
        if (update) {
          if (size.height === 900) {
            if (gaps.length > 0) KNOWN_GAPS.set(file, gaps);
            else KNOWN_GAPS.delete(file);
            writeFileSync(GAPS_FILE, `${JSON.stringify(Object.fromEntries([...KNOWN_GAPS].sort(([a], [b]) => a.localeCompare(b, 'en', { numeric: true }))), null, 2)}\n`);
          }
          return;
        }
        expect(gaps).toEqual([...(KNOWN_GAPS.get(file) ?? [])].sort());
      });
    }
  }
});
