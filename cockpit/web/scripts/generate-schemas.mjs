// Generates zod schemas from the JSON Schemas in cockpit/api/schema.
// Usage: node scripts/generate-schemas.mjs          writes src/api/generated/*.ts
//        node scripts/generate-schemas.mjs --check  fails when a generated file is out of date
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { jsonSchemaToZod } from 'json-schema-to-zod';

const here = dirname(fileURLToPath(import.meta.url));
const schemaDir = join(here, '..', '..', 'api', 'schema');
const outDir = join(here, '..', 'src', 'api', 'generated');

const SCHEMAS = [
  { file: 'bpm-meta.v1.schema.json', out: 'bpm-meta.v1.ts', name: 'bpmMetaV1' },
  { file: 'bpm-me.v1.schema.json', out: 'bpm-me.v1.ts', name: 'bpmMeV1' },
  { file: 'cockpit-hosts.v1.schema.json', out: 'cockpit-hosts.v1.ts', name: 'cockpitHostsV1' },
];

/** Replaces local "#/$defs/x" references with the definition, so the generator sees plain schemas. */
function inlineRefs(node, defs) {
  if (Array.isArray(node)) return node.map((item) => inlineRefs(item, defs));
  if (node === null || typeof node !== 'object') return node;
  if (typeof node.$ref === 'string') {
    const match = /^#\/\$defs\/([A-Za-z0-9_-]+)$/.exec(node.$ref);
    if (!match || !(match[1] in defs)) throw new Error(`Unsupported $ref ${node.$ref}`);
    return inlineRefs(defs[match[1]], defs);
  }
  const copy = {};
  for (const [key, value] of Object.entries(node)) {
    if (key === '$defs') continue;
    copy[key] = inlineRefs(value, defs);
  }
  return copy;
}

function generate({ file, name }) {
  const schema = JSON.parse(readFileSync(join(schemaDir, file), 'utf8'));
  const plain = inlineRefs(schema, schema.$defs ?? {});
  const code = jsonSchemaToZod(plain, { module: 'esm', name, type: true, noWithExplicitAny: false })
    // Unknown extra properties are kept but typed as unknown, never any.
    .replaceAll('.catchall(z.any())', '.catchall(z.unknown())')
    // Generated describe() texts repeat the JSON Schema; they are not needed at runtime.
    .replaceAll(/\.describe\((?:"(?:[^"\\]|\\.)*")\)/g, '');
  if (code.includes('z.any()')) throw new Error(`${file}: the generated schema still contains z.any()`);
  return [
    `// Generated from cockpit/api/schema/${file} by scripts/generate-schemas.mjs. Do not edit.`,
    '// Regenerate with: npm run schemas',
    code.trim(),
    '',
  ].join('\n');
}

const check = process.argv.includes('--check');
let stale = 0;
for (const entry of SCHEMAS) {
  const target = join(outDir, entry.out);
  const next = generate(entry);
  if (check) {
    const current = existsSync(target) ? readFileSync(target, 'utf8') : '';
    if (current !== next) {
      console.error(`stale: src/api/generated/${entry.out} (run npm run schemas)`);
      stale += 1;
    }
  } else {
    writeFileSync(target, next);
    console.log(`wrote src/api/generated/${entry.out}`);
  }
}
if (stale > 0) process.exit(1);
if (check) console.log(`${SCHEMAS.length} generated schemas are up to date`);
