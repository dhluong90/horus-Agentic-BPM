import { describe, expect, it } from 'vitest';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import { meLan, meMinh, metaRulesOnly } from '../mocks/fixtures';
import { availableAreas, landingArea, landingNote } from './navigation';

const withLandingValue = (value: string): BpmMetaV1 => ({ ...metaRulesOnly, modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [], settings: { 'cockpit.landing': value } }] });

const withPermissions = (permissions: string[]): BpmMeV1 => ({ ...meLan, permissions });
const metaBoth: BpmMetaV1 = {
  ...metaRulesOnly,
  modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }],
};

describe('menu areas (PD-093)', () => {
  it('TR_UI_08_authors_and_approvers_see_home_rules_approvals_and_decisions', () => {
    expect(availableAreas(metaRulesOnly, meLan)).toEqual(['home', 'rules', 'approvals']);
    expect(availableAreas(metaRulesOnly, meMinh)).toEqual(['home', 'rules', 'approvals', 'decisions']);
  });

  it('TR_UI_08_any_rule_permission_opens_the_rule_areas', () => {
    expect(availableAreas(metaRulesOnly, withPermissions(['rules:read']))).toEqual(['home', 'rules', 'approvals']);
    expect(availableAreas(metaRulesOnly, withPermissions(['rules:log:read']))).toEqual(['home', 'rules', 'approvals', 'decisions']);
  });

  it('CKP_071_decisions_shows_only_to_users_who_may_read_the_decision_log', () => {
    for (const permissions of [['rules:read'], ['rules:author'], ['rules:approve'], ['rules:read', 'rules:author', 'rules:approve']]) {
      expect(availableAreas(metaRulesOnly, withPermissions(permissions))).not.toContain('decisions');
    }
    expect(availableAreas(metaRulesOnly, withPermissions(['rules:read', 'rules:log:read']))).toContain('decisions');
  });

  it('TR_UI_08_a_user_with_no_rule_permission_sees_home_only_never_a_dead_link', () => {
    expect(availableAreas(metaRulesOnly, withPermissions(['rules:evaluate']))).toEqual(['home']);
    expect(availableAreas(metaRulesOnly, withPermissions([]))).toEqual(['home']);
  });

  it('TR_UI_08_processes_shows_only_where_a_process_engine_is_registered', () => {
    expect(availableAreas(metaRulesOnly, meLan)).not.toContain('processes');
    expect(availableAreas(metaBoth, meMinh)).toEqual(['home', 'rules', 'approvals', 'decisions', 'processes']);
    expect(availableAreas(metaBoth, withPermissions(['processes:read']))).toEqual(['home', 'processes']);
  });

  it('TR_UI_08_rule_permissions_without_the_rules_module_show_nothing', () => {
    const processesOnly: BpmMetaV1 = { ...metaRulesOnly, modules: [{ module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] };
    expect(availableAreas(processesOnly, withPermissions(['rules:read']))).toEqual(['home']);
  });
});

describe('landing area (PD-092)', () => {
  it('TR_UI_08_a_retired_or_unknown_value_leaves_a_note_for_operators', () => {
    expect(landingNote(withLandingValue('by-role'))).toContain('retired');
    expect(landingNote(withLandingValue('somewhere'))).toContain('not a known value');
    expect(landingNote(withLandingValue('approvals'))).toBeUndefined();
    expect(landingNote(metaRulesOnly)).toBeUndefined();
  });

  const withLanding = (value: string): BpmMetaV1 => ({ ...metaRulesOnly, modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [], settings: { 'cockpit.landing': value } }] });

  it('TR_UI_08_home_for_every_role_when_the_host_sets_no_landing_area', () => {
    for (const me of [meMinh, meLan, withPermissions(['rules:log:read']), withPermissions(['rules:evaluate'])]) {
      expect(landingArea(metaRulesOnly, availableAreas(metaRulesOnly, me))).toBe('home');
    }
  });

  it('TR_UI_08_the_host_may_open_on_rules_or_approvals_for_users_who_may_use_them', () => {
    expect(landingArea(withLanding('approvals'), availableAreas(metaRulesOnly, meMinh))).toBe('approvals');
    expect(landingArea(withLanding('rules'), availableAreas(metaRulesOnly, meLan))).toBe('rules');
    expect(landingArea(withLanding('approvals'), availableAreas(metaRulesOnly, withPermissions(['rules:evaluate'])))).toBe('home');
  });

  it('TR_UI_08_retired_and_future_values_open_home', () => {
    for (const value of ['by-role', 'decisions', 'changes', 'unknown']) {
      expect(landingArea(withLanding(value), availableAreas(metaRulesOnly, meMinh))).toBe('home');
    }
  });
});
