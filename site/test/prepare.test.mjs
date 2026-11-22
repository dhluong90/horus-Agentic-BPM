import { test } from 'node:test';
import assert from 'node:assert/strict';
import { convertLinks, parseSidebar } from '../prepare.mjs';

const pages = new Set(['Home', 'Cockpit', 'Getting-Started']);

test('wiki links become relative links, with and without a label', () => {
  const errors = [];
  const out = convertLinks('See [[Cockpit]] and [[Start here|Getting-Started]] and [[Home]].', pages, 'X', errors);
  assert.equal(out, 'See [Cockpit](./Cockpit) and [Start here](./Getting-Started) and [Home](./).');
  assert.deepEqual(errors, []);
});

test('an unknown wiki link is reported', () => {
  const errors = [];
  convertLinks('[[Nowhere]]', pages, 'X', errors);
  assert.equal(errors.length, 1);
});

test('links inside code fences are left alone', () => {
  const errors = [];
  const t = '```\n[[Nowhere]]\n```';
  assert.equal(convertLinks(t, pages, 'X', errors), t);
  assert.deepEqual(errors, []);
});

test('raw wiki image urls become local paths', () => {
  const out = convertLinks('![a](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/a.svg)', pages, 'X', []);
  assert.equal(out, '![a](/images/a.svg)');
});

test('the sidebar fails on a page that is missing or listed twice', () => {
  const errors = [];
  parseSidebar('**[[Home]]**\n\n**Group**\n- [[Cockpit]]\n- [[Cockpit]]\n', pages, errors);
  assert.ok(errors.some((e) => e.includes('Getting-Started is not listed')));
  assert.ok(errors.some((e) => e.includes('Cockpit is listed 2 times')));
});
