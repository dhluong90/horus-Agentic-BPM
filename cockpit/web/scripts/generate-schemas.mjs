// Generates zod schemas from the JSON Schemas in cockpit/api/schema.
// Usage: node scripts/generate-schemas.mjs          writes src/api/generated/*.ts
//        node scripts/generate-schemas.mjs --check  fails when a generated file is out of date
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { jsonSchemaToZod } from 'json-schema-to-zod';

const here = dirname(fileURLToPath(import.meta.url));
const schemaDir = join(here, '..', '..', 'api', 'schema');
const outDir = join(here, '..', 'src', 'api', 'generated');

const SCHEMAS = [
  { file: 'bpm-meta.v1.schema.json', out: 'bpm-meta.v1.ts', name: 'bpmMetaV1' },
  { file: 'bpm-me.v1.schema.json', out: 'bpm-me.v1.ts', name: 'bpmMeV1' },
  { file: 'cockpit-hosts.v1.schema.json', out: 'cockpit-hosts.v1.ts', name: 'cockpitHostsV1' },
];

// Keywords json-schema-to-zod turns into zod faithfully (checked by src/api/contract.test.ts).
// Any other keyword stops the generator, unless it is a known gap below with a hand-written refinement.
const SUPPORTED = new Set([
  '$schema', '$id', 'title', 'description', 'default',
  'type', 'properties', 'required', 'additionalProperties',
  'items', 'minItems', 'maxItems', 'uniqueItems',
  'minLength', 'maxLength', 'pattern', 'enum', 'const', 'minimum', 'maximum', 'anyOf',
]);

// Rules the generator drops, by file and JSON Pointer (after $ref inlining), each with the name of the
// refinement in src/app/config/registry.ts that restores it. The name list is written into the
// generated file; registry.ts must implement every name (a type error otherwise) and
// src/api/contract.test.ts must prove each one against the JSON Schema.
const KNOWN_GAPS = {
  'cockpit-hosts.v1.schema.json': {
    '/properties/auth/if': 'oidc-requires-issuer-and-client-id',
    '/properties/auth/then': 'oidc-requires-issuer-and-client-id',
    '/properties/hosts/items/properties/label/anyOf/1/required': 'label-map-requires-en',
    '/properties/hosts/items/properties/label/anyOf/1/propertyNames': 'label-map-locale-names',
  },
};

/**
 * Fails on any keyword the generator would drop silently. Returns the refinement names the schema needs.
 * Also fails when a KNOWN_GAPS entry no longer matches the schema, so the list cannot go stale.
 */
export function auditKeywords(file, schema, gapsByFile = KNOWN_GAPS) {
  const gaps = gapsByFile[file] ?? {};
  const hit = new Set();
  const walk = (node, pointer) => {
    if (node === null || typeof node !== 'object' || Array.isArray(node)) return;
    for (const [key, value] of Object.entries(node)) {
      const at = `${pointer}/${key}`;
      // 'required' on an object without 'properties' becomes a z.record, which drops the requirement.
      const droppedRequired = key === 'required' && !('properties' in node);
      if (!SUPPORTED.has(key) || droppedRequired) {
        if (!(at in gaps)) {
          throw new Error(`${file}: keyword at ${at} is not converted faithfully by the generator. `
            + 'Support it, or add it to KNOWN_GAPS with a hand-written refinement and a contract test.');
        }
        hit.add(at);
      } else if (key === 'properties') {
        for (const [name, child] of Object.entries(value)) walk(child, `${at}/${name}`);
      } else if (key === 'items' || (key === 'additionalProperties' && typeof value === 'object')) {
        walk(value, at);
      } else if (key === 'anyOf') {
        value.forEach((child, index) => walk(child, `${at}/${index}`));
      }
    }
  };
  walk(schema, '');
  const stale = Object.keys(gaps).filter((at) => !hit.has(at));
  if (stale.length > 0) throw new Error(`${file}: KNOWN_GAPS entries no longer in the schema: ${stale.join(', ')}`);
  return [...new Set([...hit].map((at) => gaps[at]))].sort();
}

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
  const refinements = auditKeywords(file, plain);
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
    '/** Schema rules the generator cannot express; each is a hand-written, tested refinement. */',
    `export const ${name}Refinements = ${JSON.stringify(refinements)} as const;`,
    '',
  ].join('\n');
}

function main() {
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
  if (stale > 0) process.exitCode = 1;
  else if (check) console.log(`${SCHEMAS.length} generated schemas are up to date`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
