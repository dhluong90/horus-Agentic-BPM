// Fails when a production dependency of the cockpit (anything that can reach the shipped bundle)
// has a licence outside Apache 2.0, MIT, BSD and ISC. Development tools are not shipped and not checked here.
// Run with --include-dev from another npm project (the documentation site, whose packages are all build tools
// that end up in the generated pages) to check every package in that project's tree.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { licenceViolations } from './licences.mjs';

const omit = process.argv.includes('--include-dev') ? [] : ['--omit=dev'];
const tree = JSON.parse(execFileSync('npm', ['ls', ...omit, '--all', '--json', '--long'], { encoding: 'utf8' }));
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
// --exceptions=<file>: JSON object of "name@version" to the reason a package passes although its licence field
// is not on the list (for example a missing field with an MIT licence file). Each entry is reviewed by a person.
const exceptionsArg = process.argv.find((a) => a.startsWith('--exceptions='));
const exceptions = exceptionsArg ? JSON.parse(readFileSync(exceptionsArg.slice('--exceptions='.length), 'utf8')) : {};
const problems = licenceViolations(packages.filter((p) => !(`${p.name}@${p.version}` in exceptions)));
for (const pkg of packages.sort((a, b) => a.name.localeCompare(b.name))) console.log(`${pkg.name}@${pkg.version} ${JSON.stringify(pkg.license)}`);
if (problems.length > 0) {
  console.error(`\n${problems.length} licence problem(s):\n${problems.join('\n')}`);
  process.exit(1);
}
console.log(`\n${packages.length} packages, all licences allowed (Apache-2.0, MIT, BSD, ISC)${Object.keys(exceptions).length ? `, ${String(Object.keys(exceptions).length)} reviewed exception(s)` : ''}`);
