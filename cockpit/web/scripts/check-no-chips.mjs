// Guard for the "no chips" rule: a status, count, tag or legend is text, never a rounded box with a tinted fill or a border.
// It reads CSS and fails when one rule gives a chip-like class a rounded corner together with a fill or a border.
// Usage: node scripts/check-no-chips.mjs
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');

/** Class names that mean a label on a thing: status, badge, chip, pill, tag, count, mark, verdict, flag. */
const CHIP_NAME = /(?:^|[_-])(?:badge|chip|pill|tag|status|count|mark|verdict|flag)(?:$|[_-])/i;
/** Shapes that are not text labels: a dot or swatch, an avatar or disc (identity), a node or tile of a diagram. */
const NOT_A_LABEL = /swatch|dot|avatar|disc|spinner|thumb|track|knob|node|tile/i;

const ROUNDED = /border-radius\s*:\s*(?!0(?:px)?\s*(?:;|$))/;
const FILLED = /(?:^|;)\s*background(?:-color)?\s*:\s*(?!none|transparent|inherit|unset)/;
const BORDERED = /(?:^|;)\s*border(?:-top|-right|-bottom|-left)?\s*:\s*(?!0|none)|box-shadow\s*:\s*inset\s+0\s+0\s+0\s+1px/;

/**
 * The chip-like classes of a stylesheet that draw a rounded box with a fill or a border. A modifier counts with its
 * base class (`.tag` rounds, `.tag--demo` fills), so the corner and the fill may sit in different rules.
 */
export function findChips(css) {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const families = new Map();
  for (const [, selectors, body] of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const decls = `;${body.trim()}`;
    const rounded = ROUNDED.test(body);
    const painted = FILLED.test(decls) || BORDERED.test(decls);
    if (!rounded && !painted) continue;
    for (const selector of selectors.split(',').map((part) => part.trim())) {
      const last = [...selector.matchAll(/\.([\w-]+)/g)].map((match) => match[1]).at(-1) ?? '';
      if (!CHIP_NAME.test(last) || NOT_A_LABEL.test(last)) continue;
      const family = last.split('--')[0];
      const entry = families.get(family) ?? { rounded: false, painted: false, selectors: [] };
      entry.rounded ||= rounded;
      entry.painted ||= painted;
      entry.selectors.push(selector);
      families.set(family, entry);
    }
  }
  return [...families].filter(([, entry]) => entry.rounded && entry.painted).map(([family]) => `.${family}`);
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
 * Chips of the shell, home, tasks, incidents and decisions that the first part of the clean-up (#673) removes. An entry
 * that no longer matches is reported, so the list empties itself: delete the entry when the check says so.
 */
export const PENDING = [
  'src/components/frame.css: .navrail__count',
  'src/components/frame.css: .badge',
  'src/components/frame.css: .filter-toggle__count',
  'src/components/home.css: .pill',
  'src/components/shell.css: .service-pill',
  'src/components/shell.css: .tag',
  'src/components/shell.css: .pill',
];

export function checkAll(pending = PENDING) {
  const found = stylesheets().flatMap((file) => findChips(readFileSync(file, 'utf8')).map((selector) => `${relative(root, file)}: ${selector}`));
  const stale = pending.filter((entry) => !found.includes(entry)).map((entry) => `${entry} is fixed: remove it from PENDING`);
  return [...found.filter((entry) => !pending.includes(entry)), ...stale];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const found = checkAll();
  if (found.length > 0) {
    console.error(`Chips found (a rounded box with a fill or a border on a status, count, tag or pill class):\n${found.map((line) => `  ${line}`).join('\n')}\nUse text, a dot or an icon instead; see docs/product/ux/styleguide.md, "No chips".`);
    process.exit(1);
  }
  console.log('No chips: ok');
}
