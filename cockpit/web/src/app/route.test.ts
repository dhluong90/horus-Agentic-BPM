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

  it('an_unknown_hash_or_a_bad_escape_goes_home_never_a_blank_screen', () => {
    expect(parseRoute('#/settings')).toEqual({ name: 'home' });
    expect(parseRoute('#/rules/%E0%A4%A')).toEqual({ name: 'rule-set', ruleSet: '%E0%A4%A', version: undefined, lens: undefined });
  });

  it('each_route_belongs_to_one_menu_area', () => {
    expect(areaOf({ name: 'rule-set', ruleSet: 'card-fee', version: undefined, lens: undefined })).toBe('rules');
    expect(areaOf({ name: 'rule-sets' })).toBe('rules');
    expect(areaOf({ name: 'decisions' })).toBe('decisions');
  });
});
