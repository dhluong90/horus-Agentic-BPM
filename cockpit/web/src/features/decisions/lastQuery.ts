import type { DecisionQuery } from '../../app/route';

/*
 * The decision search the user last ran in this tab, so "Decisions" in the breadcrumb and the back link of a
 * decision lead to the same list. Memory only: it holds a rule set name, a month or a case reference, never inputs.
 */
let last: DecisionQuery = {};

export function rememberDecisionQuery(query: DecisionQuery): void {
  last = query;
}

export function lastDecisionQuery(): DecisionQuery {
  return last;
}
