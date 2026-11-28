import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../i18n/I18nProvider';
import { EnvironmentTag, environmentLabel } from './EnvironmentTag';

describe('environment tag', () => {
  it('CKP_015_the_environment_reads_in_one_form_everywhere', () => {
    expect(environmentLabel('production', 'en')).toBe('Production');
    expect(environmentLabel('Production', 'vi')).toBe('Production');
    expect(environmentLabel('uat', 'en')).toBe('Uat');
  });

  it('CKP_015_amber_is_kept_for_the_demo_tag_and_production_is_neutral', () => {
    render(<I18nProvider><EnvironmentTag environment="production" /><EnvironmentTag environment="demo" /></I18nProvider>);
    const [production, demo] = screen.getAllByTestId('environment-tag');
    expect(production).toHaveTextContent('Production');
    expect(production).toHaveClass('env');
    expect(production).not.toHaveClass('env--demo');
    expect(demo).toHaveTextContent('Demo');
    expect(demo).toHaveClass('env--demo');
  });
});
