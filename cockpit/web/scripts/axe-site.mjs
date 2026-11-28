/* global document, window */
// Accessibility check of the built documentation site (CKP-065 "Readable"). Serves site/.generated/.vitepress/dist,
// opens a page with a diagram and code blocks at 1440x900 and 1440x1200 in light and dark, and fails when the
// Mermaid diagram is not drawn, a code block has no copy button, axe-core reports a serious or critical violation, or the
// homepage or the docs home has a chip (a rounded box with a fill or a border around short text; scripts/chip-detector.mjs).
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { CHIP_EXEMPT, detectChips } from './chip-detector.mjs';

const root = fileURLToPath(new URL('../../../site/.generated/.vitepress/dist', import.meta.url));
// The site is built for a sub-path (SITE_BASE, default the project page); the server below serves it under that path.
const base = (process.env.SITE_BASE || '/horus-Agentic-BPM/').replace(/\/?$/, '/');
// The first two pages hold a Mermaid diagram and code blocks; the homepage and the docs home are checked by axe and for foreign requests.
const pages = [
  { path: 'Getting-Started', diagram: true },
  { path: 'Agent-Shadow-Mode', diagram: true },
  { path: '', diagram: false },
  { path: 'docs/', diagram: false }
];
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp', '.mp4': 'video/mp4', '.webm': 'video/webm', '.json': 'application/json', '.woff2': 'font/woff2', '.txt': 'text/plain' };

const server = createServer(async (req, res) => {
  const full = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^(\.\.[/\\])+/, '');
  if (!full.startsWith(base)) { res.writeHead(404).end(); return; }
  const path = `/${full.slice(base.length)}`;
  for (const file of [path, `${path}.html`, join(path, 'index.html')]) {
    try {
      const body = await readFile(join(root, file));
      res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' }).end(body);
      return;
    } catch { /* try the next candidate */ }
  }
  res.writeHead(404).end();
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
const failures = [];
let runs = 0;
for (const scheme of ['light', 'dark']) {
  for (const height of [900, 1200]) {
    const context = await browser.newContext({ viewport: { width: 1440, height }, colorScheme: scheme });
    for (const { path: pagePath, diagram } of pages) {
      const path = base + pagePath;
      const page = await context.newPage();
      const foreign = [];
      page.on('request', (r) => { if (!r.url().startsWith(origin)) foreign.push(r.url()); });
      await page.goto(origin + path, { waitUntil: 'networkidle' });
      const label = `${path} 1440x${height} ${scheme}`;
      runs += 1;
      if (diagram) {
        try {
          await page.waitForSelector('.mermaid svg', { timeout: 10_000 });
        } catch { failures.push(`${label}: no Mermaid diagram was drawn`); }
        if ((await page.locator('div[class*="language-"] button.copy').count()) === 0) failures.push(`${label}: no copy button on code blocks`);
      } else if ((await page.locator('.hx h1').count()) !== 1) failures.push(`${label}: the page has no single h1`);
      if ((await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)) > 0) failures.push(`${label}: horizontal scroll`);
      if (foreign.length > 0) failures.push(`${label}: request to another origin: ${foreign[0]}`);
      const { violations } = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
      for (const v of violations.filter((x) => x.impact === 'serious' || x.impact === 'critical')) {
        failures.push(`${label}: axe ${v.impact} ${v.id} on ${v.nodes.length} node(s), e.g. ${v.nodes[0].target.join(' ')}`);
      }
      if (!diagram) {
        // The homepage and the docs home are written by hand: no rounded, filled or bordered box around short text ("No chips").
        for (const chip of await page.evaluate(detectChips, CHIP_EXEMPT)) failures.push(`${label}: chip ${chip.element} "${chip.text}"`);
      }
      await page.close();
    }
    await context.close();
  }
}
await browser.close();
server.close();
if (failures.length > 0) {
  console.error(`${failures.length} problem(s):\n${failures.join('\n')}`);
  process.exit(1);
}
console.log(`${runs} page views (${pages.length} pages, 1440x900 and 1440x1200, light and dark): diagrams drawn, copy buttons present, no serious axe violation, no chip on the homepage and docs home`);
