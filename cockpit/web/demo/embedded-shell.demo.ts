import { expect, test, type Locator, type Page } from '@playwright/test';

// Sprint demo of the embedded cockpit shell on the real example host: screenshots at 1440x900 and
// 1440x1200 in light and dark, and a captioned recording at 1440x900.
const PASSWORD = process.env['HORUS_DEMO_PASSWORD'] ?? '';
const OUT = 'demo/output';

/** Caption drawn over the page for the recording only, with CSSOM styles (allowed under the cockpit's CSP). */
async function caption(page: Page, text: string): Promise<void> {
  await page.evaluate((value) => {
    let box = document.getElementById('demo-caption');
    if (!box) {
      box = document.createElement('div');
      box.id = 'demo-caption';
      Object.assign(box.style, {
        position: 'fixed', left: '50%', bottom: '28px', transform: 'translateX(-50%)', zIndex: '9999',
        background: 'rgba(15,23,42,.92)', color: '#fff', font: '600 18px system-ui, sans-serif',
        padding: '12px 20px', borderRadius: '10px', maxWidth: '1100px', textAlign: 'center',
        pointerEvents: 'none',
      });
      document.body.appendChild(box);
    }
    box.textContent = value;
  }, text);
  await page.waitForTimeout(2200);
}

async function signIn(page: Page, user: string): Promise<void> {
  await page.goto('/bpm/cockpit/');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('Username').fill(user);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page).toHaveURL(/\/bpm\/cockpit\/(\?continue)?$/);
  await expect(page.getByTestId('header-user')).toHaveText(user);
}

async function expectAboveFold(page: Page, locator: Locator): Promise<void> {
  const box = await locator.boundingBox();
  const viewport = page.viewportSize();
  expect(box && viewport && box.y >= 0 && box.y + box.height <= viewport.height).toBe(true);
}

test('screenshots: signed-in header on the real host, light and dark, 1440x900 and 1440x1200', async ({ browser }) => {
  for (const theme of ['light', 'dark'] as const) {
    for (const height of [900, 1200]) {
      const context = await browser.newContext({ viewport: { width: 1440, height }, colorScheme: theme });
      const page = await context.newPage();
      await signIn(page, 'lan');
      const header = page.getByTestId('session-header');
      await expect(header.getByTestId('header-host')).toHaveText('Loan origination');
      await expect(header.getByTestId('header-tenant')).toHaveText('bank-a');
      await expectAboveFold(page, header);
      await expect(page.getByText('Edit rule sets')).toBeVisible();
      await page.evaluate(async () => {
        await document.fonts.ready;
      });
      await page.screenshot({ path: `${OUT}/real-host-signed-in-1440x${height}-${theme}.png` });
      await context.close();
    }
  }
});

test('recording: sign in, open the cockpit, switch language and theme, second user', async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    recordVideo: { dir: `${OUT}/video`, size: { width: 1440, height: 900 } },
  });
  const page = await context.newPage();

  await page.goto('/bpm/cockpit/');
  await caption(page, 'Example host, dev profile: the cockpit path asks for the host\'s own sign-in (CK-F-01, TR-SEC-09)');
  await page.getByLabel('Username').fill('lan');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page.getByTestId('header-user')).toHaveText('lan');
  await caption(page, 'Embedded cockpit: service, tenant and user from /api/bpm/meta and /api/bpm/me (CKP-001 case A)');
  await caption(page, 'Header above the fold at 1440x900; Lan may view and edit rule sets (rules:read, rules:author)');
  await page.getByRole('button', { name: 'VI' }).click();
  await caption(page, 'One language at a time: Vietnamese (EN | VI switch)');
  await page.getByRole('button', { name: /giao diện tối/ }).click();
  await caption(page, 'Dark theme, same approved tokens');
  await page.getByRole('button', { name: 'EN' }).click();
  await page.getByRole('button', { name: /light theme/ }).click();

  await page.goto('/logout');
  await page.getByRole('button', { name: /log out/i }).click();
  await page.goto('/bpm/cockpit/');
  await page.getByLabel('Username').fill('minh');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page.getByTestId('header-user')).toHaveText('minh');
  await caption(page, 'Second user Minh: different permissions from the host\'s role mapping (approve, not edit)');
  await context.close();
});
