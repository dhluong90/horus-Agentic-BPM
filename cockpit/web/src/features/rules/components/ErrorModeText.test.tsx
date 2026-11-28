import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ErrorModeText } from './ErrorModeText';

function show(mode: string | undefined, explain = false) {
  return render(<I18nProvider><ErrorModeText mode={mode} explain={explain} /></I18nProvider>);
}

describe('error mode in words (RLE-236)', () => {
  it('CKP_121_strict_reads_as_Strict_with_its_explanation_on_hover', () => {
    show('strict');
    expect(screen.getByText('Strict').closest('.error-mode')).toHaveAttribute('title', 'An error in an expression fails the decision.');
  });

  it('CKP_121_the_detail_view_writes_the_explanation_out', () => {
    show('strict', true);
    expect(screen.getByText('An error in an expression fails the decision.')).toBeInTheDocument();
  });

  it('CKP_121_a_historical_decision_reads_as_unknown_never_as_the_code', () => {
    show('unknown');
    expect(screen.getByText('Unknown (recorded before modes)')).toBeInTheDocument();
    expect(screen.queryByText('unknown')).not.toBeInTheDocument();
  });

  it('CKP_121_a_mode_from_a_newer_engine_never_shows_its_code', () => {
    show('lenient-v9');
    expect(screen.getByText('Newer mode')).toBeInTheDocument();
    expect(screen.queryByText(/lenient/)).not.toBeInTheDocument();
  });

  it('CKP_121_no_mode_shows_nothing', () => {
    const { container } = show(undefined);
    expect(container).toBeEmptyDOMElement();
  });
});
