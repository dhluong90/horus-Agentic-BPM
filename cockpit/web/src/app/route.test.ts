import { describe, expect, it } from 'vitest';
import { areaOf, formatRoute, parseRoute, type Route } from './route';

describe('routes', () => {
  it('an_empty_hash_opens_the_landing_area', () => {
    expect(parseRoute('', { name: 'approvals' })).toEqual({ name: 'approvals' });
    expect(parseRoute('#/')).toEqual({ name: 'home' });
  });

  it('rule_screens_live_under_rules_and_the_older_rule_sets_prefix_still_parses', () => {
    const route: Route = { name: 'rule-set', ruleSet: 'card-fee', version: 2, lens: 'changes' };
    expect(formatRoute(route)).toBe('#/rules/card-fee/2/changes');
    expect(parseRoute('#/rules/card-fee/2/changes')).toEqual(route);
    expect(parseRoute('#/rule-sets/card-fee/2/changes')).toEqual(route);
    expect(parseRoute('#/rule-sets')).toEqual({ name: 'rule-sets' });
    expect(formatRoute({ name: 'rule-sets' })).toBe('#/rules');
  });

  it('every_area_round_trips', () => {
    for (const name of ['home', 'approvals', 'decisions', 'processes'] as const) {
      expect(parseRoute(formatRoute({ name }))).toEqual({ name });
    }
  });

  it('CKP_071_a_decision_search_and_one_decision_round_trip_through_the_url', () => {
    const search: Route = { name: 'decisions', query: { ruleSet: 'card-fee', month: '2026-11' } };
    expect(formatRoute(search)).toBe('#/decisions?ruleSet=card-fee&month=2026-11');
    expect(parseRoute(formatRoute(search))).toEqual(search);
    const byCase: Route = { name: 'decisions', query: { caseRef: 'LN-2026-0042', month: '2026-11' } };
    expect(parseRoute(formatRoute(byCase))).toEqual(byCase);
    // A month that is not a calendar month is dropped, never sent.
    expect(parseRoute('#/decisions?ruleSet=card-fee&month=2026-13')).toEqual({ name: 'decisions', query: { ruleSet: 'card-fee' } });
    const one: Route = { name: 'decision', decisionId: '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9' };
    expect(formatRoute(one)).toBe('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7T9');
    expect(parseRoute(formatRoute(one))).toEqual(one);
    expect(areaOf(one)).toBe('decisions');
  });

  it('an_unknown_hash_or_a_bad_escape_goes_home_never_a_blank_screen', () => {
    expect(parseRoute('#/settings')).toEqual({ name: 'home' });
    expect(parseRoute('#/rules/%E0%A4%A')).toEqual({ name: 'rule-set', ruleSet: '%E0%A4%A', version: undefined, lens: undefined });
  });

  it('each_route_belongs_to_one_menu_area', () => {
    expect(areaOf({ name: 'rule-set', ruleSet: 'card-fee', version: undefined, lens: undefined })).toBe('rules');
    expect(areaOf({ name: 'rule-sets' })).toBe('rules');
    expect(areaOf({ name: 'decisions' })).toBe('decisions');
  });

  it('CKP_017_a_case_and_the_cases_of_a_process_round_trip_through_the_url', () => {
    const instance: Route = { name: 'instance', instanceId: '01JD4I9NSTANCE0000000000I9' };
    expect(formatRoute(instance)).toBe('#/instances/01JD4I9NSTANCE0000000000I9');
    expect(parseRoute(formatRoute(instance))).toEqual(instance);
    expect(areaOf(instance)).toBe('processes');
    const cases: Route = { name: 'process', key: 'loan-origination', version: 3, lens: 'cases' };
    expect(formatRoute(cases)).toBe('#/processes/loan-origination/3/cases');
    expect(parseRoute(formatRoute(cases))).toEqual(cases);
    expect(parseRoute('#/processes/loan-origination/3/other')).toEqual({ name: 'process', key: 'loan-origination', version: 3 });
  });

  it('CKP_050_the_overdue_filter_is_kept_in_the_link_and_anything_else_is_ignored', () => {
    const late: Route = { name: 'tasks', view: 'all', overdue: true, taskId: '01JD5Q1CRED1TREV1EW0000T01' };
    expect(formatRoute(late)).toBe('#/tasks?view=all&overdue=1&task=01JD5Q1CRED1TREV1EW0000T01');
    expect(parseRoute(formatRoute(late))).toEqual(late);
    expect(parseRoute('#/tasks?view=mine&overdue=yes')).toEqual({ name: 'tasks', view: 'mine' });
  });

  it('CKP_008_the_incident_tab_and_incident_round_trip_and_a_bad_id_is_never_requested', () => {
    const pending: Route = { name: 'incidents', view: 'pending', incidentId: '01JD6N5PR0P0SED0000000N005' };
    expect(formatRoute(pending)).toBe('#/incidents?view=pending&incident=01JD6N5PR0P0SED0000000N005');
    expect(parseRoute(formatRoute(pending))).toEqual(pending);
    expect(areaOf(pending)).toBe('incidents');
    expect(parseRoute('#/incidents?view=closed&incident=../x')).toEqual({ name: 'incidents' });
  });
});
