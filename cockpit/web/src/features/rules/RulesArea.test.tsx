import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { parseRoute } from '../../app/route';
import { I18nProvider } from '../../i18n/I18nProvider';
import { meLan } from '../../mocks/fixtures';
import { formatRulesPath, parseRulesPath } from './routes';
import { RulesArea } from './RulesArea';

describe('rules area routing', () => {
  it('CKP_030_sub_paths_round_trip_and_old_links_still_open', () => {
    for (const path of ['/', '/card-fee', '/card-fee/2', '/card-fee/2/changes']) expect(formatRulesPath(parseRulesPath(path))).toBe(path);
    expect(parseRoute('#/rule-sets/card-fee/1')).toEqual({ name: 'rules', path: '/card-fee/1' });
    expect(parseRoute('#/rules')).toEqual({ name: 'rules', path: '/' });
    expect(parseRoute('#/service')).toEqual({ name: 'service' });
  });

  it('CKP_030_the_area_mounts_under_a_path_router_at_rules', async () => {
    const navigate = vi.fn();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <I18nProvider initialLocale="en">
          <RulesArea path="/" navigate={navigate} href={(p) => `/rules${p === '/' ? '' : p}`} baseUrl="" hostId="loans" me={{ ...meLan, permissions: [...meLan.permissions] }} cockpitDrafts />
        </I18nProvider>
      </QueryClientProvider>,
    );
    const link = await within(await screen.findByRole('table')).findByRole('link', { name: 'card-fee' });
    expect(link).toHaveAttribute('href', '/rules/card-fee');
    await userEvent.setup().click(link);
    expect(navigate).not.toHaveBeenCalled(); // a plain link: the host router decides how to follow it
  });
});
