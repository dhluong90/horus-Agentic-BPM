import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { meLan, metaRulesOnly } from '../../mocks/fixtures';
import { mockHost, setScenario } from '../../mocks/node';

const EMPTY_PAGE = { data: [], nextCursor: null, hasNext: false };

/** card-fee v2 as the engine lists it to its requester once withdraw (RLE-046) is offered. */
const pendingForRequester = {
  ruleSet: 'card-fee', version: 2, status: 'PENDING_APPROVAL', hitPolicy: 'FIRST', effectiveFrom: null, lockVersion: 1,
  canonicalHash: 'h', requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: null, approvedAt: null,
  createdBy: 'lan', createdAt: '2026-11-18T08:00:00Z', allowedActions: ['withdraw'],
};
const cardFeeSummary = (status: string) => ({ ruleSet: 'card-fee', latestVersion: 2, latestStatus: status, publishedVersion: 1, lastChangedAt: '2026-11-18T08:30:00Z' });

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

const card = (name: RegExp) => screen.findByRole('article', { name });

describe('Home (PD-092: content by role)', () => {
  it('TR_UI_02_the_requester_sees_their_own_request_waiting_and_no_approve', async () => {
    open('');

    const pending = await card(/Your drafts and requests/);
    expect(await within(pending).findByText('card-fee · Version 2')).toBeInTheDocument();
    expect(within(pending).getByText(/waits for an approver/)).toBeInTheDocument();
    expect(within(pending).queryByRole('link', { name: /^Review/ })).not.toBeInTheDocument();
  });

  it('TR_UI_02_an_approver_gets_one_focal_review_card_and_the_list_does_not_repeat_it', async () => {
    setScenario('ready-approver');
    open('');

    const focus = await card(/card-fee · Version 2/);
    expect(within(focus).getByText('Waiting for your review')).toBeInTheDocument();
    expect(within(focus).getByRole('link', { name: 'Review' })).toHaveAttribute('href', '#/rules/card-fee/2/changes');
    const pending = await card(/Pending approvals/);
    expect(within(pending).getByText('Nothing else is waiting for your review.')).toBeInTheDocument();
    expect(within(pending).queryByText('card-fee · Version 2')).not.toBeInTheDocument();
  });

  it('TR_UI_02_the_focal_request_is_the_oldest_and_the_others_are_rows_that_open_on_click', async () => {
    setScenario('ready-approver');
    const version = (ruleSet: string, requestedAt: string) => ({ ...pendingForRequester, ruleSet, requestedAt, requestedBy: 'hoa', createdBy: 'hoa', allowedActions: ['approve', 'reject'] });
    mockHost.use(
      http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json({ data: [{ ...cardFeeSummary('PENDING_APPROVAL') }, { ...cardFeeSummary('PENDING_APPROVAL'), ruleSet: 'fx-margin' }], nextCursor: null, hasNext: false })),
      http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions', () => HttpResponse.json({ data: [version('card-fee', '2026-11-18T08:30:00Z')], nextCursor: null, hasNext: false })),
      http.get('*/api/bpm/rules/v1/rule-sets/fx-margin/versions', () => HttpResponse.json({ data: [version('fx-margin', '2026-11-19T01:40:00Z')], nextCursor: null, hasNext: false })),
    );
    open('');

    expect(await card(/card-fee · Version 2/)).toHaveTextContent('Waiting for your review');
    const pending = await card(/Pending approvals/);
    const row = within(pending).getByRole('link', { name: 'fx-margin · Version 2' });
    expect(row).toHaveAttribute('href', '#/rules/fx-margin/2/changes');
    expect(within(pending).queryByRole('link', { name: /^Review/ })).not.toBeInTheDocument();
  });

  it('TR_UI_08_people_show_by_display_name_when_the_engine_sends_one_and_by_login_otherwise', async () => {
    setScenario('ready-approver');
    mockHost.use(
      http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions', () =>
        HttpResponse.json({ data: [{ ...pendingForRequester, allowedActions: ['approve', 'reject'], requestedByName: 'Nguyễn Thị Lan' }], nextCursor: null, hasNext: false }),
      ),
    );
    open('');

    const focus = await card(/card-fee · Version 2/);
    expect(within(focus).getByText(/by Nguyễn Thị Lan/)).toBeInTheDocument();
    expect(within(focus).queryByText(/by lan$/)).not.toBeInTheDocument();
  });

  it('TR_UI_08_recent_rule_sets_are_newest_first_and_rows_open_the_rule_set', async () => {
    open('');

    const recent = await card(/Recent rule sets/);
    await within(recent).findByRole('link', { name: 'card-fee' });
    const links = within(recent).getAllByRole('link').filter((link) => link.getAttribute('href')?.startsWith('#/rules/'));
    expect(links.map((link) => link.textContent)).toEqual(['card-fee', 'kyc-risk']);
    expect(within(recent).getAllByText('Version 2').length).toBeGreaterThan(0);
  });

  it('empty_lists_show_one_line_and_the_next_action', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json(EMPTY_PAGE)));
    open('');

    const pending = await card(/Your drafts and requests/);
    expect(await within(pending).findByText('You have no drafts or requests.')).toBeInTheDocument();
    expect(within(pending).getByRole('link', { name: 'Open rule sets' })).toHaveAttribute('href', '#/rules');
    expect(within(await card(/Recent rule sets/)).getByText('No rule sets on this service yet.')).toBeInTheDocument();
  });

  it('a_failed_list_says_so_in_one_sentence_keeps_the_code_behind_details_and_recovers', async () => {
    let failing = true;
    mockHost.use(
      http.get('*/api/bpm/rules/v1/rule-sets', () =>
        failing
          ? HttpResponse.json({ title: 'Internal error', status: 500, code: 'INTERNAL_ERROR', correlationId: '01JQZ' }, { status: 500, headers: { 'Content-Type': 'application/problem+json' } })
          : undefined,
      ),
    );
    open('');

    const recent = await card(/Recent rule sets/);
    const alert = await within(recent).findByRole('alert');
    expect(within(alert).getByText('The service could not answer. Try again.')).toBeInTheDocument();
    expect(within(alert).getByText('Details')).toBeInTheDocument();
    expect(alert).toHaveTextContent('INTERNAL_ERROR');
    failing = false;
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await within(recent).findByRole('link', { name: 'card-fee' })).toBeInTheDocument();
  });

  it('TR_UI_08_an_engine_without_the_list_endpoint_says_so_instead_of_a_failure', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json({ title: 'Method Not Allowed', status: 405 }, { status: 405, headers: { 'Content-Type': 'application/problem+json' } })));
    open('');

    const recent = await card(/Recent rule sets/);
    expect(await within(recent).findByText("This service's engine does not offer this list yet.")).toBeInTheDocument();
    expect(within(recent).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('TR_UI_08_a_user_with_no_rule_permission_sees_one_line_home_only_and_no_rule_calls', async () => {
    const calls: string[] = [];
    const record = ({ request }: { request: Request }) => { calls.push(new URL(request.url).pathname); };
    mockHost.events.on('request:start', record);
    mockHost.use(http.get('*/api/bpm/me', () => HttpResponse.json({ ...meLan, user: { id: 'tuan', displayName: 'Vũ Anh Tuấn' }, permissions: ['rules:evaluate'], roles: ['TELLER'] })));
    open('');

    expect(await screen.findByText('There is nothing for your role on this service yet.')).toBeInTheDocument();
    const menu = screen.getByRole('navigation', { name: 'Cockpit menu' });
    expect(within(menu).getAllByRole('link').map((link) => link.querySelector('.navrail__title')?.textContent)).toEqual(['Home']);
    expect(calls.some((path) => path.startsWith('/api/bpm/rules/'))).toBe(false);
    mockHost.events.removeListener('request:start', record);
  });
});

describe('Approvals', () => {
  it('TR_UI_02_an_author_follows_their_request_and_gets_no_withdraw_until_the_engine_offers_it', async () => {
    open('#/approvals');

    expect(await screen.findByRole('heading', { level: 1, name: 'Approvals' })).toBeInTheDocument();
    const preview = await card(/card-fee · Version 2/);
    expect(within(preview).getByText('Waits for another approver.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Review next' })).not.toBeInTheDocument();
  });

  it('TR_UI_02_an_approver_gets_review_next_and_the_full_review_link', async () => {
    setScenario('ready-approver');
    open('#/approvals');

    expect(await screen.findByRole('link', { name: 'Review next' })).toHaveAttribute('href', '#/rules/card-fee/2/changes');
    const preview = await card(/card-fee · Version 2/);
    expect(within(preview).getByRole('link', { name: /Open full review/ })).toHaveAttribute('href', '#/rules/card-fee/2/changes');
    expect(within(preview).getByText('replaces Version 1')).toBeInTheDocument();
  });

  it('TR_UI_02_the_requester_withdraws_with_an_optional_reason_and_the_request_goes_after_the_engine_confirms', async () => {
    let pending = true;
    const sent: { ifMatch: string | null; key: string | null; body: unknown }[] = [];
    mockHost.use(
      http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions', () =>
        HttpResponse.json({ data: pending ? [pendingForRequester] : [{ ...pendingForRequester, status: 'DRAFT', requestedBy: null, allowedActions: ['request-publish'] }], nextCursor: null, hasNext: false }),
      ),
      http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json({ data: [cardFeeSummary(pending ? 'PENDING_APPROVAL' : 'DRAFT')], nextCursor: null, hasNext: false })),
      http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2:withdraw', async ({ request }) => {
        sent.push({ ifMatch: request.headers.get('If-Match'), key: request.headers.get('Idempotency-Key'), body: await request.json() });
        pending = false;
        return HttpResponse.json({ ...pendingForRequester, status: 'DRAFT', lockVersion: 2, sourceFormat: 'YAML', sourceHash: 's', definition: { schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [], outputs: [], rules: [] } });
      }),
    );
    open('#/approvals');

    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw' }));
    const dialog = screen.getByRole('dialog', { name: 'Withdraw card-fee version 2?' });
    await userEvent.type(within(dialog).getByLabelText('Reason (optional)'), 'Wrong fee for GOLD');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Withdraw' }));

    expect(await screen.findByText('You have no requests waiting.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ ifMatch: '"1"', body: { reason: 'Wrong fee for GOLD' } });
    expect(sent[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('Rules', () => {
  it('TR_UI_08_authors_get_import_rule_set_as_the_primary_action', async () => {
    open('#/rules');
    expect(await screen.findByRole('button', { name: 'Import rule set' })).toBeInTheDocument();
  });

  it('TR_UI_08_approvers_have_no_import', async () => {
    setScenario('ready-approver');
    open('#/rules');
    await screen.findByRole('link', { name: 'card-fee' });
    expect(screen.queryByRole('button', { name: 'Import rule set' })).not.toBeInTheDocument();
  });

  it('the_status_filter_and_the_search_narrow_the_list', async () => {
    open('#/rules');
    await screen.findByRole('link', { name: 'kyc-risk' });

    await userEvent.click(screen.getByRole('button', { name: /^Waiting/ }));
    expect(screen.queryByRole('link', { name: 'kyc-risk' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'card-fee' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /^All/ }));
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find a rule set' }), 'kyc');
    expect(screen.queryByRole('link', { name: 'card-fee' })).not.toBeInTheDocument();
  });
});

describe('menu', () => {
  it('TR_UI_08_a_link_to_an_area_the_user_cannot_use_says_so_and_offers_home', async () => {
    mockHost.use(http.get('*/api/bpm/me', () => HttpResponse.json({ ...meLan, permissions: ['rules:evaluate'] })));
    open('#/rules');

    expect(await screen.findByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '#/home');
  });

  it('TR_UI_08_the_host_landing_area_from_meta_opens_when_no_screen_is_linked', async () => {
    mockHost.use(http.get('*/api/bpm/meta', () => HttpResponse.json({ ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], settings: { 'cockpit.landing': 'approvals' } }] })));
    open('');

    expect(await screen.findByRole('heading', { level: 1, name: 'Approvals' })).toBeInTheDocument();
  });

  it('TR_UI_03_the_menu_collapses_to_icons_keeps_its_names_and_remembers_the_choice', async () => {
    open('');
    const menu = await screen.findByRole('navigation', { name: 'Cockpit menu' });

    await userEvent.click(within(menu).getByRole('button', { name: 'Collapse menu' }));

    expect(within(menu).getByRole('button', { name: 'Expand menu' })).toHaveAttribute('aria-expanded', 'false');
    expect(within(menu).getByRole('link', { name: 'Rules' })).toHaveAttribute('data-tip', 'Rules');
    expect(within(menu).getByRole('link', { name: 'Home' })).toHaveAttribute('data-tip', 'Home');
    expect(window.localStorage.getItem('horus.cockpit.menu')).toBe('collapsed');
  });

  it('TR_UI_08_decisions_and_processes_sit_under_one_preview_heading_and_pages_use_the_empty_state', async () => {
    mockHost.use(http.get('*/api/bpm/meta', () => HttpResponse.json({ ...metaRulesOnly, modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] })));
    open('#/processes');

    expect(await screen.findByRole('heading', { level: 1, name: 'Processes' })).toBeInTheDocument();
    // Lan holds no processes permission: the page says so in one line instead of an empty list.
    expect(screen.getByText(/Your permissions show no process definitions here/)).toBeInTheDocument();
    expect(screen.queryByText(/is installed on this service/)).not.toBeInTheDocument();
    // Lan may not read the decision log: Decisions is not in her menu, and Processes stands alone under Preview.
    const menu = screen.getByRole('navigation', { name: 'Cockpit menu' });
    expect(within(menu).queryByRole('link', { name: /Decisions/ })).not.toBeInTheDocument();
    expect(within(menu).getByRole('link', { name: /Processes/ })).toBeInTheDocument();
  });
});

describe('security (review lows)', () => {
  it('TR_SEC_09_a_refused_sign_out_says_so_and_keeps_the_page', async () => {
    mockHost.use(http.post('*/logout', () => HttpResponse.json({ status: 403 }, { status: 403 })));
    open('');
    await userEvent.click(await screen.findByRole('button', { name: /Nguyễn Thị Lan, account menu/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not sign out. You are still signed in; try again.');
    expect(screen.getByRole('heading', { level: 1, name: /Nguyễn Thị Lan/ })).toBeInTheDocument();
  });

  it('TR_SEC_09_decisions_makes_no_request_for_decision_data', async () => {
    setScenario('ready-approver');
    const calls: string[] = [];
    const record = ({ request }: { request: Request }) => { calls.push(new URL(request.url).pathname); };
    mockHost.events.on('request:start', record);
    open('#/decisions');

    expect(await screen.findByText('Choose a rule set or a case reference, and a month.')).toBeInTheDocument();
    // Nothing is searched before the user asks. Only the shell's own calls: registry, /meta, /me and the rule lists behind the Approvals count.
    expect(calls.filter((path) => /decision/i.test(path))).toEqual([]);
    expect(calls.every((path) => path.endsWith('/cockpit-hosts.json') || path === '/api/bpm/meta' || path === '/api/bpm/me' || path.startsWith('/api/bpm/rules/v1/rule-sets'))).toBe(true);
    mockHost.events.removeListener('request:start', record);
  });
});
