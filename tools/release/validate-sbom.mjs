// Validates CycloneDX JSON documents against the CycloneDX 1.6 JSON schema (strict).
//
// Usage (from tools/release/npm, after `npm ci --ignore-scripts`):
//   node ../validate-sbom.mjs FILE...
// Uses @cyclonedx/cyclonedx-library and ajv from this directory's lockfile; no network.
// Exit: 0 when every file is valid, 1 when one is not, 2 on a usage error or a missing validator.

import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('Usage: node validate-sbom.mjs FILE...');
  process.exit(2);
}

const require = createRequire(join(process.cwd(), 'package.json'));
const { Spec, Validation } = require('@cyclonedx/cyclonedx-library');
const validator = new Validation.JsonStrictValidator(Spec.Version.v1dot6);

let failed = false;
for (const file of files) {
  const text = readFileSync(file, 'utf8');
  const spec = JSON.parse(text).specVersion;
  if (spec !== '1.6') {
    console.error(`ERROR: ${file}: specVersion ${JSON.stringify(spec)}, expected "1.6"`);
    failed = true;
    continue;
  }
  let errors;
  try {
    errors = await validator.validate(text);
  } catch (error) {
    console.error(`ERROR: the CycloneDX validator could not run (${error.message}); run npm ci in tools/release/npm`);
    process.exit(2);
  }
  if (errors === null) {
    console.log(`${file}: valid CycloneDX 1.6`);
  } else {
    console.error(`ERROR: ${file} is not valid CycloneDX 1.6:\n${JSON.stringify(errors, null, 2)}`);
    failed = true;
  }
}
process.exit(failed ? 1 : 0);
