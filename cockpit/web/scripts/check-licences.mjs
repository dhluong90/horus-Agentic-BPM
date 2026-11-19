// Fails when a production dependency of the cockpit (anything that can reach the shipped bundle)
// has a licence outside Apache 2.0, MIT and BSD. Development tools are not shipped and not checked here.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { licenceViolations } from './licences.mjs';

const tree = JSON.parse(execFileSync('npm', ['ls', '--omit=dev', '--all', '--json', '--long'], { encoding: 'utf8' }));
const seen = new Map();
function walk(dependencies) {
  for (const [name, node] of Object.entries(dependencies ?? {})) {
    const key = `${name}@${node.version}`;
    if (seen.has(key)) continue;
    let license = node.license;
    if (license === undefined && node.path) {
      license = JSON.parse(readFileSync(join(node.path, 'package.json'), 'utf8')).license;
    }
    seen.set(key, { name, version: node.version, license });
    walk(node.dependencies);
  }
}
walk(tree.dependencies);

const packages = [...seen.values()];
const problems = licenceViolations(packages);
for (const pkg of packages.sort((a, b) => a.name.localeCompare(b.name))) console.log(`${pkg.name}@${pkg.version} ${JSON.stringify(pkg.license)}`);
if (problems.length > 0) {
  console.error(`\n${problems.length} licence problem(s):\n${problems.join('\n')}`);
  process.exit(1);
}
console.log(`\n${packages.length} production packages, all licences allowed (Apache-2.0, MIT, BSD)`);
