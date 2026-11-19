import { describe, expect, it } from 'vitest';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import { meLan, meMinh, metaRulesOnly } from '../mocks/fixtures';
import { availableAreas, landingArea } from './navigation';

const withPermissions = (permissions: string[]): BpmMeV1 => ({ ...meLan, permissions });
const metaBoth: BpmMetaV1 = {
  ...metaRulesOnly,
  modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }],
};

describe('menu areas', () => {
  it('TR_UI_08_an_author_sees_home_rules_and_approvals_for_their_own_requests', () => {
    expect(availableAreas(metaRulesOnly, meLan)).toEqual(['home', 'rules', 'approvals']);
  });

  it('TR_UI_08_an_approver_with_the_log_permission_also_sees_decisions', () => {
    expect(availableAreas(metaRulesOnly, meMinh)).toEqual(['home', 'rules', 'approvals', 'decisions']);
  });

  it('TR_UI_08_a_caller_who_only_evaluates_sees_home_only_never_a_dead_link', () => {
    expect(availableAreas(metaRulesOnly, withPermissions(['rules:evaluate']))).toEqual(['home']);
  });

  it('TR_UI_08_reading_rules_without_author_or_approve_gives_no_approvals', () => {
    expect(availableAreas(metaRulesOnly, withPermissions(['rules:read']))).toEqual(['home', 'rules']);
  });

  it('TR_UI_08_processes_needs_the_module_in_meta_and_the_permission_in_me', () => {
    expect(availableAreas(metaRulesOnly, withPermissions(['processes:read']))).toEqual(['home']);
    expect(availableAreas(metaBoth, withPermissions(['processes:read']))).toEqual(['home', 'processes']);
  });

  it('TR_UI_08_a_permission_without_its_module_shows_nothing', () => {
    const processesOnly: BpmMetaV1 = { ...metaRulesOnly, modules: [{ module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] };
    expect(availableAreas(processesOnly, meMinh)).toEqual(['home']);
  });
});

describe('landing area (PD-078, CKP-031)', () => {
  const withLanding = (value: string): BpmMetaV1 => ({ ...metaRulesOnly, modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [], settings: { 'cockpit.landing': value } }] });
  const areas = (me: BpmMeV1, meta: BpmMetaV1) => availableAreas(meta, me);

  it('TR_UI_08_home_when_the_host_sets_no_landing_area', () => {
    expect(landingArea(metaRulesOnly, meMinh, areas(meMinh, metaRulesOnly))).toBe('home');
  });

  it('TR_UI_08_by_role_sends_approvers_and_authors_to_approvals_auditors_to_decisions_readers_to_rules', () => {
    const meta = withLanding('by-role');
    const auditor = withPermissions(['rules:log:read']);
    const reader = withPermissions(['rules:read']);
    expect(landingArea(meta, meMinh, areas(meMinh, meta))).toBe('approvals');
    expect(landingArea(meta, meLan, areas(meLan, meta))).toBe('approvals');
    expect(landingArea(meta, auditor, areas(auditor, meta))).toBe('decisions');
    expect(landingArea(meta, reader, areas(reader, meta))).toBe('rules');
  });

  it('TR_UI_08_a_fixed_landing_area_applies_to_everyone_who_may_use_it_and_others_get_home', () => {
    const meta = withLanding('decisions');
    expect(landingArea(meta, meMinh, areas(meMinh, meta))).toBe('decisions');
    expect(landingArea(meta, meLan, areas(meLan, meta))).toBe('home');
    expect(landingArea(withLanding('changes'), meLan, areas(meLan, metaRulesOnly))).toBe('approvals');
    expect(landingArea(withLanding('unknown'), meLan, areas(meLan, metaRulesOnly))).toBe('home');
  });
});
