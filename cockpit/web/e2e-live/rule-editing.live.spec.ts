import { expect, test, type Browser, type Page } from '@playwright/test';

/*
 * The whole rule editing journey against the real Rule Engine in the example host, two people, four eyes:
 * Lan (author) imports a rule set and requests publication; Minh (approver) approves it; Lan edits a new
 * draft in the cockpit, sees the engine's errors on their cells, fixes them, saves and requests publication; Minh
 * rejects with a reason, Lan reads it in the history and asks again, Minh reads the changes and approves; Lan tries the published rules when the host grants her rules:evaluate.
 * Screenshots go to e2e/screenshots/live/.
 */

const PASSWORD = process.env['LIVE_PASSWORD'] ?? 'demo-pass';
const SHOTS = 'e2e/screenshots/live';
const NAME = `card-fee-${Date.now().toString(36)}`;

const DOCUMENT = `schemaVersion: 1
ruleSet: ${NAME}
description: Annual card fee by tier and balance
hitPolicy: first
inputs:
  - { name: tier, type: string, allowed: [CLASSIC, GOLD, PLATINUM] }
  - { name: balance, type: money }
  - { name: txCount, type: integer }
outputs:
  - { name: fee, type: money }
  - { name: waived, type: boolean }
rules:
  - id: R1
    when: { tier: "== 'PLATINUM'" }
    then: { fee: "0 VND", waived: true }
  - id: R2
    when: { tier: "== 'GOLD'", balance: ">= 50000000 VND" }
    then: { fee: "0 VND", waived: true }
  - id: R3
    when: { tier: "== 'GOLD'" }
    then: { fee: "30000 VND", waived: false }
  - id: R4
    when: { txCount: ">= 20" }
    then: { fee: "15000 VND", waived: false }
  - id: R5
    when: {}
    then: { fee: "50000 VND", waived: false }
`;

async function signIn(browser: Browser, user: string, colorScheme: 'light' | 'dark' = 'light'): Promise<Page> {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme, reducedMotion: 'reduce' });
  await context.addInitScript(() => {
    window.localStorage.setItem('horus.cockpit.locale', 'en');
  });
  const page = await context.newPage();
  await page.goto('/login');
  await page.fill('input[name=username]', user);
  await page.fill('input[name=password]', PASSWORD);
  await page.click('button[type=submit]');
  return page;
}

async function openCockpit(page: Page, hash: string) {
  await page.goto(`/bpm/cockpit/${hash}`);
  await expect(page.getByTestId('header-user')).toBeVisible();
}

async function aboveFold(page: Page, name: string | RegExp) {
  const box = await page.getByRole('button', { name }).first().boundingBox();
  expect(box, `${String(name)} is on screen`).not.toBeNull();
  expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(900);
}

const rowHeads = (page: Page) => page.locator('table.dt tbody th[scope=row] .row-id, table.dt tbody th[scope=row] input');

test('CKP_030_live_json_files_import_on_the_list_and_on_the_rule_set_page', async ({ browser }) => {
  const lan = await signIn(browser, 'lan');
  await openCockpit(lan, '#/rules');
  const name = `fx-margin-${Date.now().toString(36)}`;
  const doc = (fee: string) => JSON.stringify({ schemaVersion: 1, ruleSet: name, hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }],
    outputs: [{ name: 'fee', type: 'money' }], rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee } }] }, null, 2);
  const pick = async (button: string, fee: string) => {
    // The list's Import file button; the rule set page imports from its More menu (below).
    const chooser = lan.waitForEvent('filechooser');
    await lan.getByRole('button', { name: button }).click();
    await (await chooser).setFiles({ name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(doc(fee)) });
  };
  await pick('Import file', '0 VND');
  await expect(lan.locator('.rs-head__sub')).toHaveText(/^Version 1 · draft/);
  await lan.getByRole('button', { name: 'More' }).click();
  const chooser = lan.waitForEvent('filechooser');
  await lan.getByRole('menuitem', { name: 'Import new version' }).click();
  await (await chooser).setFiles({ name: `${name}.json`, mimeType: 'application/json', buffer: Buffer.from(doc('5000 VND')) });
  await expect(lan.locator('.rs-head__sub')).toHaveText(/^Version 2 · draft/);
  await expect(lan.getByText('Draft version 2 created')).toBeVisible();
  await expect(lan.getByText('5,000 VND')).toBeVisible();
});

test('CKP_032_live_author_edits_a_draft_and_an_approver_publishes_it', async ({ browser }) => {
  test.setTimeout(120_000);
  const lan = await signIn(browser, 'lan');
  await openCockpit(lan, '#/rules');

  // Import a file: one POST with an Idempotency-Key, then the new draft opens.
  const chooser = lan.waitForEvent('filechooser');
  await lan.getByRole('button', { name: 'Import file' }).click();
  await (await chooser).setFiles({ name: `${NAME}.yaml`, mimeType: 'application/yaml', buffer: Buffer.from(DOCUMENT) });
  await expect(lan.locator('.rs-head__sub')).toHaveText(/^Version 1 · draft/);
  await expect(lan.getByText('50,000,000 VND')).toBeVisible();
  await lan.screenshot({ path: `${SHOTS}/01-version-table-draft-1440x900-light-en.png` });

  // Request publication; the requester sees Approve disabled with the reason.
  await lan.getByRole('button', { name: 'Request publish' }).click();
  await expect(lan.getByText('Version 1 is waiting for approval.')).toBeVisible();
  await expect(lan.getByRole('button', { name: 'Approve and publish' })).toBeDisabled();
  await expect(lan.getByText('Approve is unavailable. You requested this change. Another approver must decide.')).toBeVisible();
  await aboveFold(lan, 'Approve and publish');
  await lan.screenshot({ path: `${SHOTS}/02-requester-approve-disabled-1440x900-light-en.png` });

  // The approver opens the same link and approves.
  const minh = await signIn(browser, 'minh');
  await openCockpit(minh, `#/rules/${NAME}/1`);
  await expect(minh.getByText('First version: every row is new.')).toBeVisible();
  await minh.getByLabel('Comment').fill('Fees match the tariff');
  await aboveFold(minh, 'Approve and publish');
  await minh.getByRole('button', { name: 'Approve and publish' }).click();
  await expect(minh.getByText('Version 1 is published.')).toBeVisible();

  // The author starts a new draft from the published version and edits it.
  await lan.reload();
  await expect(lan.getByRole('heading', { level: 1 })).toContainText('Published');
  await lan.getByRole('button', { name: 'New draft' }).click();
  // Cells edit as values: tier picker, comparison and amount, money with VND inside, Yes or No.
  await lan.getByRole('button', { name: 'Row R2, Tier: Gold' }).click();
  await lan.getByRole('checkbox', { name: 'Platinum' }).check();
  await lan.keyboard.press('Escape');
  await lan.getByRole('button', { name: 'Add row' }).click();
  for (let i = 0; i < 3; i += 1) {
    await lan.getByRole('button', { name: 'Actions for row R6' }).click();
    await lan.getByRole('menuitem', { name: 'Move row R6 up' }).click();
  }
  await lan.getByRole('button', { name: 'Row R6, Tier', exact: true }).click();
  await lan.getByRole('checkbox', { name: 'Gold' }).check();
  await lan.keyboard.press('Escape');
  // A comparison without an amount: the engine refuses it.
  await lan.getByRole('button', { name: /^Row R6, Balance: any value/ }).click();
  await lan.getByLabel('Row R6, Fee').fill('10000');
  await lan.getByRole('group', { name: 'Row R6, Waived' }).getByRole('button', { name: 'No' }).click();
  await aboveFold(lan, 'Save draft');
  await lan.getByRole('button', { name: 'Save draft' }).click();

  // The engine's 422 lands on its cell; the other edits stay.
  const balance = lan.getByLabel('Row R6, Balance', { exact: true });
  await expect(balance).toHaveAttribute('aria-invalid', 'true');
  await expect(lan.getByText('Enter a value.')).toBeVisible();
  await expect(lan.getByRole('button', { name: 'Row R2, Tier: Gold, Platinum' })).toBeVisible();
  await expect(lan.getByTestId('problem-code')).toHaveText('VALIDATION_FAILED');
  await lan.screenshot({ path: `${SHOTS}/03-editor-error-on-cell-1440x900-light-en.png` });

  await balance.fill('20000000');
  await lan.getByRole('button', { name: 'Save draft' }).click();
  await expect(lan.locator('.rs-head__sub')).toHaveText(/^Version 2 · draft/);
  await expect(lan.getByText('Saved as draft version 2')).toBeVisible();
  await expect(rowHeads(lan)).toHaveText(['R1', 'R2', 'R6', 'R3', 'R4', 'R5']);
  await lan.getByRole('button', { name: 'Request publish' }).click();
  await expect(lan.getByText('Version 2 is waiting for approval.')).toBeVisible();

  // The approver sends it back with a reason; the author sees it in the history and asks again.
  await openCockpit(minh, `#/rules/${NAME}/2`);
  await minh.getByLabel('Comment').fill('R6 needs the pricing committee minutes');
  await minh.getByRole('button', { name: 'Reject' }).click();
  await expect(minh.getByText('Version 2 is back with its author.')).toBeVisible();
  await lan.reload();
  await expect(lan.getByRole('heading', { level: 1 })).toContainText('Draft');
  await lan.getByRole('tab', { name: 'History' }).click();
  await expect(lan.getByText('Rejected by minh')).toBeVisible();
  await expect(lan.getByText('R6 needs the pricing committee minutes')).toBeVisible();
  await lan.screenshot({ path: `${SHOTS}/04a-author-sees-rejection-1440x900-light-en.png` });
  await lan.getByRole('button', { name: 'Request publish' }).click();
  await expect(lan.getByText('Version 2 is waiting for approval.')).toBeVisible();

  // The approver reads the changes with the editor's labels and formats, then approves.
  await minh.reload();
  await expect(minh.getByText('Compared with Version 1, in effect now')).toBeVisible();
  await expect(minh.getByText('1 added, 1 changed, 0 removed, 4 unchanged')).toBeVisible();
  await minh.screenshot({ path: `${SHOTS}/04-approver-changes-1440x900-light-en.png` });
  await minh.setViewportSize({ width: 1440, height: 1200 });
  await minh.screenshot({ path: `${SHOTS}/04-approver-changes-1440x1200-light-en.png` });
  await minh.getByRole('button', { name: 'Approve and publish' }).click();
  await expect(minh.getByText('Version 2 is published.')).toBeVisible();

  // Try it needs rules:evaluate. A host that does not grant it to the author shows the panel's unavailable
  // state; the test checks that state and records the skip instead of failing.
  await lan.reload();
  const me = (await (await lan.request.get('/api/bpm/me')).json()) as { permissions: string[] };
  await lan.getByRole('tab', { name: 'Try it' }).click();
  if (!me.permissions.includes('rules:evaluate')) {
    await expect(lan.getByText('You cannot run rules on this service.')).toBeVisible();
    const note = 'Try it skipped: this host does not grant rules:evaluate to the demo author (lan).';
    test.info().annotations.push({ type: 'skipped-step', description: note });
    console.log(note);
    return;
  }
  await lan.getByLabel('Tier', { exact: true }).selectOption('GOLD');
  await lan.getByLabel('Balance', { exact: true }).fill('30000000');
  await expect(lan.getByLabel('Balance', { exact: true })).toHaveValue('30,000,000');
  await lan.getByRole('button', { name: 'Run' }).click();
  await expect(lan.getByText('Row R6 matched')).toBeVisible();
  await expect(lan.locator('.try__result').getByText('10,000 VND')).toBeVisible();
  await expect(lan.locator('tr.dt__row--matched .row-id')).toHaveText('R6');
  await lan.screenshot({ path: `${SHOTS}/05-try-it-matched-1440x900-light-en.png` });
});
