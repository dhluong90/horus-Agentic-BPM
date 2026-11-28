/* global document, window, getComputedStyle */
// The built documentation site at phone and tablet width (#629): 375, 390 and 768 px. Serves site/.generated/.vitepress/dist and fails when
//  - a page scrolls sideways, or has an element outside the viewport that no scrolling frame holds,
//  - a link to a heading leaves the heading under the page's fixed bar (Menu, On this page),
//  - the zoom control of a screenshot covers the picture instead of sitting in its own strip below it.
// SITE_SHOTS_DIR, when set, receives a screenshot of the home page and of an anchored heading at each width.
import { createServer } from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const root = fileURLToPath(new URL('../../../site/.generated/.vitepress/dist', import.meta.url));
const base = (process.env.SITE_BASE || '/horus-Agentic-BPM/').replace(/\/?$/, '/');
const shots = process.env.SITE_SHOTS_DIR;
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

const pages = (await readdir(root)).filter((f) => f.endsWith('.html') && f !== '404.html').map((f) => (f === 'index.html' ? '' : f.replace(/\.html$/, '')));
pages.push('docs/');
const failures = [];
const underBar = (page, id) => page.evaluate((hash) => {
  const heading = document.getElementById(hash);
  const bar = document.querySelector('.VPLocalNav');
  if (!heading || !bar || getComputedStyle(bar).display === 'none' || !['fixed', 'sticky'].includes(getComputedStyle(bar).position)) return false;
  return heading.getBoundingClientRect().top < bar.getBoundingClientRect().bottom;
}, id);
let runs = 0;
const browser = await chromium.launch();
for (const width of [375, 390, 768]) {
  const context = await browser.newContext({ viewport: { width, height: 800 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  for (const path of pages) {
    const page = await context.newPage();
    await page.goto(origin + base + path, { waitUntil: 'load' });
    runs += 1;
    const label = `${width}px ${base}${path}`;
    const found = await page.evaluate(() => {
      const outside = [];
      for (const element of document.querySelectorAll('main *, .VPDoc *')) {
        const box = element.getBoundingClientRect();
        if (box.width === 0 || getComputedStyle(element).position === 'fixed') continue;
        let held = false;
        for (let up = element.parentElement; up; up = up.parentElement) {
          const overflow = getComputedStyle(up).overflowX;
          if (overflow === 'auto' || overflow === 'scroll' || overflow === 'hidden') { held = true; break; }
        }
        if (!held && box.right > window.innerWidth + 1) outside.push(`${element.tagName.toLowerCase()}.${String(element.className).slice(0, 30)}`);
      }
      // The zoom control is a round button drawn at the foot of its frame; it must start below the picture.
      const covering = [...document.querySelectorAll('button.zoom')].filter((button) => {
        const after = getComputedStyle(button, '::after');
        const picture = button.querySelector('img');
        if (!picture) return false;
        const top = button.getBoundingClientRect().bottom - parseFloat(after.bottom) - parseFloat(after.height);
        return top < picture.getBoundingClientRect().bottom - 1;
      }).length;
      return { wide: document.documentElement.scrollWidth > window.innerWidth, outside: outside.slice(0, 3), covering };
    });
    if (found.wide) failures.push(`${label}: the page scrolls sideways`);
    if (found.outside.length > 0) failures.push(`${label}: outside the viewport and not in a scrolling frame: ${found.outside.join(', ')}`);
    if (found.covering > 0) failures.push(`${label}: ${found.covering} zoom control(s) cover the picture`);
    if (shots && path === '') await page.screenshot({ path: join(shots, `site-${width}-home.png`) });
    await page.close();
  }

  // A heading opened by its address stops below the page's fixed bar: the first six headings of a long page, each opened as a visitor's browser opens an address.
  const first = await context.newPage();
  await first.goto(`${origin}${base}Getting-Started`, { waitUntil: 'load' });
  const hashes = await first.evaluate(() => [...document.querySelectorAll('.vp-doc h2[id]')].slice(0, 6).map((heading) => heading.id));
  await first.close();
  if (hashes.length < 6) failures.push(`${width}px Getting-Started has fewer than six headings to open`);
  for (const hash of hashes) {
    // Opened by its address, and followed from a link inside the page after the reader has scrolled.
    const page = await context.newPage();
    await page.goto(`${origin}${base}Getting-Started#${hash}`, { waitUntil: 'load' });
    await page.waitForTimeout(800);
    const linked = await context.newPage();
    await linked.goto(`${origin}${base}Getting-Started`, { waitUntil: 'networkidle' });
    await linked.evaluate(() => window.scrollTo(0, 2500));
    await linked.waitForTimeout(300);
    await linked.goto(`${origin}${base}Getting-Started#${hash}`, { waitUntil: 'networkidle' });
    await linked.waitForTimeout(1500);
    if (await underBar(linked, hash)) failures.push(`${width}px Getting-Started#${hash} followed from a link: the heading is under the fixed bar`);
    runs += 1;
    await linked.close();
    const hidden = await underBar(page, hash);
    runs += 1;
    if (hidden) failures.push(`${width}px Getting-Started#${hash}: the heading is under the fixed bar`);
    if (shots && hash === hashes[4]) await page.screenshot({ path: join(shots, `site-${width}-anchor.png`) });
    await page.close();
  }
  await context.close();
}
await browser.close();
server.close();

if (failures.length > 0) {
  console.error(`${failures.length} problem(s) in ${runs} checks:\n${failures.join('\n')}`);
  process.exit(1);
}
console.log(`Site at phone and tablet width: ${runs} checks, no problem.`);
