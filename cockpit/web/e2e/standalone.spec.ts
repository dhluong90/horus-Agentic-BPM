import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { meHa } from '../src/mocks/fixtures';
import { rememberLocale } from './mockHost';
import { audienceOf, mockStandalone, standaloneRegistry } from './standaloneHosts';

/*
 * The standalone cockpit (#11): one sign-in with PKCE, one token per host kept in memory, the host id in the
 * address, one host at a time, the version check and a per-host error (CKP-012 to CKP-015, TR-SEC-12).
 */

const DECISION = '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9';

async function expectInsideViewport(page: Page, locator: Locator) {
  const viewport = page.viewportSize() ?? { width: 0, height: 0 };
  const rect = await locator.boundingBox();
  expect(rect, 'element is rendered').not.toBeNull();
  if (!rect) return;
  expect(rect.y).toBeGreaterThanOrEqual(0);
  expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
  expect(rect.y + rect.height).toBeLessThanOrEqual(viewport.height);
}

async function storedTokens(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const stored = (storage: Storage) => Array.from({ length: storage.length }, (_, index) => storage.getItem(storage.key(index) ?? '') ?? '');
    const values: string[] = [...stored(window.localStorage), ...stored(window.sessionStorage), document.cookie];
    return values.filter((value) => /eyJ|refresh-|Bearer/.test(value));
  });
}

test.describe('host list (CKP-012)', () => {
  test('CKP_012_valid_list_shows_both_hosts_in_the_picker', async ({ page }) => {
    await mockStandalone(page);
    await page.goto('./#/rules');

    await expect(page.getByTestId('header-host')).toHaveText('Loan origination');
    await page.getByTestId('host-picker').click();
    const items = page.locator('.host-picker__item .host-picker__label');
    await expect(items).toHaveText(['Loan origination', 'KYC onboarding']);
  });

  test('CKP_012_an_http_base_url_is_named_by_its_pointer_and_no_request_leaves_but_the_list', async ({ page }) => {
    const requests: string[] = [];
    page.on('request', (request) => {
      if (!request.url().startsWith('http://127.0.0.1')) requests.push(request.url());
    });
    const registry = { ...standaloneRegistry, hosts: [standaloneRegistry.hosts[0], { ...standaloneRegistry.hosts[1], baseUrl: 'http://kyc.bank.example' }] };
    await mockStandalone(page, { registry });
    await page.goto('./');

    await expect(page.getByTestId('config-pointer')).toHaveText('/hosts/1/baseUrl');
    expect(requests).toEqual([]);
  });
});

test.describe('sign-in (CKP-013)', () => {
  test('CKP_013_pkce_sign_in_with_per_host_tokens_in_memory', async ({ page }) => {
    const record = await mockStandalone(page);
    await page.goto('./#/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();

    await page.getByTestId('host-picker').click();
    await page.getByRole('link', { name: /KYC onboarding/ }).click();
    await expect(page.getByRole('link', { name: 'kyc-risk' })).toBeVisible();

    expect(record.authorizations).toHaveLength(1);
    const authorization = record.authorizations[0];
    expect(authorization?.searchParams.get('code_challenge_method')).toBe('S256');
    expect(authorization?.searchParams.get('scope')).toBe('openid profile loans-bpm kyc-bpm');
    // One refresh grant per host scope, after the code grant
    expect(record.grants.map((grant) => [grant.get('grant_type'), grant.get('scope')])).toEqual([
      ['authorization_code', null], ['refresh_token', 'loans-bpm'], ['refresh_token', 'kyc-bpm'],
    ]);
    expect(new Set(record.tokensSeen.loans.map(audienceOf))).toEqual(new Set(['loans-bpm']));
    expect(new Set(record.tokensSeen.kyc.map(audienceOf))).toEqual(new Set(['kyc-bpm']));
    expect(await storedTokens(page)).toEqual([]);

    // After a reload the tokens are gone: sign-in runs again through the provider's session
    await page.reload();
    await expect(page.getByRole('link', { name: 'kyc-risk' })).toBeVisible();
    expect(record.authorizations).toHaveLength(2);
    expect(page.url()).toMatch(/#\/hosts\/kyc\/rules$/);
  });
});

test.describe('version skew (CKP-014)', () => {
  test('CKP_014_meta_features_and_version_skew_gate_screens', async ({ page }) => {
    await mockStandalone(page, { kyc: { meta: { modules: [{ module: 'rules', engineVersion: '0.1.9', apiVersion: 'v1', features: [] }] } } });
    await page.goto('./#/hosts/kyc/rules');

    await expect(page.getByTestId('version-text')).toHaveText('KYC onboarding runs engine 0.1.9. This cockpit supports 0.2 and 0.3.');
    await expect(page.getByTestId('error-code')).toHaveText('UNSUPPORTED_VERSION');
    await expect(page.getByRole('navigation', { name: 'Cockpit menu' })).toHaveCount(0);
  });
});

test.describe('one host at a time (CKP-015)', () => {
  test('CKP_015_host_switch_shows_one_host_at_a_time', async ({ page }) => {
    const record = await mockStandalone(page);
    await page.goto('./#/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    expect(page.url()).toMatch(/#\/hosts\/loans\/rules$/);

    await page.getByTestId('host-picker').click();
    await page.getByRole('link', { name: /KYC onboarding/ }).click();

    await expect(page.getByRole('link', { name: 'kyc-risk' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'card-fee' })).toHaveCount(0);
    expect(page.url()).toMatch(/#\/hosts\/kyc\/rules$/);
    await expect(page.getByTestId('header-host')).toHaveText('KYC onboarding');
    await expect(page.getByTestId('header-tenant')).toHaveText('bank-a');
    // The kyc screens asked kyc only
    expect(record.paths.kyc.length).toBeGreaterThan(0);
  });

  test('CKP_015_a_host_answering_me_with_a_problem_shows_its_code_and_reference_and_the_other_host_stays_usable', async ({ page }) => {
    await mockStandalone(page, { kyc: { meProblem: { status: 403, code: 'PERMISSION_DENIED', correlationId: '01JD3A7Q2M5N8P1R4S6T9V0W2X' } } });
    await page.goto('./#/hosts/kyc/rules');

    await expect(page.getByTestId('error-code')).toHaveText('PERMISSION_DENIED');
    await expect(page.getByTestId('error-correlation-id')).toHaveText('01JD3A7Q2M5N8P1R4S6T9V0W2X');

    await page.getByTestId('host-picker').click();
    await page.getByRole('link', { name: /Loan origination/ }).click();
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
  });

  for (const height of [900, 1200]) {
    test(`CKP_015_picker_current_host_and_user_are_above_the_fold_at_1440x${String(height)}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height });
      await rememberLocale(page, 'vi');
      await mockStandalone(page);
      await page.goto('./#/rules');
      await expect(page.getByTestId('header-user')).toBeAttached();
      for (const locator of [page.getByTestId('host-picker'), page.getByTestId('header-host'), page.locator('.avatar-btn')]) {
        await expectInsideViewport(page, locator);
      }
      expect(await page.evaluate(() => window.scrollY)).toBe(0);
    });
  }

  test('CKP_015_the_picker_works_from_the_keyboard_and_passes_axe', async ({ page }) => {
    await mockStandalone(page);
    await page.goto('./#/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    await page.getByTestId('host-picker').focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.host-picker__menu')).toBeVisible();
    const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
    expect(axe.violations.map((violation) => violation.id)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(page.locator('.host-picker__menu')).toBeHidden();
    await expect(page.getByTestId('host-picker')).toBeFocused();
  });
});

test.describe('link to one decision (CKP-041)', () => {
  test('CKP_041_a_link_naming_its_host_lands_on_the_decision_after_sign_in', async ({ page }) => {
    const record = await mockStandalone(page, { loans: { me: meHa } });
    await page.goto(`./#/decisions/${DECISION}?host=loans`);

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`#/hosts/loans/decisions/${DECISION}$`));
    expect(record.authorizations).toHaveLength(1);
    expect(record.paths.loans.filter((path) => path.endsWith(`/decisions/${DECISION}`))).toHaveLength(1);
    expect(record.paths.kyc).toEqual([]);
  });

  test('CKP_041_without_its_host_the_link_asks_which_service_and_requests_nothing', async ({ page }) => {
    const record = await mockStandalone(page);
    await page.goto(`./#/decisions/${DECISION}`);

    await expect(page.getByRole('heading', { name: 'Which service logged this decision?' })).toBeVisible();
    expect(record.paths.loans).toEqual([]);
    expect(record.paths.kyc).toEqual([]);
    await page.getByRole('link', { name: 'KYC onboarding' }).click();
    await expect(page).toHaveURL(new RegExp(`#/hosts/kyc/decisions/${DECISION}$`));
  });
});

test.describe('tenant switcher (CKP-022)', () => {
  const tenants = { allowed: ['bank-a', 'bank-a-fin'], ruleSets: { 'bank-a': 'card-fee', 'bank-a-fin': 'kyc-risk' }, names: { 'bank-a': 'Bank A Retail', 'bank-a-fin': 'Bank A Finance' } };

  test('CKP_022_tenant_switcher_allowed_tenants_only', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const record = await mockStandalone(page, { loans: { tenants } });
    await page.goto('./#/hosts/loans/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    // The switcher offers exactly the token's allowed tenants, with the current one marked.
    const switcher = page.getByTestId('tenant-switcher');
    await expectInsideViewport(page, switcher);
    await switcher.click();
    const items = page.locator('.tenant-switcher__item');
    // The host's display names from /me (#474), each with its id beside it.
    await expect(items).toHaveText(['Bank A Retailbank-a', 'Bank A Financebank-a-fin']);
    await expect(items.first()).toHaveAttribute('aria-current', 'true');

    await page.getByRole('button', { name: 'Bank A Finance bank-a-fin' }).click();
    // The list reloads for the new tenant only: its own rule set, nothing of bank-a's.
    await expect(page.getByRole('link', { name: 'kyc-risk' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'card-fee' })).toHaveCount(0);
    await expect(switcher).toContainText('Bank A Finance');
    const switched = record.paths.loans.map((path, index) => [path, record.tenantsSent.loans[index]]).filter(([path]) => path?.endsWith('/rule-sets'));
    expect(switched.at(-1)).toEqual(['/api/bpm/rules/v1/rule-sets', 'bank-a-fin']);
    // Never two tenants on one screen: the header names one, the list shows one.
    await expect(page.getByTestId('tenant-refused')).toHaveCount(0);
  });

  test('CKP_022_a_forged_tenant_is_refused_and_the_cockpit_returns_to_the_users_own_tenant', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const record = await mockStandalone(page, { loans: { tenants, forgeTenant: 'bank-b' } });
    await page.goto('./#/hosts/loans/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    await page.getByTestId('tenant-switcher').click();
    await page.getByRole('button', { name: 'bank-a-fin' }).click();

    // The proxy sent bank-b: the host refused it, and the cockpit is back on bank-a with the reason in words.
    const notice = page.getByTestId('tenant-refused');
    await expect(notice).toContainText('The service did not accept that tenant for your account.');
    expect(record.tenantsSent.loans).toContain('bank-b');
    await expect(page.getByTestId('tenant-switcher')).toContainText('Bank A Retail');
    await expect(page.getByTestId('tenant-switcher')).not.toContainText('Bank A Finance');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'kyc-risk' })).toHaveCount(0);
    await notice.locator('summary').click();
    await expect(page.getByTestId('tenant-refused-reference')).toHaveText('01JD5T3N4NT0000000000000AB');
    const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
    expect(axe.violations.map((violation) => violation.id)).toEqual([]);
    await notice.getByRole('button', { name: 'Dismiss' }).click();
    await expect(notice).toHaveCount(0);
  });

  test('CKP_022_with_switching_off_or_one_allowed_tenant_there_is_no_switcher', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // Switching on, one tenant in the token: nothing to switch to.
    const record = await mockStandalone(page, { loans: { tenants: { allowed: ['bank-a'], ruleSets: { 'bank-a': 'card-fee' } } }, kyc: {} });
    await page.goto('./#/hosts/loans/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    await expect(page.getByTestId('tenant-switcher')).toHaveCount(0);
    await expect(page.getByTestId('header-tenant')).toHaveText('bank-a');
    // Switching off on kyc: hidden, and no X-Tenant-Id ever leaves.
    await page.goto('./#/hosts/kyc/rules');
    await expect(page.getByRole('link', { name: 'kyc-risk' })).toBeVisible();
    await expect(page.getByTestId('tenant-switcher')).toHaveCount(0);
    expect([...record.tenantsSent.loans, ...record.tenantsSent.kyc].every((sent) => sent === '')).toBe(true);
  });

  test('CKP_022_the_switcher_works_from_the_keyboard', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await rememberLocale(page, 'vi');
    await mockStandalone(page, { loans: { tenants } });
    await page.goto('./#/hosts/loans/rules');
    await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
    await page.getByTestId('tenant-switcher').focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.tenant-switcher .host-picker__menu')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('.tenant-switcher .host-picker__menu')).toBeHidden();
    await expect(page.getByTestId('tenant-switcher')).toBeFocused();
  });
});
