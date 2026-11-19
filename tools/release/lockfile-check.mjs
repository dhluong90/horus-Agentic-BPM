// Host and integrity check of npm lockfiles that runs before any npm package is installed or executed
// (TR-SEC-06). Node built-ins only, so it needs nothing from the trees it checks; lockfile-lint runs
// afterwards as a second opinion.
//
// Usage:   node tools/release/lockfile-check.mjs LOCKFILE...
// Fails unless every lockfile is lockfileVersion 2 or 3 and every entry of "packages", except the
// root ("") and local links ("link": true), has
//   resolved   starting with https://registry.npmjs.org/
//   integrity  a sha512- hash
// Exit:    0 when every lockfile passes, 1 when one does not, 2 on a usage error.

import { readFileSync } from 'node:fs';

const REGISTRY = 'https://registry.npmjs.org/';
const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('Usage: node tools/release/lockfile-check.mjs LOCKFILE...');
  process.exit(2);
}

let failed = false;
for (const file of files) {
  const lock = JSON.parse(readFileSync(file, 'utf8'));
  const errors = [];
  if (![2, 3].includes(lock.lockfileVersion) || typeof lock.packages !== 'object') {
    errors.push(`lockfileVersion ${JSON.stringify(lock.lockfileVersion)}; only 2 and 3, with "packages", are checked`);
  }
  let checked = 0;
  for (const [path, entry] of Object.entries(lock.packages ?? {})) {
    if (path === '' || entry.link === true) continue;
    checked += 1;
    if (typeof entry.resolved !== 'string' || !entry.resolved.startsWith(REGISTRY)) {
      errors.push(`${path}: resolved ${JSON.stringify(entry.resolved)} is not on ${REGISTRY}`);
    }
    if (typeof entry.integrity !== 'string' || !/^sha512-[A-Za-z0-9+/]{86}==$/.test(entry.integrity)) {
      errors.push(`${path}: integrity ${JSON.stringify(entry.integrity)} is not a sha512 hash`);
    }
  }
  for (const message of errors) console.error(`ERROR: ${file}: ${message}`);
  if (errors.length > 0) {
    failed = true;
  } else {
    console.log(`${file}: ${checked} package(s), all from ${REGISTRY} with sha512 integrity`);
  }
}
process.exit(failed ? 1 : 0);
