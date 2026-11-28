// Guard for the "no chips" rule: a status, count, tag or legend is text, never a rounded box with a tinted fill or a border.
// It reads CSS and fails when one rule gives a chip-like class a rounded corner together with a fill or a border.
// Usage: node scripts/check-no-chips.mjs
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');

/** Class names that mean a label on a thing: status, badge, chip, pill, tag, count, mark, verdict, flag, cue, label, note, legend, meta. */
const CHIP_NAME = /(?:^|[_-])(?:badge|chip|pill|tag|status|count|mark|verdict|flag|cue|label|note|legend|meta)(?:$|[_-])/i;

/**
 * The only classes that may be a rounded, painted box although a name above matches. Each is one exact class in one
 * file with the reason; nothing is exempt by a word inside its name (a `tile__status` or `node__count` is checked like
 * any other). An entry that matches nothing is reported, so the list cannot outlive its reason.
 */
export const EXEMPT = [
  { file: 'src/components/shell.css', selector: '.status-dot', why: 'an 8px round status light; the state is written in words beside it' },
];

const ROUNDED = /border-radius\s*:\s*(?!0(?:px)?\s*(?:;|$))/;
const FILLED = /(?:^|;)\s*background(?:-color)?\s*:\s*(?!none|transparent|inherit|unset)/;
const BORDERED = /(?:^|;)\s*border(?:-top|-right|-bottom|-left)?\s*:\s*(?!0|none)|box-shadow\s*:\s*inset\s+0\s+0\s+0\s+1px/;

/**
 * The chip-like families of a stylesheet: for each class family (`.tag`, `.tag--demo`) whether it rounds and whether it
 * paints. A modifier counts with its base class, so the corner and the fill may sit in different rules.
 */
function families(css, exempt) {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const found = new Map();
  for (const [, selectors, body] of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const decls = `;${body.trim()}`;
    const rounded = ROUNDED.test(body);
    const painted = FILLED.test(decls) || BORDERED.test(decls);
    if (!rounded && !painted) continue;
    for (const selector of selectors.split(',').map((part) => part.trim())) {
      const last = [...selector.matchAll(/\.([\w-]+)/g)].map((match) => match[1]).at(-1) ?? '';
      if (!CHIP_NAME.test(last) || exempt.includes(`.${last}`)) continue;
      const family = last.split('--')[0];
      const entry = found.get(family) ?? { rounded: false, painted: false };
      entry.rounded ||= rounded;
      entry.painted ||= painted;
      found.set(family, entry);
    }
  }
  return found;
}

/** The chip-like classes of one stylesheet that draw a rounded box with a fill or a border. */
export function findChips(css, exempt = []) {
  return [...families(css, exempt)].filter(([, entry]) => entry.rounded && entry.painted).map(([family]) => `.${family}`);
}

/** The stylesheets that ship: the cockpit's and, in this repository, the docs site's theme. */
export function stylesheets() {
  const dirs = [join(root, 'src'), join(root, '..', '..', 'site', '.vitepress', 'theme')];
  const files = [];
  const walk = (dir) => {
    if (!existsSync(dir)) return;
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.name.endsWith('.css')) files.push(path);
    }
  };
  dirs.forEach(walk);
  return files;
}

/**
 * Known chips that a later change removes, as `file: .class`. An entry that no longer matches is reported, so the list
 * empties itself: delete the entry when the check says so. Empty since part A of the clean-up (#673) merged.
 */
export const PENDING = [];

/**
 * Every chip-like family of the shipped stylesheets, as `file: .class`. A family is judged across files, because a base
 * class may round in one file and its modifier fill in another.
 */
export function checkAll(pending = PENDING, exempt = EXEMPT) {
  const perFile = stylesheets().map((file) => {
    const name = relative(root, file);
    return { name, found: families(readFileSync(file, 'utf8'), exempt.filter((entry) => entry.file === name).map((entry) => entry.selector)) };
  });
  const merged = new Map();
  for (const { name, found } of perFile) {
    for (const [family, entry] of found) {
      const all = merged.get(family) ?? { rounded: false, painted: false, files: [] };
      all.rounded ||= entry.rounded;
      all.painted ||= entry.painted;
      all.files.push(name);
      merged.set(family, all);
    }
  }
  const chips = [...merged].filter(([, entry]) => entry.rounded && entry.painted).flatMap(([family, entry]) => entry.files.map((name) => `${name}: .${family}`));
  const stale = pending.filter((entry) => !chips.includes(entry)).map((entry) => `${entry} is fixed: remove it from PENDING`);
  // An exemption must still name a class that is a rounded, painted box in that file; if not, it has no reason left.
  const unused = exempt.filter((entry) => {
    const file = stylesheets().find((path) => relative(root, path) === entry.file);
    if (file === undefined) return true;
    const css = readFileSync(file, 'utf8');
    return !css.includes(entry.selector);
  }).map((entry) => `${entry.file}: ${entry.selector} is not in the file: remove it from EXEMPT`);
  return [...chips.filter((entry) => !pending.includes(entry)), ...stale, ...unused];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const found = checkAll();
  if (found.length > 0) {
    console.error(`Chips found (a rounded box with a fill or a border on a status, count, tag or pill class):\n${found.map((line) => `  ${line}`).join('\n')}\nUse text, a dot or an icon instead; see docs/product/ux/styleguide.md, "No chips".`);
    process.exit(1);
  }
  console.log('No chips: ok');
}
