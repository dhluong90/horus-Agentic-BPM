#!/usr/bin/env bash
# Validate every YAML example in wiki/*.md against the definition-format schemas.
#
#   rule-engine/api/schema/rule-set.v1.schema.json
#   process-engine/api/schema/process-definition.v1.schema.json
#
# Usage:   tools/validate-examples.sh [wiki-dir]
# Needs:   Node.js 18 or later and npm. On first run the script installs three
#          permissively licensed packages into a cache directory outside the
#          repository: ajv 8.20.0 (MIT), ajv-formats 3.0.1 (MIT), js-yaml 4.1.0 (MIT).
#          Set HORUS_VALIDATE_CACHE to choose the directory. Offline: run once
#          with network access, or point HORUS_VALIDATE_CACHE at a directory
#          that already has those packages in node_modules.
# Exit:    0 all examples valid, 1 at least one example invalid, 2 tooling missing.
#
# How a block is classified:
#   - keys spring.* or horus.*                 configuration, skipped
#   - ruleSet and rules present                full rule set, whole schema
#   - other rule-set keys only                 rule-set fragment, required keys not enforced
#   - a map with only when                     rule-row fragment, checked against $defs/when
#   - process and steps present                full process definition, whole schema
#   - a list of maps with type                 process steps, each checked against $defs/step
# Rule-set blocks that declare outputs and rules also get a lint the schema
# cannot express: a string output whose then value is a bare identifier that is
# not a declared input is reported, because the expression language reads it
# as an identifier. String literals need inner quotes: "'UNDER_AGE'".

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIKI="${1:-$ROOT/wiki}"
CACHE="${HORUS_VALIDATE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/horus-bpm-validate}"

command -v node >/dev/null 2>&1 || { echo "node not found; install Node.js 18 or later" >&2; exit 2; }

if [ ! -d "$CACHE/node_modules/ajv" ] || [ ! -d "$CACHE/node_modules/ajv-formats" ] || [ ! -d "$CACHE/node_modules/js-yaml" ]; then
  command -v npm >/dev/null 2>&1 || { echo "npm not found and $CACHE has no packages" >&2; exit 2; }
  mkdir -p "$CACHE"
  echo "Installing validator packages into $CACHE" >&2
  npm install --prefix "$CACHE" --no-audit --no-fund --silent \
    ajv@8.20.0 ajv-formats@3.0.1 js-yaml@4.1.0 >&2 || {
      echo "npm install failed (offline?). See the header of this script." >&2; exit 2; }
fi

NODE_PATH="$CACHE/node_modules" node - "$ROOT" "$WIKI" <<'JS'
const fs = require('fs');
const path = require('path');
const Ajv2020 = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats').default;
const yaml = require('js-yaml');

const [root, wiki] = process.argv.slice(2);
const ruleSchema = JSON.parse(fs.readFileSync(path.join(root, 'rule-engine/api/schema/rule-set.v1.schema.json'), 'utf8'));
const procSchema = JSON.parse(fs.readFileSync(path.join(root, 'process-engine/api/schema/process-definition.v1.schema.json'), 'utf8'));

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema(ruleSchema);
ajv.addSchema(procSchema);
const fragmentOf = (schema, id) => Object.assign(JSON.parse(JSON.stringify(schema)), { $id: id, required: [] });
const v = {
  ruleFull: ajv.getSchema(ruleSchema.$id),
  ruleFragment: ajv.compile(fragmentOf(ruleSchema, ruleSchema.$id + ':fragment')),
  when: ajv.getSchema(ruleSchema.$id + '#/$defs/when'),
  procFull: ajv.getSchema(procSchema.$id),
  procFragment: ajv.compile(fragmentOf(procSchema, procSchema.$id + ':fragment')),
  step: ajv.getSchema(procSchema.$id + '#/$defs/step'),
};
const ruleKeys = new Set(Object.keys(ruleSchema.properties));
const procKeys = new Set(Object.keys(procSchema.properties));
const isMap = (x) => x !== null && typeof x === 'object' && !Array.isArray(x);
const fmt = (errs) => (errs || []).map((e) => `    ${e.instancePath || '/'} ${e.message}${e.params && e.params.allowedValue !== undefined ? ' ' + JSON.stringify(e.params.allowedValue) : ''}${e.params && e.params.additionalProperty ? ' (' + e.params.additionalProperty + ')' : ''}${e.params && e.params.unevaluatedProperty ? ' (' + e.params.unevaluatedProperty + ')' : ''}`);

function lintRuleSet(doc) {
  const out = [];
  if (!Array.isArray(doc.outputs) || !Array.isArray(doc.rules)) return out;
  const inputs = new Set((doc.inputs || []).map((i) => i && i.name));
  const outputs = new Map(doc.outputs.filter(isMap).map((o) => [o.name, o.type]));
  doc.rules.filter(isMap).forEach((rule, i) => {
    if (!isMap(rule.then)) return;
    for (const [name, value] of Object.entries(rule.then)) {
      if (!outputs.has(name)) { if (doc.outputs.length) out.push(`    /rules/${i}/then/${name} is not a declared output`); continue; }
      if (outputs.get(name) === 'string' && typeof value === 'string' && /^[A-Za-z_][A-Za-z0-9_]*$/.test(value.trim())
          && !inputs.has(value.trim()) && !['true', 'false', 'null'].includes(value.trim())) {
        out.push(`    /rules/${i}/then/${name} "${value}" is an identifier, not a string literal; write "'${value}'"`);
      }
    }
  });
  return out;
}

function classify(doc) {
  if (isMap(doc)) {
    const keys = Object.keys(doc);
    if (keys.some((k) => k === 'spring' || k === 'horus' || k.startsWith('spring.') || k.startsWith('horus.'))) return ['skip', 'configuration'];
    if (keys.length === 1 && keys[0] === 'when') return ['when', 'rule-row fragment'];
    if (keys.includes('ruleSet') && keys.includes('rules')) return ['ruleFull', 'rule set'];
    if (keys.includes('process') && keys.includes('steps')) return ['procFull', 'process definition'];
    if (keys.every((k) => ruleKeys.has(k)) && !keys.every((k) => procKeys.has(k))) return ['ruleFragment', 'rule-set fragment'];
    if (keys.every((k) => procKeys.has(k))) return ['procFragment', 'process fragment'];
  }
  if (Array.isArray(doc) && doc.length && doc.every((s) => isMap(s) && 'type' in s)) return ['steps', 'process steps'];
  return ['unknown', 'unclassified'];
}

let pass = 0, fail = 0, skip = 0;
const files = fs.readdirSync(wiki).filter((f) => f.endsWith('.md')).sort();
for (const file of files) {
  const lines = fs.readFileSync(path.join(wiki, file), 'utf8').split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (!/^\s*```ya?ml\s*$/.test(lines[i])) continue;
    const start = i + 1;
    const body = [];
    for (i++; i < lines.length && !/^\s*```\s*$/.test(lines[i]); i++) body.push(lines[i]);
    const where = `${file}:${start}`;
    let doc;
    try { doc = yaml.load(body.join('\n'), { schema: yaml.CORE_SCHEMA }); }
    catch (e) { fail++; console.log(`FAIL ${where} YAML does not parse\n    ${e.message.split('\n')[0]}`); continue; }
    const [kind, label] = classify(doc);
    if (kind === 'skip' || kind === 'unknown') { skip++; console.log(`SKIP ${where} ${label}`); continue; }
    let errors = [];
    if (kind === 'when') { if (!v.when(doc.when)) errors = fmt(v.when.errors).map((l) => l.replace('    /', '    /when/')); }
    else if (kind === 'steps') { doc.forEach((s, n) => { if (!v.step(s)) errors.push(...fmt(v.step.errors).map((l) => l.replace('    /', `    /${n}/`))); }); }
    else if (!v[kind](doc)) errors = fmt(v[kind].errors);
    if (kind === 'ruleFull' || kind === 'ruleFragment') errors.push(...lintRuleSet(doc));
    if (errors.length) { fail++; console.log(`FAIL ${where} ${label}\n${[...new Set(errors)].join('\n')}`); }
    else { pass++; console.log(`PASS ${where} ${label}`); }
  }
}
console.log(`\n${pass} passed, ${fail} failed, ${skip} skipped`);
process.exit(fail ? 1 : 0);
JS
