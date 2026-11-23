import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { meLan } from '../mocks/fixtures';
import { mockHost, setScenario } from '../mocks/node';

/** Paths the cockpit asked for, in order. */
let requested: string[] = [];
const record = ({ request }: { request: Request }) => {
  requested.push(new URL(request.url).pathname);
};

beforeEach(() => {
  requested = [];
  mockHost.events.on('request:start', record);
});
afterEach(() => {
  mockHost.events.removeListener('request:start', record);
});

async function header() {
  return screen.findByTestId('session-header');
}

describe('embedded shell, case A', () => {
  it('CKP_001_cockpit_boots_embedded_from_generated_registry_and_shows_host_tenant_and_user', async () => {
    render(<App />);

    expect(await screen.findByTestId('header-user')).toHaveTextContent('Nguyễn Thị Lan');
    expect(screen.getByTestId('header-host')).toHaveTextContent('Retail banking');
    expect(screen.getByTestId('header-tenant')).toHaveTextContent('Sao Mai retail');
    expect(screen.getByRole('heading', { level: 1, name: /Nguyễn Thị Lan/ })).toBeInTheDocument();
    // No backend of its own: the registry, then the host's two metadata endpoints, then only engine endpoints, same origin.
    expect(requested[0]).toMatch(/\/cockpit-hosts\.json$/);
    expect([...requested.slice(1, 3)].sort()).toEqual(['/api/bpm/me', '/api/bpm/meta']);
    await screen.findByRole('link', { name: 'Kyc risk' });
    expect(requested.slice(3).every((path) => path.startsWith('/api/bpm/rules/v1/'))).toBe(true);
  });

  it('TR_UI_08_menu_shows_only_the_areas_the_host_runs_and_the_user_may_use', async () => {
    setScenario('ready-approver');
    render(<App />);

    expect(await screen.findByTestId('header-user')).toHaveTextContent('Trần Văn Minh');
    const menu = screen.getByRole('navigation', { name: 'Cockpit menu' });
    expect(within(menu).getAllByRole('link').map((link) => link.querySelector('.navrail__title')?.textContent)).toEqual(['Home', 'Rules', 'Approvals', 'Decisions']);
    expect(within(menu).getByText('Preview')).toBeInTheDocument();
    expect(within(menu).getByRole('link', { name: 'Home' })).toHaveAttribute('aria-current', 'page');
    // The approver may review lan's card-fee version 2: one count on Approvals.
    expect(await within(menu).findByRole('link', { name: 'Approvals, 1 to review' })).toBeInTheDocument();
  });
});

describe('embedded shell, failure paths', () => {
  it('CKP_001_me_problem_shows_code_and_correlation_id_and_no_partial_header', async () => {
    setScenario('me-forbidden');
    render(<App />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { name: /refused the request/ })).toBeInTheDocument();
    // One plain sentence; the code, the reference and the HTTP status wait behind a closed Details.
    const details = alert.querySelector('details');
    expect(details).not.toBeNull();
    expect(details?.open).toBe(false);
    expect(within(alert).getByText('Details')).toBeInTheDocument();
    expect(screen.getByTestId('error-code')).toHaveTextContent('PERMISSION_DENIED');
    expect(screen.getByTestId('error-correlation-id')).toHaveTextContent('01JD3A7Q2M5N8P1R4S6T9V0W2X');
    const top = await header();
    expect(within(top).queryByTestId('header-host')).not.toBeInTheDocument();
    expect(within(top).queryByTestId('header-tenant')).not.toBeInTheDocument();
    expect(within(top).queryByTestId('header-user')).not.toBeInTheDocument();
  });

  it('CKP_001_meta_problem_shows_code_and_correlation_id_and_no_partial_header', async () => {
    setScenario('meta-internal-error');
    render(<App />);

    expect(await screen.findByTestId('error-code')).toHaveTextContent('INTERNAL_ERROR');
    expect(screen.getByTestId('error-correlation-id')).toHaveTextContent('01JD3B2C4D6E8F0G2H4J6K8M0N');
    expect(screen.queryByTestId('header-user')).not.toBeInTheDocument();
  });

  it('CKP_001_host_without_metadata_endpoints_says_so_instead_of_a_blank_page', async () => {
    setScenario('meta-not-found');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'This service has no cockpit endpoints' })).toBeInTheDocument();
    expect(screen.getByTestId('error-code')).toHaveTextContent('Not provided');
    expect(screen.queryByTestId('header-user')).not.toBeInTheDocument();
  });

  it('CKP_001_redirect_to_the_host_sign_in_offers_sign_in_again', async () => {
    setScenario('signed-out');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Your session has ended' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in again' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('CKP_001_401_problem_while_the_page_was_served_shows_code_and_reference_with_retry_not_sign_in', async () => {
    setScenario('me-unauthenticated');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'The service did not accept your sign-in' })).toBeInTheDocument();
    expect(screen.getByTestId('error-code')).toHaveTextContent('UNAUTHENTICATED');
    expect(screen.getByTestId('error-correlation-id')).toHaveTextContent('01JD3C5D7E9F1G3H5J7K9M1N3P');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in again' })).not.toBeInTheDocument();
  });

  it('CKP_001_response_that_breaks_the_schema_is_an_error_never_a_partial_render', async () => {
    setScenario('me-bad-shape');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'The service sent an unexpected answer' })).toBeInTheDocument();
    expect(screen.queryByTestId('header-user')).not.toBeInTheDocument();
  });

  it('CKP_001_unreachable_host_shows_retry_and_recovers_when_the_host_answers', async () => {
    let down = true;
    mockHost.use(
      http.get('*/api/bpm/me', () => (down ? HttpResponse.error() : undefined)),
    );
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Cannot reach the service' })).toBeInTheDocument();
    down = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('header-user')).toHaveTextContent('Nguyễn Thị Lan');
  });
});

describe('host registry', () => {
  it('CKP_012_invalid_base_url_names_the_json_pointer_and_calls_no_host', async () => {
    setScenario('registry-invalid');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'The cockpit configuration cannot be used' })).toBeInTheDocument();
    expect(screen.getByTestId('config-pointer')).toHaveTextContent('/hosts/0/baseUrl');
    expect(requested.every((path) => path.endsWith('/cockpit-hosts.json'))).toBe(true);
  });

  it('CKP_012_duplicate_host_id_is_named_and_no_host_is_called', async () => {
    setScenario('registry-duplicate-id');
    render(<App />);

    expect(await screen.findByText('Two services use the id loans.')).toBeInTheDocument();
    expect(screen.getByTestId('config-pointer')).toHaveTextContent('/hosts/1/id');
    expect(requested.every((path) => path.endsWith('/cockpit-hosts.json'))).toBe(true);
  });

  it('CKP_013_registry_with_identity_provider_sends_the_browser_to_sign_in_with_pkce_before_any_host_call', async () => {
    setScenario('registry-oidc');
    const navigated: string[] = [];
    const oidc = {
      fetchImpl: (async () => Response.json({
        issuer: 'https://idp.bank.example/realms/operations',
        authorization_endpoint: 'https://idp.bank.example/realms/operations/auth',
        token_endpoint: 'https://idp.bank.example/realms/operations/token',
      })) as typeof fetch,
      pending: new Map<string, string>(),
    };
    render(<App oidc={{
      fetchImpl: oidc.fetchImpl,
      pending: { getItem: (key) => oidc.pending.get(key) ?? null, setItem: (key, value) => { oidc.pending.set(key, value); }, removeItem: (key) => { oidc.pending.delete(key); } },
      navigate: (url) => { navigated.push(url); },
      randomBytes: (length) => new Uint8Array(length).fill(7),
      sha256: async () => new Uint8Array(32).fill(9).buffer,
      now: () => 0,
    }} />);

    expect(await screen.findByText('Signing you in…')).toBeInTheDocument();
    await vi.waitFor(() => { expect(navigated).toHaveLength(1); });
    const url = new URL(navigated[0] ?? '');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('scope')).toBe('openid loans-bpm');
    expect(requested.every((path) => path.endsWith('/cockpit-hosts.json'))).toBe(true);
  });
});

describe('language', () => {
  it('TR_UI_04_language_switch_shows_one_language_at_a_time_and_is_remembered', async () => {
    render(<App />);
    await screen.findByTestId('header-user');

    await userEvent.click(screen.getByRole('button', { name: 'VI' }));

    expect(screen.getByRole('heading', { level: 1, name: /^Chào buổi/ })).toBeInTheDocument();
    expect(screen.getByTestId('header-host')).toHaveTextContent('Ngân hàng bán lẻ');
    expect(screen.getByRole('link', { name: 'Quy tắc' })).toBeInTheDocument();
    expect(screen.queryByText('Home')).not.toBeInTheDocument();
    expect(document.documentElement.lang).toBe('vi');
    expect(screen.getByRole('button', { name: 'VI' })).toHaveAttribute('aria-pressed', 'true');
    expect(window.localStorage.getItem('horus.cockpit.locale')).toBe('vi');
  });

  it('TR_UI_04_registry_default_language_applies_until_the_user_picks_one', async () => {
    mockHost.use(
      http.get('*/cockpit-hosts.json', () =>
        HttpResponse.json({
          schemaVersion: 1,
          auth: { mode: 'host-session' },
          hosts: [{ id: 'loans', label: { en: 'Loan origination', vi: 'Khởi tạo khoản vay' }, baseUrl: '' }],
          ui: { defaultLocale: 'vi' },
        }),
      ),
    );
    render(<App />);

    expect(await screen.findByRole('heading', { level: 1, name: /^Chào buổi/ })).toBeInTheDocument();
  });
});

describe('embedded shell, tenant switcher', () => {
  it('CKP_022_embedded_cockpit_offers_the_tenants_the_session_allows_and_sends_the_chosen_one', async () => {
    const user = userEvent.setup();
    setScenario('embedded-two-tenants');
    const tenants: (string | null)[] = [];
    mockHost.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname === '/api/bpm/me') tenants.push(request.headers.get('X-Tenant-Id'));
    });
    render(<App />);

    await screen.findByTestId('header-user');
    await user.click(await screen.findByTestId('tenant-switcher'));
    const menu = screen.getByRole('list', { name: /tenant/i });
    expect(within(menu).getAllByRole('button').map((item) => item.textContent)).toEqual([expect.stringContaining('Bank A'), expect.stringContaining('Bank B')]);
    await user.click(within(menu).getByRole('button', { name: /Bank B/ }));
    await vi.waitFor(() => { expect(tenants.at(-1)).toBe('bank-b'); });
  });

  it('CKP_022_embedded_cockpit_shows_no_switcher_with_one_allowed_tenant', async () => {
    render(<App />);
    await screen.findByTestId('header-user');
    expect(screen.queryByTestId('tenant-switcher')).not.toBeInTheDocument();
  });

  it('CKP_022_a_development_tenant_the_session_does_not_allow_is_never_offered', async () => {
    const user = userEvent.setup();
    setScenario('embedded-two-tenants');
    // The host also reports a development tenant, but the session's allowed list does not contain it.
    mockHost.use(http.get('*/api/bpm/me', () => HttpResponse.json({
      ...meLan, tenant: { current: 'bank-a', allowed: ['bank-a', 'bank-b'], names: { 'bank-a': 'Bank A', 'bank-b': 'Bank B', dev: 'Dev tenant' } },
    })));
    render(<App />);

    await user.click(await screen.findByTestId('tenant-switcher'));
    const menu = screen.getByRole('list', { name: /tenant/i });
    expect(within(menu).getAllByRole('button')).toHaveLength(2);
    expect(within(menu).queryByRole('button', { name: /dev/i })).not.toBeInTheDocument();
  });
});
