import { Ajv2020 } from 'ajv/dist/2020.js';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { META_MODULES, metaRulesOnly, metaWithBadKnownModule, metaWithUnknownModule, UNKNOWN_MODULE } from '../mocks/fixtures';
import { createHostClient } from './hostClient';
import { HostCallError } from './hostError';
import { createSkippedModulesWarner, NOTE_MAX_NAMES, NOTE_NAME_LENGTH, readMeta, skippedModulesNote } from './metaReader';

const schemaDir = join(process.cwd(), '..', 'api', 'schema');
const metaSchema = new Ajv2020({ allErrors: true, strict: false }).compile(JSON.parse(readFileSync(join(schemaDir, 'bpm-meta.v1.schema.json'), 'utf8')) as object);

const { rules, processes, unknown } = META_MODULES;

describe('tolerant reading of /meta modules', () => {
  it('CKP_001_an_unknown_module_is_skipped_and_the_known_modules_are_kept', () => {
    const reading = readMeta(metaWithUnknownModule);
    expect(reading).toEqual({ ok: true, meta: { ...metaWithUnknownModule, modules: [rules, processes] }, skipped: [UNKNOWN_MODULE] });
  });

  it('CKP_001_a_body_without_unknown_modules_reads_as_the_strict_schema_with_nothing_skipped', () => {
    expect(readMeta(metaRulesOnly)).toEqual({ ok: true, meta: metaRulesOnly, skipped: [] });
  });

  it('CKP_001_an_unknown_module_is_skipped_whatever_its_shape', () => {
    // The cockpit does not know this module's contract, so it does not judge it.
    const body = { ...metaRulesOnly, modules: [...metaRulesOnly.modules, { module: 'documents', engineVersion: 'next' }] };
    expect(readMeta(body)).toMatchObject({ ok: true, skipped: ['documents'] });
  });

  it.each([
    ['bad engine version', { ...rules, engineVersion: '0.1' }],
    ['feature outside its pattern', { ...processes, features: ['Processes'] }],
    ['no module name', { engineVersion: '0.1.0', apiVersion: 'v1', features: [] }],
    ['module name not a string', { ...rules, module: 7 }],
    ['not an object', 'cases'],
  ])('CKP_001_a_malformed_known_entry_still_fails_validation_%s', (_name, entry) => {
    expect(readMeta({ ...metaWithUnknownModule, modules: [entry, unknown] })).toEqual({ ok: false });
  });

  it('CKP_001_a_host_with_only_unknown_modules_is_still_an_unexpected_response', () => {
    expect(readMeta({ ...metaRulesOnly, modules: [unknown] })).toEqual({ ok: false });
  });

  it('CKP_001_the_strict_rest_of_meta_is_unchanged', () => {
    expect(readMeta({ ...metaWithUnknownModule, schemaVersion: 2 })).toEqual({ ok: false });
    expect(readMeta({ ...metaWithUnknownModule, limits: { maxPageSize: 500 } })).toEqual({ ok: false });
    expect(readMeta({ ...metaWithUnknownModule, modules: 'rules' })).toEqual({ ok: false });
    expect(readMeta(null)).toEqual({ ok: false });
  });

  // The published JSON Schema stays strict; the reader differs from it only by skipping unknown module entries.
  it.each([
    ['known modules only', metaRulesOnly],
    ['with an unknown module', metaWithUnknownModule],
    ['malformed known module', metaWithBadKnownModule],
  ])('CKP_001_contract_reader_agrees_with_the_json_schema_once_unknown_modules_are_removed_%s', (_name, body) => {
    const modules: ReadonlyArray<{ module: string }> = body.modules;
    const withoutUnknown = { ...body, modules: modules.filter((entry) => entry.module !== UNKNOWN_MODULE) };
    expect(readMeta(body).ok).toBe(metaSchema(withoutUnknown));
    expect(metaSchema(metaWithUnknownModule)).toBe(false);
  });
});

describe('skipped module note', () => {
  it('CKP_001_note_names_each_skipped_module_quoted', () => {
    expect(skippedModulesNote(['cases'])).toBe('/api/bpm/meta reports 1 module(s) this cockpit does not know; skipped: "cases"');
  });

  it('CKP_001_note_caps_names_and_length_and_escapes_control_and_bidi_characters', () => {
    const many = Array.from({ length: NOTE_MAX_NAMES + 2 }, (_, i) => `m${i}`);
    expect(skippedModulesNote(many)).toContain(' and 2 more');
    expect(skippedModulesNote(['x'.repeat(NOTE_NAME_LENGTH + 10)])).toContain(`"${'x'.repeat(NOTE_NAME_LENGTH)}…"`);
    expect(skippedModulesNote(['a‮b​c\n'])).toContain('"a\\u202eb\\u200bc\\n"');
  });

  it('CKP_001_warner_notes_each_distinct_set_once_and_nothing_when_none_is_skipped', () => {
    const warn = vi.fn();
    const note = createSkippedModulesWarner(warn);
    note([]);
    note(['cases']);
    note(['cases']);
    note(['documents', 'cases']);
    note(['cases', 'documents']);
    expect(warn).toHaveBeenCalledTimes(2);
  });
});

describe('host client reads /meta tolerantly', () => {
  const json = (body: unknown) => async () => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

  it('CKP_001_get_meta_skips_the_unknown_module_and_reports_it', async () => {
    const skipped = vi.fn();
    const meta = await createHostClient('', json(metaWithUnknownModule), skipped).getMeta();
    expect(meta.modules.map((entry) => entry.module)).toEqual(['rules', 'processes']);
    expect(skipped).toHaveBeenCalledWith([UNKNOWN_MODULE]);
  });

  it('CKP_001_get_meta_with_a_malformed_known_module_is_an_unexpected_response', async () => {
    const skipped = vi.fn();
    const error = await createHostClient('', json(metaWithBadKnownModule), skipped).getMeta().catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(HostCallError);
    expect((error as HostCallError).hostError).toMatchObject({ kind: 'unexpected-response', status: 200 });
    expect(skipped).not.toHaveBeenCalled();
  });
});
