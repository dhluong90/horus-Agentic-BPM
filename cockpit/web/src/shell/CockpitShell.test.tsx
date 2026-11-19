import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
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
    expect(screen.getByTestId('header-host')).toHaveTextContent('Loan origination');
    expect(screen.getByTestId('header-tenant')).toHaveTextContent('bank-a');
    expect(screen.getByRole('heading', { level: 1, name: /Nguyễn Thị Lan/ })).toBeInTheDocument();
    // No backend of its own: the registry, then the host's two metadata endpoints, then only engine endpoints, same origin.
    expect(requested[0]).toMatch(/\/cockpit-hosts\.json$/);
    expect([...requested.slice(1, 3)].sort()).toEqual(['/api/bpm/me', '/api/bpm/meta']);
    await screen.findByRole('link', { name: 'kyc-risk' });
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

  it('TR_UI_01_registry_with_identity_provider_sign_in_is_refused_in_this_release_and_calls_no_host', async () => {
    setScenario('registry-oidc');
    render(<App />);

    expect(await screen.findByText(/not available in this version/)).toBeInTheDocument();
    expect(requested.every((path) => path.endsWith('/cockpit-hosts.json'))).toBe(true);
  });
});

describe('language', () => {
  it('TR_UI_04_language_switch_shows_one_language_at_a_time_and_is_remembered', async () => {
    render(<App />);
    await screen.findByTestId('header-user');

    await userEvent.click(screen.getByRole('button', { name: 'VI' }));

    expect(screen.getByRole('heading', { level: 1, name: /^Chào buổi/ })).toBeInTheDocument();
    expect(screen.getByTestId('header-host')).toHaveTextContent('Khởi tạo khoản vay');
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
