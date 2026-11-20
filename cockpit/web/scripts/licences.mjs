// Licence rule for the shipped cockpit bundle (TR-LIC-01): Apache 2.0, MIT, BSD and ISC only,
// and the packages the cockpit design rejects by name are refused even if relicensed.
export const ALLOWED = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', '0BSD', 'ISC']);
export const REJECTED_BY_NAME = new Set(['bpmn-js', 'diagram-js', 'elkjs', 'jointjs', '@joint/core']);

/** SPDX expression to the identifiers it allows: "(MIT OR Apache-2.0)" passes if any branch passes. */
function allowedExpression(expression) {
  if (typeof expression !== 'string' || expression.trim() === '') return false;
  const cleaned = expression.replaceAll(/[()]/g, ' ').trim();
  if (/\bAND\b/.test(cleaned)) return cleaned.split(/\s+AND\s+/).every((part) => allowedExpression(part));
  return cleaned.split(/\s+OR\s+/).some((id) => ALLOWED.has(id.trim()));
}

/**
 * @param {Array<{ name: string, version: string, license: unknown }>} packages
 * @returns {string[]} one message per refused package; empty when every package passes
 */
export function licenceViolations(packages) {
  const problems = [];
  for (const pkg of packages) {
    if (REJECTED_BY_NAME.has(pkg.name)) {
      problems.push(`${pkg.name}@${pkg.version}: rejected by name in the cockpit design`);
      continue;
    }
    const license = typeof pkg.license === 'object' && pkg.license !== null ? pkg.license.type : pkg.license;
    if (!allowedExpression(license)) problems.push(`${pkg.name}@${pkg.version}: licence ${JSON.stringify(license ?? null)} is not allowed`);
  }
  return problems;
}
