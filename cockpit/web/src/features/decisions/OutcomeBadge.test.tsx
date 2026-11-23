import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider';
import { OutcomeBadge } from './components/OutcomeBadge';
import { isFailedOutcome, knownOutcome } from './DecisionsPage';

function badge(outcome: string, matched: string[]) {
  render(<I18nProvider><OutcomeBadge outcome={knownOutcome(outcome)} matched={matched} /></I18nProvider>);
}

/** The outcomes the Rule Engine API names: MATCHED, NO_MATCH, UNIQUE_HIT_VIOLATION, EVALUATION_ERROR. */
describe('decision outcome as the API names it', () => {
  it('CKP_071_a_matched_decision_reads_as_a_match_never_as_failed', () => {
    badge('MATCHED', ['R1']);
    expect(screen.getByText(/Rule R1 matched/)).toBeInTheDocument();
    expect(screen.queryByText(/Failed/)).not.toBeInTheDocument();
    expect(isFailedOutcome(knownOutcome('MATCHED'))).toBe(false);
  });

  it('CKP_071_no_match_reads_as_no_rule_matched_never_as_failed', () => {
    badge('NO_MATCH', []);
    expect(screen.getByText(/No rule matched/)).toBeInTheDocument();
    expect(screen.queryByText(/Failed/)).not.toBeInTheDocument();
    expect(isFailedOutcome(knownOutcome('NO_MATCH'))).toBe(false);
  });

  it('CKP_071_only_an_evaluation_error_or_an_unknown_outcome_reads_as_failed', () => {
    badge('EVALUATION_ERROR', []);
    expect(screen.getByText(/Failed/)).toBeInTheDocument();
    expect(isFailedOutcome(knownOutcome('EVALUATION_ERROR'))).toBe(true);
    expect(isFailedOutcome(knownOutcome('UNIQUE_HIT_VIOLATION'))).toBe(true);
    expect(isFailedOutcome(knownOutcome('SOMETHING_NEW'))).toBe(true);
  });
});
