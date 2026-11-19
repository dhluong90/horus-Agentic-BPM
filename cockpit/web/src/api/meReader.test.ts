import { describe, expect, it } from 'vitest';
import { availableAreas } from '../app/navigation';
import { meLan, metaRulesOnly, UNREADABLE_PERMISSION } from '../mocks/fixtures';
import { createDroppedPermissionsWarner, droppedPermissionsNote, NOTE_MAX_NAMES, NOTE_NAME_LENGTH, readMe } from './meReader';

describe('tolerant reading of /api/bpm/me', () => {
  it('CKP_001_me_with_one_ill_formed_permission_still_parses_and_drops_only_that_name', () => {
    const reading = readMe({ ...meLan, permissions: ['rules:read', UNREADABLE_PERMISSION, 'rules:author'] });
    expect(reading).toEqual({ ok: true, me: meLan, dropped: [UNREADABLE_PERMISSION] });
  });

  it('CKP_001_the_other_permissions_keep_working_after_a_name_is_dropped', () => {
    const reading = readMe({ ...meLan, permissions: [UNREADABLE_PERMISSION, 'rules:read'] });
    if (!reading.ok) throw new Error('expected /me to parse');
    expect(reading.me.permissions).toEqual(['rules:read']);
    expect(availableAreas(metaRulesOnly, reading.me)).toEqual(['home', 'rules', 'approvals', 'decisions']);
  });

  it('CKP_001_every_ill_formed_kind_of_name_is_dropped', () => {
    const names = ['loans:read', 'rules', 'Rules:Read', 'rules:a:b:c', ''];
    const reading = readMe({ ...meLan, permissions: ['rules:read', ...names] });
    expect(reading).toMatchObject({ ok: true, dropped: names, me: { permissions: ['rules:read'] } });
  });

  it('CKP_001_well_formed_me_is_returned_unchanged_with_nothing_dropped', () => {
    expect(readMe(meLan)).toEqual({ ok: true, me: meLan, dropped: [] });
  });

  it('CKP_001_a_well_formed_but_unknown_name_is_kept_and_simply_never_checked', () => {
    expect(readMe({ ...meLan, permissions: ['rules:read', 'rules:foresee'] })).toMatchObject({ ok: true, dropped: [], me: { permissions: ['rules:read', 'rules:foresee'] } });
  });

  it.each<[string, unknown]>([
    ['empty user id', { ...meLan, user: { id: '' } }],
    ['no tenant', { schemaVersion: 1, user: { id: 'lan' }, permissions: [] }],
    ['schema version 2', { ...meLan, schemaVersion: 2 }],
    ['duplicate permission', { ...meLan, permissions: ['rules:read', 'rules:read'] }],
    ['permission that is not a string', { ...meLan, permissions: ['rules:read', 7] }],
    ['permissions that are not a list', { ...meLan, permissions: 'rules:read' }],
    ['not an object', 'rules:read'],
  ])('CKP_001_every_other_field_is_still_validated_strictly_%s', (_name, body) => {
    expect(readMe(body)).toEqual({ ok: false });
  });

  it('CKP_001_the_console_note_names_each_dropped_permission_as_a_quoted_literal', () => {
    expect(droppedPermissionsNote([UNREADABLE_PERMISSION, 'loans:read'])).toBe(
      `/api/bpm/me lists 2 permission name(s) this cockpit does not read; ignored: "${UNREADABLE_PERMISSION}", "loans:read"`,
    );
  });

  it('CKP_001_the_console_note_prints_at_most_ten_names_and_counts_the_rest', () => {
    const names = Array.from({ length: 25 }, (_, i) => `loans:n${String(i)}`);
    const note = droppedPermissionsNote(names);
    expect(note).toContain('lists 25 permission name(s)');
    expect(note).toContain('"loans:n9" and 15 more');
    expect(note).not.toContain('loans:n10');
    expect(note.match(/"loans:n/g)).toHaveLength(NOTE_MAX_NAMES);
  });

  it('CKP_001_the_console_note_cuts_each_name_to_64_characters', () => {
    const note = droppedPermissionsNote([`rules:${'x'.repeat(5000)}`]);
    expect(note).toContain(`"rules:${'x'.repeat(NOTE_NAME_LENGTH - 'rules:'.length)}…"`);
    expect(note.length).toBeLessThan(200);
  });

  it('CKP_001_the_console_note_escapes_control_and_bidi_characters', () => {
    const note = droppedPermissionsNote(['rules:\u202Eetirw', 'rules:a\nb\u0085\u200B\u2066', 'rules:\u{E0041}']);
    expect(note).toContain(String.raw`"rules:\u202eetirw"`);
    expect(note).toContain(String.raw`"rules:a\nb\u0085\u200b\u2066"`);
    expect(note).toContain(String.raw`"rules:\udb40\udc41"`);
    expect(note).not.toMatch(/[\p{Cc}\p{Cf}]/u);
  });

  it('CKP_001_the_same_dropped_names_are_warned_once_per_page_load', () => {
    const notes: string[] = [];
    const warn = createDroppedPermissionsWarner((note) => notes.push(note));
    warn([UNREADABLE_PERMISSION]);
    warn([UNREADABLE_PERMISSION]);
    warn([]);
    expect(notes).toHaveLength(1);
    warn(['loans:read', UNREADABLE_PERMISSION]);
    warn([UNREADABLE_PERMISSION, 'loans:read']);
    expect(notes).toHaveLength(2);
  });
});
