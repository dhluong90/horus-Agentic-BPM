import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createQueryClient } from '../../App';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { FederationProvider, type FederatedHost } from '../../app/federation/federation';
import { useRoute } from '../../app/route';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ENGINE_VERSION } from '../../mocks/engineVersion';
import { HostScope } from './HostScope';
import { MergedInboxPage } from './MergedInboxPage';
import { MergedIncidentsPage } from './MergedIncidentsPage';

/*
 * The merged inbox and incident list against hosts that answer from a script (CKP-019, CKP-020): what each host was
 * asked, with which headers, and what the screen shows when a host is slow, down, unchanged or has no ETag.
 */

const ULID = (suffix: string) => `01JD7M${suffix.padEnd(20, '0')}`.toUpperCase();
const TASKS = { l1: ULID('L1'), l2: ULID('L2'), l3: ULID('L3'), k1: ULID('K1'), k2: ULID('K2') };
const INCIDENTS = { n3: ULID('N3'), n9: ULID('N9') };

interface Asked { method: string; url: URL; headers: Record<string, string> }

type Answer = (asked: Asked) => Response | Promise<Response>;

const problem = (status: number, code: string) => Response.json({ title: code, status, code, correlationId: '01JD7M0000000000000000COR1' }, { status, headers: { 'Content-Type': 'application/problem+json' } });

function fakeHost(id: string, label: string, answer: Answer, permissions = ['processes:task:work', 'processes:read', 'processes:incident:manage']): { host: FederatedHost; asked: Asked[] } {
  const asked: Asked[] = [];
  const fetchImpl = ((input: string, init: RequestInit) => {
    const entry = { method: init.method ?? 'GET', url: new URL(input), headers: Object.fromEntries(Object.entries(init.headers as Record<string, string>).map(([name, value]) => [name.toLowerCase(), value])) };
    asked.push(entry);
    return Promise.resolve(answer(entry));
  }) as unknown as typeof fetch;
  const me: BpmMeV1 = { schemaVersion: 1, user: { id: 'tuan', displayName: 'Tuan' }, tenant: { current: 'bank-a', allowed: ['bank-a'] }, permissions };
  const meta = { schemaVersion: 1, hostId: id, modules: [{ module: 'processes', engineVersion: ENGINE_VERSION, apiVersion: 'v1', features: [], settings: {} }], tenancy: { switching: false }, limits: { maxPageSize: 200 } } as BpmMetaV1;
  return { host: { state: 'ready', id, label, baseUrl: `https://${id}.test`, me, meta, fetchImpl }, asked };
}

const down: Answer = () => { throw new TypeError('network'); };

const taskOf = (id: string, name: string, dueAt: string) => ({
  id, instanceId: ULID(`I${id.slice(6, 8)}`), definitionKey: 'loan', definitionVersion: 1, businessKey: `LN-${id.slice(6, 8)}`, nodeId: 'review', name, assignee: 'role:credit-officer',
  status: 'OPEN', lockVersion: 0, claimedBy: null, createdAt: '2027-01-01T00:00:00Z', dueAt, overdueBy: null,
});
const listOf = (data: unknown[], nextCursor: string | null = null, etag?: string) => Response.json({ data, nextCursor, hasNext: nextCursor !== null }, etag === undefined ? {} : { headers: { ETag: etag } });

function Harness({ hosts, refreshSeconds = 3600 }: { hosts: FederatedHost[]; refreshSeconds?: number }) {
  const [route] = useRoute();
  const current = hosts.find((host) => host.state === 'ready');
  if (current?.state !== 'ready') return null;
  return (
    <FederationProvider value={{ currentId: current.id, hosts, timeoutMs: 5_000 }}>
      <HostScope host={current}>
        {route.name === 'incidents'
          ? <MergedIncidentsPage view={route.view} incidentId={route.incidentId} on={route.on} refreshSeconds={refreshSeconds} />
          : <MergedInboxPage taskId={route.name === 'tasks' ? route.taskId : undefined} on={route.name === 'tasks' ? route.on : undefined} refreshSeconds={refreshSeconds} />}
      </HostScope>
    </FederationProvider>
  );
}

function open(hash: string, hosts: FederatedHost[], refreshSeconds?: number) {
  window.location.hash = hash;
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider><Harness hosts={hosts} {...(refreshSeconds === undefined ? {} : { refreshSeconds })} /></I18nProvider>
    </QueryClientProvider>,
  );
}

const rowNames = () => within(screen.getByRole('table')).getAllByRole('rowheader').map((cell) => cell.textContent);
const tasksAsked = (asked: Asked[]) => asked.filter((entry) => entry.url.pathname.endsWith('/tasks'));

describe('CKP-019 one task inbox across hosts', () => {
  it('CKP_019_merged_inbox_exact_order_partial_banner_and_304', async () => {
    const loans = fakeHost('loans', 'Loan origination', (asked) => {
      if (!asked.url.pathname.endsWith('/tasks')) return problem(404, 'NOT_FOUND');
      return asked.url.searchParams.get('cursor') === 'c1'
        ? listOf([taskOf(TASKS.l3, 'Late loan check', '2027-01-08T00:00:00Z')])
        : listOf([taskOf(TASKS.l1, 'Credit review', '2027-01-02T00:00:00Z'), taskOf(TASKS.l2, 'Collateral check', '2027-01-04T00:00:00Z')], 'c1');
    });
    const kyc = fakeHost('kyc', 'KYC onboarding', (asked) => (asked.url.pathname.endsWith('/tasks') ? listOf([taskOf(TASKS.k1, 'Verify documents', '2027-01-03T00:00:00Z'), taskOf(TASKS.k2, 'Screen sanctions', '2027-01-06T00:00:00Z')]) : problem(404, 'NOT_FOUND')));
    const cards = fakeHost('cards', 'Cards', down);
    open('#/hosts/loans/tasks?scope=all', [loans.host, kyc.host, cards.host]);

    // Items up to the loans frontier (day 4) in due order, each labelled by host; kyc's day 6 waits behind the frontier.
    await waitFor(() => { expect(rowNames()).toEqual(['Credit reviewCase LN-L1', 'Verify documentsCase LN-K1', 'Collateral checkCase LN-L2']); });
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    expect(within(rows[0] as HTMLElement).getByText('Loan origination')).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText('KYC onboarding')).toBeInTheDocument();
    // The failed host is named and the list says it is incomplete; the others stay.
    expect(await screen.findByText('Cards unreachable, list incomplete.')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/01JD7M|role:/);

    // Load more reads only the host at the frontier.
    const kycBefore = tasksAsked(kyc.asked).length;
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await waitFor(() => { expect(rowNames()).toHaveLength(5); });
    expect(rowNames()).toEqual(['Credit reviewCase LN-L1', 'Verify documentsCase LN-K1', 'Collateral checkCase LN-L2', 'Screen sanctionsCase LN-K2', 'Late loan checkCase LN-L3']);
    expect(tasksAsked(loans.asked).at(-1)?.url.searchParams.get('cursor')).toBe('c1');
    expect(tasksAsked(kyc.asked)).toHaveLength(kycBefore);
  });

  it('CKP_019_refresh_sends_if_none_match_when_a_host_gave_an_etag_and_a_304_leaves_the_list', async () => {
    const loans = fakeHost('loans', 'Loan origination', (asked) => {
      if (!asked.url.pathname.endsWith('/tasks')) return problem(404, 'NOT_FOUND');
      return asked.headers['if-none-match'] === '"v1"' ? new Response(null, { status: 304, headers: { ETag: '"v1"' } }) : listOf([taskOf(TASKS.l1, 'Credit review', '2027-01-02T00:00:00Z')], null, '"v1"');
    });
    const kyc = fakeHost('kyc', 'KYC onboarding', (asked) => (asked.url.pathname.endsWith('/tasks') ? listOf([taskOf(TASKS.k1, 'Verify documents', '2027-01-03T00:00:00Z')], null, '"k1"') : problem(404, 'NOT_FOUND')));
    open('#/hosts/loans/tasks?scope=all', [loans.host, kyc.host], 0.05);

    await waitFor(() => { expect(tasksAsked(loans.asked).some((entry) => entry.headers['if-none-match'] === '"v1"')).toBe(true); });
    expect(tasksAsked(loans.asked)[0]?.headers['if-none-match']).toBeUndefined();
    expect(rowNames()).toEqual(['Credit reviewCase LN-L1', 'Verify documentsCase LN-K1']);
    expect(screen.queryByText(/unreachable/)).not.toBeInTheDocument();
  });

  it('CKP_019_refresh_without_an_etag_is_a_plain_read_and_shows_what_changed', async () => {
    let count = 1;
    const loans = fakeHost('loans', 'Loan origination', (asked) => {
      if (!asked.url.pathname.endsWith('/tasks')) return problem(404, 'NOT_FOUND');
      const items = [taskOf(TASKS.l1, 'Credit review', '2027-01-02T00:00:00Z'), ...(count > 1 ? [taskOf(TASKS.l2, 'Collateral check', '2027-01-04T00:00:00Z')] : [])];
      count += 1;
      return listOf(items);
    });
    const kyc = fakeHost('kyc', 'KYC onboarding', (asked) => (asked.url.pathname.endsWith('/tasks') ? listOf([]) : problem(404, 'NOT_FOUND')));
    open('#/hosts/loans/tasks?scope=all', [loans.host, kyc.host], 0.05);

    await waitFor(() => { expect(rowNames()).toEqual(['Credit reviewCase LN-L1', 'Collateral checkCase LN-L2']); });
    expect(tasksAsked(loans.asked).length).toBeGreaterThan(1);
    expect(tasksAsked(loans.asked).every((entry) => entry.headers['if-none-match'] === undefined)).toBe(true);
  });

  it('CKP_019_a_host_without_the_permission_is_left_out_with_no_notice', async () => {
    const loans = fakeHost('loans', 'Loan origination', (asked) => (asked.url.pathname.endsWith('/tasks') ? listOf([taskOf(TASKS.l1, 'Credit review', '2027-01-02T00:00:00Z')]) : problem(404, 'NOT_FOUND')));
    const reader = fakeHost('audit', 'Audit', () => problem(500, 'INTERNAL_ERROR'), ['processes:read']);
    open('#/hosts/loans/tasks?scope=all', [loans.host, reader.host]);
    await waitFor(() => { expect(rowNames()).toEqual(['Credit reviewCase LN-L1']); });
    expect(reader.asked).toHaveLength(0);
    expect(screen.queryByText(/unreachable/)).not.toBeInTheDocument();
  });

  it('CKP_019_when_every_host_fails_the_list_says_none_could_be_reached', async () => {
    const loans = fakeHost('loans', 'Loan origination', down);
    const kyc = fakeHost('kyc', 'KYC onboarding', down);
    open('#/hosts/loans/tasks?scope=all', [loans.host, kyc.host]);
    expect(await screen.findByText('No service could be reached.')).toBeInTheDocument();
    expect(screen.getByText('Loan origination, KYC onboarding unreachable, list incomplete.')).toBeInTheDocument();
  });
});

const incidentOf = (id: string, nodeId: string, errorCode: string, createdAt: string) => ({
  id, instanceId: ULID('INST'), nodeId, tokenId: null, errorCode, causeSummary: errorCode, retryCount: 1, status: 'OPEN', lockVersion: 4, retrying: false,
  createdAt, resolvedAt: null, resolution: null, resolvedBy: null, pendingResolution: null, allowedActions: ['retry', 'resolve'],
});

describe('CKP-020 one incident list across hosts', () => {
  it('CKP_020_merged_incident_list_routes_actions_to_host', async () => {
    const n3 = incidentOf(INCIDENTS.n3, 'assess-documents', 'AGENT_TIMEOUT', '2027-01-01T10:00:00Z');
    const n9 = incidentOf(INCIDENTS.n9, 'screen-sanctions', 'SERVICE_ERROR', '2027-01-01T10:05:00Z');
    let retrying = false;
    const loans = fakeHost('loans', 'Loan origination', (asked) => {
      if (asked.method === 'POST') { retrying = true; return Response.json({ ...n3, retrying: true, allowedActions: [] }); }
      const now = { ...n3, retrying, allowedActions: retrying ? [] : n3.allowedActions };
      if (asked.url.pathname.endsWith('/incidents')) return listOf([now]);
      if (asked.url.pathname.endsWith(`/incidents/${INCIDENTS.n3}`)) return Response.json(now);
      return problem(404, 'NOT_FOUND');
    });
    const kyc = fakeHost('kyc', 'KYC onboarding', (asked) => {
      if (asked.url.pathname.endsWith('/incidents')) return listOf([n9]);
      if (asked.url.pathname.endsWith(`/incidents/${INCIDENTS.n9}`)) return Response.json(n9);
      return problem(404, 'NOT_FOUND');
    });
    open(`#/hosts/loans/incidents?scope=all&on=loans&incident=${INCIDENTS.n3}`, [loans.host, kyc.host]);

    // N9 then N3, each with its host.
    await waitFor(() => { expect(rowNames()).toEqual(['Screen sanctions', 'Assess documents']); });
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    expect(within(rows[0] as HTMLElement).getByText('KYC onboarding')).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText('Loan origination')).toBeInTheDocument();

    const selected = screen.getByRole('complementary', { name: 'Assess documents' });
    await userEvent.click(await within(selected).findByRole('button', { name: 'Retry' }));
    const dialog = await screen.findByRole('dialog', { name: 'Retry Assess documents' });
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Agent back after maintenance');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText(/Retry scheduled for Assess documents/)).toBeInTheDocument();
    const posts = (entries: Asked[]) => entries.filter((entry) => entry.method === 'POST');
    expect(posts(loans.asked)).toHaveLength(1);
    expect(posts(loans.asked)[0]?.url.href).toBe(`https://loans.test/api/bpm/processes/v1/incidents/${INCIDENTS.n3}:retry`);
    expect(posts(loans.asked)[0]?.headers['if-match']).toBe('"4"');
    expect(posts(loans.asked)[0]?.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(posts(kyc.asked)).toHaveLength(0);
    // The owning host's list is read again after the command; the row shows the retry.
    expect(await within(screen.getByRole('table')).findByText('Retrying')).toBeInTheDocument();
  });

  it('CKP_020_an_incident_of_a_host_that_fails_is_left_out_and_named_while_the_other_stays', async () => {
    const n3 = incidentOf(INCIDENTS.n3, 'assess-documents', 'AGENT_TIMEOUT', '2027-01-01T10:00:00Z');
    const loans = fakeHost('loans', 'Loan origination', (asked) => (asked.url.pathname.endsWith('/incidents') ? listOf([n3]) : Response.json(n3)));
    const kyc = fakeHost('kyc', 'KYC onboarding', () => problem(503, 'SERVICE_UNAVAILABLE'));
    open('#/hosts/loans/incidents?scope=all', [loans.host, kyc.host]);
    await waitFor(() => { expect(rowNames()).toEqual(['Assess documents']); });
    expect(await screen.findByText('KYC onboarding unreachable, list incomplete.')).toBeInTheDocument();
  });

  it('CKP_020_four_eyes_stays_the_hosts_rule_a_proposer_cannot_approve_their_own_result', async () => {
    const pending = { ...incidentOf(INCIDENTS.n3, 'verify-docs', 'SERVICE_ERROR', '2027-01-01T10:00:00Z'), status: 'RESOLUTION_PENDING', allowedActions: [], pendingResolution: { proposedBy: 'tuan', proposedAt: '2027-01-01T11:00:00Z', reason: 'Checked', output: { ok: true }, approvalMode: 'four-eyes' } };
    const loans = fakeHost('loans', 'Loan origination', (asked) => (asked.url.pathname.endsWith('/incidents') ? listOf([pending]) : Response.json(pending)));
    open(`#/hosts/loans/incidents?scope=all&view=pending&on=loans&incident=${INCIDENTS.n3}`, [loans.host]);
    const selected = await screen.findByRole('complementary', { name: 'Verify docs' });
    const approve = await within(selected).findByRole('button', { name: 'Approve result' });
    expect(approve).toHaveAttribute('aria-disabled', 'true');
    await act(async () => { await userEvent.click(approve); });
    expect(loans.asked.filter((entry) => entry.method === 'POST')).toHaveLength(0);
  });
});
