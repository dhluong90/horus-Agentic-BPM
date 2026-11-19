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

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

async function card(name: RegExp) {
  return screen.findByRole('article', { name });
}

describe('Home', () => {
  it('TR_UI_02_the_requester_sees_their_own_pending_request_without_approve', async () => {
    open('');

    expect(await screen.findByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();
    const approvals = await card(/Waiting for approval/);
    expect(await within(approvals).findByText('Your request')).toBeInTheDocument();
    expect(within(approvals).getByText('Waits for another approver')).toBeInTheDocument();
    expect(within(approvals).queryByRole('link', { name: /^Review/ })).not.toBeInTheDocument();
    // Nothing to review, so the primary action opens the rule sets.
    expect(screen.getByRole('link', { name: 'Open rule sets' })).toHaveAttribute('href', '#/rules');
  });

  it('TR_UI_02_an_approver_gets_review_on_someone_elses_request_and_it_opens_the_changes', async () => {
    setScenario('ready-approver');
    open('');

    const approvals = await card(/Waiting for approval/);
    const review = await within(approvals).findByRole('link', { name: 'Review: card-fee version 2' });
    expect(review).toHaveAttribute('href', '#/rules/card-fee/2/changes');
    expect(within(approvals).getByText('Requested by lan')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review 1 waiting' })).toHaveAttribute('href', '#/approvals');
  });

  it('TR_UI_08_recent_rule_sets_are_newest_first_with_the_count_from_the_engine', async () => {
    open('');

    const recent = await card(/Recently changed rule sets/);
    await within(recent).findByRole('link', { name: 'card-fee' });
    expect(within(recent).getByRole('heading', { name: /Recently changed rule sets/ })).toHaveTextContent('2');
    expect(within(recent).getAllByRole('listitem').map((row) => within(row).getAllByRole('link')[0]?.textContent)).toEqual(['card-fee', 'kyc-risk']);
  });

  it('empty_lists_show_one_line_and_the_next_action', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json(EMPTY_PAGE)));
    open('');

    const approvals = await card(/Waiting for approval/);
    expect(await within(approvals).findByText('Nothing is waiting for approval.')).toBeInTheDocument();
    expect(within(approvals).getByRole('link', { name: 'Open rule sets' })).toHaveAttribute('href', '#/rules');
    const recent = await card(/Recently changed rule sets/);
    expect(within(recent).getByText('No rule sets on this service yet.')).toBeInTheDocument();
  });

  it('a_failed_list_shows_the_code_and_reference_and_recovers_on_try_again', async () => {
    let failing = true;
    mockHost.use(
      http.get('*/api/bpm/rules/v1/rule-sets', () =>
        failing
          ? HttpResponse.json({ title: 'Internal error', status: 500, code: 'INTERNAL_ERROR', correlationId: '01JQZ' }, { status: 500, headers: { 'Content-Type': 'application/problem+json' } })
          : undefined,
      ),
    );
    open('');

    const recent = await card(/Recently changed rule sets/);
    const alert = await within(recent).findByRole('alert');
    expect(alert).toHaveTextContent('INTERNAL_ERROR');
    expect(alert).toHaveTextContent('01JQZ');
    failing = false;
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await within(recent).findByRole('link', { name: 'card-fee' })).toBeInTheDocument();
  });
});

describe('older engine', () => {
  it('TR_UI_08_an_engine_without_the_list_endpoint_says_so_instead_of_a_generic_failure', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json({ title: 'Method Not Allowed', status: 405 }, { status: 405, headers: { 'Content-Type': 'application/problem+json' } })));
    open('');

    const recent = await card(/Recently changed rule sets/);
    expect(await within(recent).findByText("This service's engine does not offer this list yet.")).toBeInTheDocument();
    expect(within(recent).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(recent).queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });
});

describe('Approvals', () => {
  it('TR_UI_02_without_withdraw_from_the_engine_the_requester_gets_no_action_button', async () => {
    open('#/approvals');

    expect(await screen.findByRole('heading', { level: 1, name: 'Approvals' })).toBeInTheDocument();
    expect(await screen.findByText('Your request')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Withdraw/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Review/ })).not.toBeInTheDocument();
  });

  it('TR_UI_02_the_requester_withdraws_with_an_optional_reason_and_the_row_goes_after_the_engine_confirms', async () => {
    let pending = true;
    const sent: { path: string; ifMatch: string | null; key: string | null; body: unknown }[] = [];
    mockHost.use(
      http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions', () =>
        HttpResponse.json({ data: pending ? [pendingForRequester] : [{ ...pendingForRequester, status: 'DRAFT', requestedBy: null, allowedActions: ['request-publish'] }], nextCursor: null, hasNext: false }),
      ),
      http.get('*/api/bpm/rules/v1/rule-sets', () =>
        HttpResponse.json({ data: [{ ruleSet: 'card-fee', latestVersion: 2, latestStatus: pending ? 'PENDING_APPROVAL' : 'DRAFT', publishedVersion: 1, lastChangedAt: '2026-11-18T08:30:00Z' }], nextCursor: null, hasNext: false }),
      ),
      http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2:withdraw', async ({ request }) => {
        sent.push({ path: new URL(request.url).pathname, ifMatch: request.headers.get('If-Match'), key: request.headers.get('Idempotency-Key'), body: await request.json() });
        pending = false;
        return HttpResponse.json({ ...pendingForRequester, status: 'DRAFT', lockVersion: 2, sourceFormat: 'YAML', sourceHash: 's', definition: { schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [], outputs: [], rules: [] } });
      }),
    );
    open('#/approvals');

    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw: card-fee version 2' }));
    const dialog = screen.getByRole('dialog', { name: 'Withdraw card-fee version 2?' });
    await userEvent.type(within(dialog).getByLabelText('Reason (optional)'), 'Wrong fee for GOLD');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Withdraw' }));

    expect(await screen.findByText('Nothing is waiting for approval.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Request for card-fee version 2 withdrawn');
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ ifMatch: '"1"', body: { reason: 'Wrong fee for GOLD' } });
    expect(sent[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('menu', () => {
  it('TR_UI_08_a_link_to_an_area_the_user_cannot_use_says_so_and_offers_home', async () => {
    open('#/decisions');

    expect(await screen.findByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '#/home');
    expect(screen.queryByRole('link', { name: 'Decisions · Preview' })).not.toBeInTheDocument();
  });

  it('TR_UI_08_the_host_landing_area_from_meta_opens_when_no_screen_is_linked', async () => {
    mockHost.use(http.get('*/api/bpm/meta', () => HttpResponse.json({ ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], settings: { 'cockpit.landing': 'changes' } }] })));
    open('');

    expect(await screen.findByRole('heading', { level: 1, name: 'Approvals' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Approvals' })).toHaveAttribute('aria-current', 'page');
  });

  it('TR_UI_03_the_menu_collapses_to_icons_keeps_its_names_and_remembers_the_choice', async () => {
    open('');
    const menu = await screen.findByRole('navigation', { name: 'Cockpit menu' });

    await userEvent.click(within(menu).getByRole('button', { name: 'Collapse menu' }));

    expect(within(menu).getByRole('button', { name: 'Expand menu' })).toHaveAttribute('aria-expanded', 'false');
    expect(within(menu).getByRole('link', { name: 'Rules' })).toBeInTheDocument();
    expect(window.localStorage.getItem('horus.cockpit.menu')).toBe('collapsed');
  });

  it('TR_UI_08_decisions_and_processes_are_marked_preview_in_plain_text', async () => {
    mockHost.use(
      http.get('*/api/bpm/meta', () => HttpResponse.json({ ...metaRulesOnly, modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] })),
      http.get('*/api/bpm/me', () => HttpResponse.json({ ...meLan, permissions: ['rules:read', 'rules:log:read', 'processes:read'] })),
    );
    open('#/processes');

    expect(await screen.findByRole('heading', { level: 1, name: 'Processes Preview' })).toBeInTheDocument();
    expect(screen.getByText('Process definitions, instances and tasks arrive in a later release.')).toBeInTheDocument();
    expect(screen.getByText('No process is deployed on this service yet.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('link', { name: 'Decisions · Preview' }));
    expect(await screen.findByText('Decision history comes in 0.2.0.')).toBeInTheDocument();
  });
});
