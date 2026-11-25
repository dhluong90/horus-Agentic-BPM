// Parses every Mermaid block of the site with the Mermaid version the site builds with. A syntax error would otherwise
// show only in the browser, as an error panel at the bottom of the page.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import DOMPurify from 'dompurify';
import mermaid from 'mermaid';

// Mermaid sanitises label text with DOMPurify, which needs a browser DOM. A syntax check does not: stub the two calls
// the parser makes, so no DOM package is added to the site. Mermaid shares this module instance.
DOMPurify.addHook ??= () => {};
DOMPurify.sanitize ??= (text) => String(text);
DOMPurify.removeHook ??= () => {};

const here = dirname(fileURLToPath(import.meta.url));
const roots = [join(here, '..', '..', 'wiki'), join(here, '..')];
const skip = new Set(['node_modules', '.generated', '.git', 'cache', 'dist']);

const markdown = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
  if (e.isDirectory()) return skip.has(e.name) ? [] : markdown(join(dir, e.name));
  return e.name.endsWith('.md') ? [join(dir, e.name)] : [];
});

// Every ```mermaid fence, with the line it starts on.
export const blocks = (text) => [...text.matchAll(/^```mermaid[^\n]*\n([\s\S]*?)^```/gm)].map((m) => ({
  code: m[1], line: text.slice(0, m.index).split('\n').length
}));

// Returns the parse error of a diagram, or null when it parses.
export const parseError = async (code) => {
  try { await mermaid.parse(code); return null; } catch (e) { return String(e.message ?? e).split('\n').slice(0, 3).join(' | ').replace(/Expecting .*/, ''); }
};

test('every Mermaid block in wiki/ and site/ parses', async () => {
  const failures = [];
  let count = 0;
  for (const file of roots.flatMap(markdown)) {
    for (const b of blocks(readFileSync(file, 'utf8'))) {
      count += 1;
      const err = await parseError(b.code);
      if (err) failures.push(`${relative(join(here, '..', '..'), file)}:${b.line}: ${err}`);
    }
  }
  assert.ok(count > 0, 'no Mermaid block found: the scan is broken');
  assert.deepEqual(failures, [], `Mermaid syntax errors:\n${failures.join('\n')}`);
});

test('a broken Mermaid block is reported', async () => {
  const [b] = blocks('text\n```mermaid\nflowchart LR\n  A --> \n```\n');
  assert.equal(b.line, 2);
  assert.match(await parseError(b.code) ?? '', /Parse error|Expecting/);
});

test('a valid Mermaid block parses', async () => {
  assert.equal(await parseError('flowchart LR\n  A --> B\n'), null);
});
