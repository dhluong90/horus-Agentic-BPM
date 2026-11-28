// Generates the VitePress source in site/.generated from the wiki/ folder.
// - The homepage and the docs home are site-owned (site/content, site/public). The wiki's Home.md keeps its name, at /Home.
// - Every wiki page keeps its name; llms.txt is generated from the sidebar.
// - [[Text|Target]] and [[Target]] become relative links; an unknown target fails the build.
// - Images that point at the wiki's raw URL are copied from wiki/images and linked locally, so a reader's
//   browser makes no request to another origin.
// - The sidebar follows wiki/_Sidebar.md; a page missing from it, or listed twice, fails the build.
// Usage: node prepare.mjs [--check]   (--check does the checks and writes nothing)
import { readFileSync, writeFileSync, mkdirSync, rmSync, cpSync, readdirSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const wiki = join(here, '..', 'wiki');
const out = join(here, '.generated');
export const SITE_URL = 'https://dhluong90.github.io/horus-Agentic-BPM';
const RAW = /https:\/\/raw\.githubusercontent\.com\/wiki\/dhluong90\/horus-Agentic-BPM\//g;

/**
 * Rendered diagrams carry width="100%", so a browser has no size to draw them at and shrinks a wide one to the column.
 * Giving the copy its own width, from its viewBox, lets the stylesheet show it at that size and scroll it on a phone.
 */
export function sizeSvg(text) {
  const box = text.match(/viewBox="[-\d.]+ [-\d.]+ ([\d.]+) ([\d.]+)"/);
  if (!box || !/^<svg[^>]*\swidth="100%"/.test(text)) return text;
  return text.replace(/^(<svg[^>]*?)\swidth="100%"/, `$1 width="${Math.round(Number(box[1]))}" height="${Math.round(Number(box[2]))}"`);
}

export function slug(target) {
  return target.trim().replace(/\s+/g, '-');
}

export function convertLinks(text, pages, page, errors) {
  // Leave fenced code alone: links inside it are examples.
  return text.split(/(```[\s\S]*?```)/g).map((part, i) => {
    if (i % 2 === 1) return part;
    return part.replace(/\[\[([^\]]+)\]\]/g, (m, inner) => {
      if (/^Page Name$/.test(inner) || /`[^`]*$/.test(part.slice(0, part.indexOf(m)))) return m;
      const [label, tgt] = inner.includes('|') ? inner.split('|') : [inner, inner];
      const [name, anchor] = tgt.split('#');
      const s = slug(name);
      if (!pages.has(s)) { errors.push(`${page}: unknown wiki link [[${inner}]]`); return m; }
      const href = `./${s}` + (anchor ? `#${anchor.toLowerCase()}` : '');
      return `[${label.trim()}](${href})`;
    });
  }).join('').replace(RAW, '/');
}

export function parseSidebar(text, pages, errors) {
  const groups = []; let cur = null; const seen = new Map();
  for (const line of text.split('\n')) {
    const link = line.match(/\[\[([^\]]+)\]\]/);
    const bold = line.match(/^\*\*([^*]+)\*\*:?(.*)$/);
    if (bold && !(link && bold[1].startsWith('[['))) { cur = { text: bold[1].trim(), items: [] }; groups.push(cur); continue; }
    if (bold && link && !cur) { cur = { text: 'Start', items: [] }; groups.push(cur); }
    if (!link) continue;
    const [label, tgt] = link[1].includes('|') ? link[1].split('|') : [link[1], link[1]];
    const s = slug(tgt);
    if (!pages.has(s)) { errors.push(`_Sidebar: unknown page ${s}`); continue; }
    seen.set(s, (seen.get(s) ?? 0) + 1);
    if (!cur) { cur = { text: 'Start', items: [] }; groups.push(cur); }
    cur.items.push({ text: label.trim(), link: `/${s}` });
  }
  for (const p of pages) if (p !== 'Footer' && !seen.has(p)) errors.push(`_Sidebar: page ${p} is not listed`);
  for (const [p, n] of seen) if (n > 1) errors.push(`_Sidebar: page ${p} is listed ${n} times`);
  return groups.filter((g) => g.items.length);
}

// llms.txt: a plain list of the pages for language-model crawlers, in sidebar order, with absolute URLs.
export function llmsTxt(sidebar, siteUrl = SITE_URL) {
  const lines = ['# HORUS Agentic BPM', '', '> Embeddable BPMN, DMN and rules engine for Java. It runs inside your Spring Boot service and PostgreSQL, and enforces a second approver on every rule change.', ''];
  for (const g of sidebar) {
    lines.push(`## ${g.text}`, '');
    for (const i of g.items) lines.push(`- [${i.text}](${siteUrl}${i.link})`);
    lines.push('');
  }
  return lines.join('\n');
}

export function build(check = false) {
  const errors = [];
  const files = readdirSync(wiki).filter((f) => f.endsWith('.md'));
  const pages = new Set(files.map((f) => basename(f, '.md')).filter((p) => p !== '_Sidebar' && p !== '_Footer'));
  const sidebar = parseSidebar(readFileSync(join(wiki, '_Sidebar.md'), 'utf8'), pages, errors);
  if (!check) { rmSync(out, { recursive: true, force: true }); mkdirSync(join(out, '.vitepress'), { recursive: true }); }
  for (const p of pages) {
    let body = convertLinks(readFileSync(join(wiki, `${p}.md`), 'utf8'), pages, p, errors);
    // The page title is the first heading; the site shows it, so no front matter is needed.
    if (!check) writeFileSync(join(out, `${p}.md`), body);
  }
  if (errors.length) { console.error(errors.join('\n')); throw new Error(`${errors.length} problem(s) in the wiki content`); }
  if (!check) {
    cpSync(join(wiki, 'images'), join(out, 'images'), { recursive: true });
    for (const file of readdirSync(join(out, 'images')).filter((name) => name.endsWith('.svg'))) {
      const path = join(out, 'images', file);
      writeFileSync(path, sizeSvg(readFileSync(path, 'utf8')));
    }
    cpSync(join(here, 'content'), out, { recursive: true });
    cpSync(join(here, 'public'), join(out, 'public'), { recursive: true });
    writeFileSync(join(out, 'public', 'llms.txt'), llmsTxt(sidebar));
    cpSync(join(here, '.vitepress', 'config.mjs'), join(out, '.vitepress', 'config.mjs'));
    cpSync(join(here, '.vitepress', 'theme'), join(out, '.vitepress', 'theme'), { recursive: true });
    writeFileSync(join(out, '.vitepress', 'sidebar.json'), JSON.stringify(sidebar, null, 2));
  }
  console.log(`${pages.size} pages, ${sidebar.length} sidebar groups${check ? ' (checked, nothing written)' : ''}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try { build(process.argv.includes('--check')); } catch (e) { console.error(e.message); process.exit(1); }
}
