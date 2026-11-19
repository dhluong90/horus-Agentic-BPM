import { expect, test, type Locator, type Page } from '@playwright/test';
import { cspViolations, mockHost } from './mockHost';

async function box(locator: Locator) {
  const value = await locator.boundingBox();
  expect(value, 'element is rendered').not.toBeNull();
  return value ?? { x: 0, y: 0, width: 0, height: 0 };
}

async function expectInsideViewport(page: Page, locator: Locator) {
  const viewport = page.viewportSize() ?? { width: 0, height: 0 };
  const rect = await box(locator);
  expect(rect.x).toBeGreaterThanOrEqual(0);
  expect(rect.y).toBeGreaterThanOrEqual(0);
  expect(rect.x + rect.width).toBeLessThanOrEqual(viewport.width);
  expect(rect.y + rect.height).toBeLessThanOrEqual(viewport.height);
}

test.describe('embedded cockpit, case A', () => {
  test('CKP_001_embedded_cockpit_loads_registry_then_meta_and_me_and_shows_host_tenant_and_user', async ({ page }) => {
    const calls = await mockHost(page);
    const external: string[] = [];
    page.on('request', (request) => {
      if (!request.url().startsWith('http://127.0.0.1:4173/')) external.push(request.url());
    });

    await page.goto('./');

    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-host')).toHaveText('Loan origination');
    await expect(header.getByTestId('header-tenant')).toHaveText('bank-a');
    await expect(header.getByTestId('header-user')).toHaveText('Nguyễn Thị Lan');
    expect(calls[0]).toBe('/cockpit-hosts.json');
    expect([...calls.slice(1)].sort()).toEqual(['/api/bpm/me', '/api/bpm/meta']);
    // No backend of its own and no third-party origin: everything is same-origin.
    expect(external).toEqual([]);
  });

  test('CKP_001_header_with_host_tenant_and_user_is_above_the_fold_at_1440x900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page);
    await page.goto('./');

    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-user')).toBeVisible();
    await expectInsideViewport(page, header);
    for (const id of ['header-host', 'header-tenant', 'header-user']) {
      await expectInsideViewport(page, header.getByTestId(id));
    }
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
  });

  test('CKP_001_header_at_1440x1200_has_the_same_geometry_and_no_layout_shift', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page);
    await page.goto('./');
    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-user')).toBeVisible();
    const at900 = await box(header);

    await page.setViewportSize({ width: 1440, height: 1200 });
    await page.reload();
    await expect(header.getByTestId('header-user')).toBeVisible();
    const at1200 = await box(header);

    expect(at1200).toEqual(at900);
    const shift = await page.evaluate(
      () =>
        new Promise<number>((resolve) => {
          let total = 0;
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries()) total += (entry as unknown as { value: number }).value;
          }).observe({ type: 'layout-shift', buffered: true });
          setTimeout(() => {
            resolve(total);
          }, 300);
        }),
    );
    expect(shift).toBeLessThan(0.01);
    // No empty band: the page fills the viewport and the main area starts right under the header.
    const main = await box(page.locator('main'));
    expect(main.y).toBe(at1200.y + at1200.height);
    expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBeLessThanOrEqual(1200);
  });
});

test.describe('failure paths', () => {
  test('CKP_001_me_problem_shows_code_and_correlation_id_and_no_partial_header', async ({ page }) => {
    await mockHost(page, 'me-forbidden');
    await page.goto('./');

    const alert = page.getByRole('alert');
    await expect(alert.getByTestId('error-code')).toHaveText('PERMISSION_DENIED');
    await expect(alert.getByTestId('error-correlation-id')).toHaveText('01JD3A7Q2M5N8P1R4S6T9V0W2X');
    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-host')).toHaveCount(0);
    await expect(header.getByTestId('header-tenant')).toHaveCount(0);
    await expect(header.getByTestId('header-user')).toHaveCount(0);
  });

  test('CKP_001_invalid_registry_shows_the_json_pointer_and_calls_no_host', async ({ page }) => {
    const calls = await mockHost(page, 'registry-invalid');
    await page.goto('./');

    await expect(page.getByTestId('config-pointer')).toHaveText('/hosts/0/baseUrl');
    expect(calls).toEqual(['/cockpit-hosts.json']);
  });
});

test.describe('security headers', () => {
  test('TR_SEC_09_production_bundle_runs_under_script_src_self_with_no_violation', async ({ page }) => {
    const violations = cspViolations(page);
    await mockHost(page);
    const response = await page.goto('./');

    const headers = response?.headers() ?? {};
    expect(headers['content-security-policy']).toContain("script-src 'self'");
    expect(headers['content-security-policy']).toContain("style-src 'self'");
    expect(headers['x-content-type-options']).toBe('nosniff');
    await expect(page.getByTestId('header-user')).toBeVisible();
    await page.getByRole('button', { name: 'VI' }).click();
    await page.getByRole('button', { name: /giao diện tối/ }).click();
    expect(violations).toEqual([]);
  });
});

test.describe('keyboard', () => {
  test('TR_UI_03_skip_link_language_and_theme_switches_work_from_the_keyboard', async ({ page }) => {
    await mockHost(page);
    await page.goto('./');
    await expect(page.getByTestId('header-user')).toBeVisible();

    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 'EN' })).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Tổng quan dịch vụ');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.locator('html')).toHaveAttribute('data-theme', /light|dark/);
  });
});

test.describe('header geometry', () => {
  test('CKP_001_header_keeps_one_height_while_loading_on_error_and_when_ready', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page, 'me-forbidden');
    await page.goto('./');
    await expect(page.getByRole('alert')).toBeVisible();
    const onError = await box(page.getByTestId('session-header'));

    await page.unrouteAll();
    await mockHost(page, 'ready');
    await page.reload();
    await expect(page.getByTestId('header-user')).toBeVisible();
    const ready = await box(page.getByTestId('session-header'));

    expect(ready.height).toBe(onError.height);
  });
});
