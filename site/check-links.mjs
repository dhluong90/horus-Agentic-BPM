// Link check of the built site, no network. Every internal href and src in dist must resolve to a file in dist
// under SITE_BASE (default /): a root-absolute URL outside the base, or one with no file behind it, fails.
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, dirname, posix } from 'node:path';
import { fileURLToPath } from 'node:url';

const dist = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '.generated/.vitepress/dist');
const base = (process.env.SITE_BASE || '/').replace(/\/?$/, '/');

const walk = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]));
const exists = (rel) => [rel, `${rel}.html`, posix.join(rel, 'index.html')].some((c) => existsSync(join(dist, c)) && statSync(join(dist, c)).isFile());

const problems = [];
let checked = 0;
const pages = walk(dist).filter((f) => f.endsWith('.html'));
for (const file of pages) {
  const pagePath = base + file.slice(dist.length + 1).replace(/\\/g, '/');
  const html = readFileSync(file, 'utf8');
  for (const [, , raw] of html.matchAll(/<(?:a|link|script|img|source|iframe)\b[^>]*?\s(?:href|src)="([^"]*)"/g).map((m) => [m[0], m[1], m[1]])) {
    const url = raw.replace(/&amp;/g, '&');
    if (url === '' || url.startsWith('#') || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(url)) continue; // anchors and external links are not this check's business
    checked += 1;
    const path = new URL(url, `http://x${pagePath}`).pathname;
    if (!path.startsWith(base)) { problems.push(`${pagePath}: ${raw} is outside ${base}`); continue; }
    const rel = decodeURIComponent(path.slice(base.length));
    if (rel !== '' && !exists(rel)) problems.push(`${pagePath}: ${raw} has no file in dist`);
    if (rel === '' && !exists('index.html')) problems.push(`${pagePath}: ${raw} has no index.html`);
  }
}
if (pages.length === 0) problems.push(`no HTML in ${dist}`);
if (problems.length > 0) { console.error(`${problems.length} problem(s):\n${[...new Set(problems)].join('\n')}`); process.exit(1); }
console.log(`${checked} internal links and assets in ${pages.length} pages resolve under ${base}`);
