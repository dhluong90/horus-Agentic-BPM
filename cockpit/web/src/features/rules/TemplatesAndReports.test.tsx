import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { demoSeed, draftSeed, type FakeRequest } from '../../mocks/ruleEngine';

/** The rule engine calls the screens made: method, path and body. */
let sent: { method: string; path: string; body: string }[] = [];

beforeEach(() => {
  sent = [];
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.startsWith('/api/bpm/rules') && request.method === 'POST') void request.clone().text().then((body) => { sent.push({ method: request.method, path: path.replace('/api/bpm/rules/v1', ''), body }); });
  });
  return () => { mockHost.events.removeAllListeners(); };
});

function open(hash: string, scenario: Scenario, locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const REQUIRED = { required: ['card-fee'] } as const;

function asUser(user: string, permissions: string[], method: string, path: string, body = ''): FakeRequest {
  return { method, path: `/api/bpm/rules/v1${path}`, headers: { 'idempotency-key': `${user}-${path}-${body.length}` }, body, user, permissions };
}

describe('create a rule set from a template (PD-112)', () => {
  it('CKP_083_an_author_picks_a_template_names_it_and_lands_in_its_draft', async () => {
    resetRuleEngine(demoSeed());
    const user = userEvent.setup();
    open('#/rules', 'rules-author-templates');
    await user.click(await screen.findByRole('button', { name: 'New rule set' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('radio', { name: /Fee by tier/ }));
    await user.type(within(dialog).getByLabelText('Name'), 'Monthly card fee');
    expect(within(dialog).getByLabelText('Identifier')).toHaveValue('monthly-card-fee');
    await user.click(within(dialog).getByRole('button', { name: 'Create draft' }));
    await waitFor(() => { expect(window.location.hash).toBe('#/rules/monthly-card-fee/1/edit'); });
    const created = sent.find((call) => call.path === '/rule-sets');
    const document = JSON.parse(created?.body ?? '{}') as { ruleSet: string; displayName: string; rules: unknown[] };
    expect([document.ruleSet, document.displayName, document.rules.length]).toEqual(['monthly-card-fee', 'Monthly card fee', 4]);
  });

  it('CKP_083_a_name_already_used_is_shown_at_the_name_and_nothing_is_created', async () => {
    const engine = resetRuleEngine(demoSeed());
    const before = engine.store.length;
    const user = userEvent.setup();
    open('#/rules', 'rules-author-templates');
    await user.click(await screen.findByRole('button', { name: 'New rule set' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('radio', { name: /Blank/ }));
    await user.type(within(dialog).getByLabelText('Name'), 'Card fee');
    await user.click(within(dialog).getByRole('button', { name: 'Create draft' }));
    expect(await within(dialog).findByText('A rule set named card-fee already exists. Choose another identifier.')).toBeInTheDocument();
    expect(engine.store.length).toBe(before);
    expect(window.location.hash).toBe('#/rules');
  });

  it('CKP_083_a_user_without_author_permission_has_no_new_rule_set_button', async () => {
    open('#/rules', 'rules-approver-report');
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'New rule set' })).not.toBeInTheDocument();
  });
});

describe('simulation report (PD-115)', () => {
  it('CKP_109_the_engine_refuses_a_request_without_a_report_when_the_host_requires_one', () => {
    const engine = resetRuleEngine(draftSeed(), {}, {}, REQUIRED);
    const refused = engine.handle(asUser('lan', ['rules:author'], 'POST', '/rule-sets/card-fee/versions/2:request-publish'));
    expect([refused.status, (JSON.parse(refused.body) as { code: string }).code]).toEqual([409, 'SIMULATION_REPORT_REQUIRED']);
    expect(engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2)?.status).toBe('DRAFT');
  });

  it('CKP_109_the_author_attaches_a_report_before_requesting_publish', async () => {
    const engine = resetRuleEngine(draftSeed(), {}, {}, REQUIRED);
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-author-templates');
    await user.click(await screen.findByRole('button', { name: 'Request publish' }));
    const dialog = await screen.findByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: 'Request publish' });
    expect(submit).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: 'Run a new report' }));
    await waitFor(() => { expect(within(dialog).getByRole('button', { name: 'Request publish' })).toBeEnabled(); });
    await user.click(within(dialog).getByRole('button', { name: 'Request publish' }));
    await waitFor(() => { expect(engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2)?.status).toBe('PENDING_APPROVAL'); });
    const request = sent.find((call) => call.path.endsWith(':request-publish'));
    expect(JSON.parse(request?.body ?? '{}')).toEqual({ simulationReportId: expect.stringMatching(/^01KB/) as string });
  });

  it('CKP_109_the_approver_reads_changed_outcomes_before_and_after_and_approval_stays_bound_to_the_reviewed_hash', async () => {
    const engine = resetRuleEngine(demoSeed(), {}, {}, { ...REQUIRED, demoReport: true });
    const user = userEvent.setup();
    open('#/rules/card-fee/2/report', 'rules-approver-report');
    expect(await screen.findByRole('heading', { name: /Simulation report/ })).toBeInTheDocument();
    expect(await screen.findByText('outcomes change')).toBeInTheDocument();
    expect(await screen.findAllByText(/Result: fee is 50,000 VND/)).not.toHaveLength(0);
    await user.click(await screen.findByRole('button', { name: /Approve/ }));
    await waitFor(() => { expect(engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2)?.status).toBe('PUBLISHED'); });
    const approve = sent.find((call) => call.path.endsWith(':approve'));
    expect((JSON.parse(approve?.body ?? '{}') as { expectedCanonicalHash: string }).expectedCanonicalHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('CKP_109_a_report_older_than_the_last_edit_is_marked_out_of_date', () => {
    const engine = resetRuleEngine(draftSeed(), {}, {}, REQUIRED);
    const made = engine.handle(asUser('lan', ['rules:author', 'rules:simulate'], 'POST', '/rule-sets/card-fee/versions/2/simulation-reports', JSON.stringify({ source: 'REPLAY', from: '2026-11-01T00:00:00Z', to: '2026-11-20T00:00:00Z', limit: 100 })));
    const report = JSON.parse(made.body) as { id: string; status: string };
    expect(report.status).toBe('CURRENT');
    const draft = engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2);
    if (draft) draft.definition = { ...draft.definition, rules: draft.definition.rules.slice(1) };
    const read = engine.handle(asUser('minh', ['rules:read'], 'GET', `/rule-sets/card-fee/versions/2/simulation-reports/${report.id}`));
    expect((JSON.parse(read.body) as { status: string }).status).toBe('OUT_OF_DATE');
    const attach = engine.handle(asUser('lan', ['rules:author'], 'POST', '/rule-sets/card-fee/versions/2:request-publish', JSON.stringify({ simulationReportId: report.id })));
    expect([attach.status, (JSON.parse(attach.body) as { code: string }).code]).toEqual([409, 'SIMULATION_REPORT_OUT_OF_DATE']);
  });

  it('CKP_109_the_report_reads_in_vietnamese', async () => {
    resetRuleEngine(demoSeed(), {}, {}, { ...REQUIRED, demoReport: true });
    open('#/rules/card-fee/2/report', 'rules-approver-report', 'vi');
    expect(await screen.findByRole('heading', { name: /Báo cáo mô phỏng/ })).toBeInTheDocument();
    expect(await screen.findByText('kết quả thay đổi')).toBeInTheDocument();
  });
});
