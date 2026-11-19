// Release build of the cockpit bundle with a record of what is in it (TR-SEC-06, TR-LIC-01).
//
// Usage (from cockpit/web, after `npm ci --ignore-scripts` and `tsc -b`):
//   node ../../tools/release/cockpit-build.mjs MODULES.json BUNDLE.txt
// Normally called through tools/release/build-cockpit.sh, which the release build and the container
// image both use.
//
// Runs the cockpit's own `vite build` (its vite.config.ts, its installed Vite) with one extra plugin,
// then writes two files outside dist/, so nothing is added to the jar:
// BUNDLE.txt is the SHA-256 of every file of dist/ (cockpit-hosts.json excepted, as in the starter's
// resource rule), written right after the build in the `shasum -a 256` format, sorted by path.
// MODULES.json records what is in the bundle:
//   packages        every node_modules package with at least one module in the bundle: name, version,
//                   licence and the package directory
//   bundlerRuntime  packages whose runtime helpers the bundler injected (virtual modules such as
//                   "\0rolldown/runtime.js"), with the module ids
//   unattributed    module ids that belong to no package and are not cockpit sources; the release
//                   build fails on any
// release-sbom.py bundle-sbom then fails when a bundled package is not a production dependency.
//
// The cockpit's "build" script must stay "tsc -b && vite build"; this file fails if it changes, so the
// release build and `npm run build` cannot drift apart.

import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const EXPECTED_BUILD_SCRIPT = 'tsc -b && vite build';
// Virtual module id prefixes and the package that injects them.
const VIRTUAL_OWNERS = [
  ['rolldown/', 'rolldown'],
  ['rolldown:', 'rolldown'],
  ['vite/', 'vite'],
  ['plugin-react', '@vitejs/plugin-react'],
];

const NOT_PACKAGED = new Set(['cockpit-hosts.json']);
const [out, bundleOut] = process.argv.slice(2);
if (!out || !bundleOut) {
  console.error('Usage: node cockpit-build.mjs MODULES.json BUNDLE.txt (run from cockpit/web)');
  process.exit(2);
}
const web = process.cwd();
const manifest = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8'));
if (manifest.scripts?.build !== EXPECTED_BUILD_SCRIPT) {
  console.error(`cockpit/web "build" script is ${JSON.stringify(manifest.scripts?.build)}, `
    + `expected ${JSON.stringify(EXPECTED_BUILD_SCRIPT)}. Update tools/release/cockpit-build.mjs to match.`);
  process.exit(1);
}

const fromWeb = createRequire(join(web, 'package.json'));
const vitePackageJson = fromWeb.resolve('vite/package.json');
const vite = await import(pathToFileURL(fromWeb.resolve('vite')).href);
const fromVite = createRequire(vitePackageJson);

function licenceOf(pkg) {
  if (typeof pkg.license === 'string') return pkg.license;
  if (pkg.license?.type) return pkg.license.type;
  if (Array.isArray(pkg.licenses)) return pkg.licenses.map((l) => l.type ?? l).join(' AND ');
  return '';
}

function readPackage(directory) {
  const pkg = JSON.parse(readFileSync(join(directory, 'package.json'), 'utf8'));
  return { name: pkg.name, version: pkg.version, license: licenceOf(pkg) };
}

function packageDirectory(file) {
  const marker = `${sep}node_modules${sep}`;
  const at = file.lastIndexOf(marker);
  if (at < 0) return null;
  const rest = file.slice(at + marker.length).split(sep);
  const depth = rest[0].startsWith('@') ? 2 : 1;
  return file.slice(0, at + marker.length) + rest.slice(0, depth).join(sep);
}

function ownerPackage(name) {
  for (const require of [fromVite, fromWeb]) {
    try {
      return dirname(require.resolve(`${name}/package.json`));
    } catch {
      // try the next resolution root
    }
  }
  return null;
}

const packages = new Map();
const runtime = new Map();
const unattributed = new Set();

function record(id) {
  const virtual = id.startsWith('\0');
  const bare = (virtual ? id.slice(1) : id).split('?')[0];
  const file = bare.startsWith('/') || /^[A-Za-z]:[\\/]/.test(bare) ? resolve(bare) : null;
  const directory = file ? packageDirectory(file) : null;
  if (directory) {
    const pkg = readPackage(directory);
    packages.set(`${pkg.name}@${pkg.version}`, { ...pkg, directory });
    return;
  }
  if (file && file.startsWith(web + sep)) return; // the cockpit's own sources and index.html
  if (virtual) {
    const owner = VIRTUAL_OWNERS.find(([prefix]) => bare.startsWith(prefix))?.[1];
    const ownerDirectory = owner ? ownerPackage(owner) : null;
    if (ownerDirectory) {
      const pkg = readPackage(ownerDirectory);
      const key = `${pkg.name}@${pkg.version}`;
      const entry = runtime.get(key) ?? { ...pkg, moduleIds: [] };
      entry.moduleIds = [...new Set([...entry.moduleIds, JSON.stringify(id).slice(1, -1)])].sort();
      runtime.set(key, entry);
      return;
    }
  }
  unattributed.add(JSON.stringify(id).slice(1, -1));
}

const recorder = {
  name: 'horus-release-bundle-record',
  apply: 'build',
  generateBundle(_options, bundle) {
    for (const output of Object.values(bundle)) {
      if (output.type !== 'chunk') continue;
      for (const id of output.moduleIds ?? Object.keys(output.modules ?? {})) record(id);
    }
  },
};

await vite.build({ root: web, plugins: [recorder] });

// The bundle's fingerprint, taken before anything else runs. Paths compare by UTF-16 code unit, which is
// byte order for the ASCII names Vite writes.
const dist = join(web, 'dist');
const files = readdirSync(dist, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isFile() && !NOT_PACKAGED.has(entry.name))
  .map((entry) => join(entry.parentPath ?? entry.path, entry.name).slice(dist.length + 1).split(sep).join('/'))
  .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
for (const file of files) {
  if (/[\r\n]/.test(file)) {
    console.error(`dist/ has a file name with a line break: ${JSON.stringify(file)}`);
    process.exit(1);
  }
}
if (!files.includes('index.html')) {
  console.error('dist/index.html is missing: the cockpit bundle was not built');
  process.exit(1);
}
const lines = files.map((file) => `${createHash('sha256').update(readFileSync(join(dist, file))).digest('hex')}  ./${file}\n`);
writeFileSync(bundleOut, lines.join(''));
console.log(`${bundleOut}: SHA-256 of ${files.length} file(s) under dist/`);

const sorted = (map) => [...map.values()].sort((a, b) => `${a.name}@${a.version}`.localeCompare(`${b.name}@${b.version}`));
const result = {
  vite: readPackage(dirname(vitePackageJson)).version,
  packages: sorted(packages).map(({ directory, ...pkg }) => ({ ...pkg, directory: directory.slice(web.length + 1) })),
  bundlerRuntime: sorted(runtime),
  unattributed: [...unattributed].sort(),
};
writeFileSync(out, `${JSON.stringify(result, null, 2)}\n`);
console.log(`${out}: ${result.packages.length} bundled package(s), `
  + `${result.bundlerRuntime.length} bundler runtime package(s), ${result.unattributed.length} unattributed module(s)`);
