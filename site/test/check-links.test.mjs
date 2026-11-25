import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = join(dirname(fileURLToPath(import.meta.url)), '..', 'check-links.mjs');

// Builds a one-page dist under /b/ with the given files and body, and runs the check on it.
const run = (body, files) => {
  const dist = mkdtempSync(join(tmpdir(), 'check-links-'));
  try {
    writeFileSync(join(dist, 'index.html'), `<!doctype html><html><body>${body}</body></html>`);
    for (const f of files) { mkdirSync(dirname(join(dist, f)), { recursive: true }); writeFileSync(join(dist, f), 'x'); }
    return spawnSync(process.execPath, [script, dist], { env: { ...process.env, SITE_BASE: '/' }, encoding: 'utf8' });
  } finally { rmSync(dist, { recursive: true, force: true }); }
};

const img = '<img src="/images/a-700.webp" srcset="/images/a-700.webp 700w, /images/a-1030.webp 1030w" alt="">';

test('every srcset variant present: passes', () => {
  assert.equal(run(img, ['images/a-700.webp', 'images/a-1030.webp']).status, 0);
});

test('a missing srcset variant fails the check and is named', () => {
  const r = run(img, ['images/a-700.webp']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /a-1030\.webp has no file in dist/);
});

test('a missing source srcset variant and a missing poster fail', () => {
  const r = run('<picture><source srcset="/i/x.webp 1x, /i/x2.webp 2x"></picture><video poster="/i/p.png" src="/i/v.mp4"></video>', ['i/x.webp', 'i/v.mp4']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /x2\.webp has no file/);
  assert.match(r.stderr, /p\.png has no file/);
});
