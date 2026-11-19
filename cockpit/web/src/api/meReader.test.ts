import { describe, expect, it } from 'vitest';
import { availableAreas } from '../app/navigation';
import { meLan, metaRulesOnly, UNREADABLE_PERMISSION } from '../mocks/fixtures';
import { droppedPermissionsNote, readMe } from './meReader';

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

  it('CKP_001_the_console_note_names_each_dropped_permission', () => {
    expect(droppedPermissionsNote([UNREADABLE_PERMISSION, 'loans:read'])).toBe(
      `/api/bpm/me lists 2 permission name(s) this cockpit does not read; ignored: ${UNREADABLE_PERMISSION}, loans:read`,
    );
  });
});
