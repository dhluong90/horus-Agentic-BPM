import { expect, test, type Locator, type Page } from '@playwright/test';
import { UNREADABLE_PERMISSION } from '../src/mocks/fixtures';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

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
    const origin = new URL(test.info().project.use.baseURL ?? 'http://127.0.0.1:4173/').origin;
    page.on('request', (request) => {
      if (new URL(request.url()).origin !== origin) external.push(request.url());
    });

    await page.goto('./#/service');

    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-host')).toHaveText('Retail banking');
    await expect(header.getByTestId('header-tenant')).toHaveText('bank-a');
    await expect(header.getByTestId('header-user')).toHaveText('Nguyễn Thị Lan');
    expect(calls[0]).toBe('/cockpit-hosts.json');
    expect([...calls.slice(1, 3)].sort()).toEqual(['/api/bpm/me', '/api/bpm/meta']);
    // No backend of its own and no third-party origin: everything is same-origin.
    expect(external).toEqual([]);
  });

  test('CKP_001_header_with_host_tenant_and_user_is_above_the_fold_at_1440x900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page);
    await page.goto('./#/service');

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
    await page.goto('./#/service');
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
          // Only shifts of the header count here; the cards below fill in as the engine answers.
          const header = document.querySelector('[data-testid="session-header"]');
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries()) {
              const shift = entry as unknown as { value: number; sources?: { node?: Node | null }[] };
              if ((shift.sources ?? []).some((source) => source.node instanceof Node && header?.contains(source.node) === true)) total += shift.value;
            }
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

test.describe('tolerant reading of /me', () => {
  /** The menu items a scenario's user is offered, in order. */
  async function menuFor(page: Page, scenario: 'ready' | 'me-unknown-permission'): Promise<string[]> {
    await mockHost(page, scenario);
    await mockRuleEngine(page, scenario);
    await page.goto('./#/service');
    await expect(page.getByTestId('session-header').getByTestId('header-user')).toHaveText('Nguyễn Thị Lan');
    return page.getByTestId('nav-rail').getByRole('link').allInnerTexts();
  }

  test('CKP_001_me_with_an_unreadable_permission_signs_the_user_in_and_offers_nothing_for_it', async ({ page }) => {
    const notes: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'warning' && message.text().includes('/api/bpm/me')) notes.push(message.text());
    });
    await page.setViewportSize({ width: 1440, height: 900 });

    const menu = await menuFor(page, 'me-unknown-permission');

    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('session-header').getByTestId('header-tenant')).toHaveText('bank-a');
    // rules:read and rules:author still open the rule areas; the dropped name adds nothing, Decisions included.
    expect(menu.map((item) => item.split('\n')[0])).toEqual(expect.arrayContaining(['Home', 'Rules', 'Approvals']));
    expect(menu.map((item) => item.split('\n')[0])).not.toContain('Decisions');
    expect(notes).toHaveLength(1);
    expect(notes[0]).toContain(UNREADABLE_PERMISSION);
  });

  test('CKP_001_the_dropped_permission_leaves_the_same_menu_as_without_it', async ({ browser }) => {
    const baseURL = test.info().project.use.baseURL;
    const options = { viewport: { width: 1440, height: 900 }, ...(baseURL === undefined ? {} : { baseURL }) };
    const withName = await browser.newContext(options);
    const without = await browser.newContext(options);
    expect(await menuFor(await withName.newPage(), 'me-unknown-permission')).toEqual(await menuFor(await without.newPage(), 'ready'));
    await withName.close();
    await without.close();
  });
});

test.describe('failure paths', () => {
  test('CKP_001_me_problem_shows_code_and_correlation_id_and_no_partial_header', async ({ page }) => {
    await mockHost(page, 'me-forbidden');
    await page.goto('./#/service');

    const alert = page.getByRole('alert');
    await expect(alert.getByTestId('error-code')).toHaveText('PERMISSION_DENIED');
    await expect(alert.getByTestId('error-correlation-id')).toHaveText('01JD3A7Q2M5N8P1R4S6T9V0W2X');
    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-host')).toHaveCount(0);
    await expect(header.getByTestId('header-tenant')).toHaveCount(0);
    await expect(header.getByTestId('header-user')).toHaveCount(0);
  });

  test('CKP_012_invalid_registry_shows_the_json_pointer_and_calls_no_host', async ({ page }) => {
    const calls = await mockHost(page, 'registry-invalid');
    await page.goto('./#/service');

    await expect(page.getByTestId('config-pointer')).toHaveText('/hosts/0/baseUrl');
    expect(calls).toEqual(['/cockpit-hosts.json']);
  });
});

test.describe('security headers', () => {
  test('TR_SEC_09_production_bundle_runs_under_script_src_self_with_no_violation', async ({ page }) => {
    const violations = cspViolations(page);
    await mockHost(page);
    const response = await page.goto('./#/service');

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
    await page.goto('./#/service');
    await expect(page.getByTestId('header-user')).toBeVisible();

    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 'EN', exact: true })).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/^Chào buổi/);
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.locator('html')).toHaveAttribute('data-theme', /light|dark/);
  });
});

test.describe('header geometry', () => {
  test('CKP_001_header_keeps_one_height_while_loading_on_error_and_when_ready', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page, 'me-forbidden');
    await page.goto('./#/service');
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

test.describe('header with the longest values', () => {
  test('CKP_001_vietnamese_header_with_longest_label_chip_and_banner_stays_one_line_above_the_fold_at_1440x900', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockHost(page, 'ready-longest-labels');
    await page.goto('./#/service');

    const header = page.getByTestId('session-header');
    await expect(header.getByTestId('header-user')).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'vi');
    await page.evaluate(async () => {
      await document.fonts.ready;
    });
    const rect = await box(header);
    expect(rect.height).toBe(64);
    await expectInsideViewport(page, header);
    for (const id of ['header-host', 'header-tenant', 'header-user']) {
      await expectInsideViewport(page, header.getByTestId(id));
    }
    await expectInsideViewport(page, page.getByRole('button', { name: 'VI' }));
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1440);
    // The full text stays available when it is cut with an ellipsis.
    await expect(header.getByTestId('header-host')).toHaveAttribute('title', /Hệ thống khởi tạo/);
    await page.screenshot({ path: 'e2e/screenshots/shell-longest-labels-1440x900-light-vi.png' });
  });
});

test.describe('theme before the script runs', () => {
  test('TR_UI_03_dark_system_preference_applies_without_data_theme', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await mockHost(page);
    await page.goto('./#/service');
    await expect(page.getByTestId('header-user')).toBeVisible();

    const background = await page.evaluate(() => {
      document.documentElement.removeAttribute('data-theme');
      return getComputedStyle(document.body).backgroundColor;
    });
    expect(background).toBe('rgb(11, 15, 23)');
  });
});

test.describe('targets and fold (ux-design section 8)', () => {
  for (const scenario of ['ready', 'me-forbidden', 'signed-out'] as const) {
    test(`TR_UI_03_every_visible_control_has_a_44px_hit_area_${scenario}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await rememberLocale(page, 'vi');
      await mockHost(page, scenario);
      await page.goto('./#/service');
      await expect(scenario === 'ready' ? page.getByTestId('header-user') : scenario === 'signed-out' ? page.getByTestId('signed-out') : page.getByRole('alert')).toBeVisible();
      // Measure the Copy buttons too: open every Details disclosure first.
      await page.locator('details').evaluateAll((all) => { for (const details of all) (details as HTMLDetailsElement).open = true; });

      const tooSmall = await page.evaluate(() => {
        const half = 21; // probe inside a 44 x 44 px box centred on the control
        const failures: string[] = [];
        for (const control of document.querySelectorAll<HTMLElement>('button, a[href]')) {
          const rect = control.getBoundingClientRect();
          // A control inside a closed Details is not visible, so it has no target to measure yet.
          if (rect.width === 0 || rect.bottom < 0 || rect.top > window.innerHeight || !control.checkVisibility()) continue;
          const cx = rect.left + rect.width / 2;
          const cy = rect.top + rect.height / 2;
          const probes: Array<[number, number]> = [[cx, cy - half], [cx, cy + half], [cx - half, cy], [cx + half, cy]];
          const missed = probes.filter(([x, y]) => {
            const hit = document.elementFromPoint(x, y);
            return !(hit && (hit === control || control.contains(hit)));
          });
          if (missed.length > 0) failures.push(`${control.textContent.trim() || (control.getAttribute('aria-label') ?? '?')} ${Math.round(rect.width)}x${Math.round(rect.height)}`);
        }
        return failures;
      });
      expect(tooSmall).toEqual([]);
    });
  }

  test('CKP_001_primary_action_of_every_error_state_is_above_the_fold_at_1440x900_in_vietnamese', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await rememberLocale(page, 'vi');
    const cases = [
      ['me-forbidden', 'Thử lại'],
      ['me-unauthenticated', 'Thử lại'],
      ['meta-not-found', 'Thử lại'],
      ['me-bad-shape', 'Thử lại'],
      ['signed-out', 'Đăng nhập lại'],
    ] as const;
    for (const [scenario, action] of cases) {
      await page.unrouteAll();
      await mockHost(page, scenario);
      await page.goto('./#/service');
      await page.reload();
      const button = page.getByRole('button', { name: action });
      await expect(button, scenario).toBeVisible();
      await expectInsideViewport(page, button);
      expect((await box(button)).height, scenario).toBeGreaterThanOrEqual(44);
    }
  });

  test('CKP_001_usual_vietnamese_service_tenant_and_user_are_not_truncated_at_1440', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await rememberLocale(page, 'vi');
    await mockHost(page, 'ready');
    await page.goto('./#/service');
    await expect(page.getByTestId('header-user')).toBeVisible();

    const cut = await page.evaluate(() =>
      // The user's name sits in the account menu and the avatar's accessible name, not in the bar.
      ['header-host', 'header-tenant'].filter((id) => {
        const el = document.querySelector<HTMLElement>(`[data-testid="${id}"]`);
        return !el || el.scrollWidth > el.clientWidth;
      }),
    );
    expect(cut).toEqual([]);
  });
});
