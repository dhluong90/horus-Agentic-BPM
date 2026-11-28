import { expect, test, type Page } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * Live counts on the process diagram (#91) at 1440x900, in both languages: tokens and open incidents are plain figures
 * under their steps, each opens the list it counts, and a refresh moves nothing. LIVE_COUNTS_SHOTS names a folder for
 * the screenshots; without it nothing is written.
 */

const SHOTS = process.env['LIVE_COUNTS_SHOTS'];
const TEXT = {
  en: { tokens: /12 at Approve high-risk customer/, incidents: /1 open at Screen sanctions lists/, key: 'Live figures', listed: 'Show all incidents' },
  vi: { tokens: /12 tại Duyệt khách hàng rủi ro cao/, incidents: /1 đang mở tại Sàng lọc danh sách cấm vận/, key: 'Số liệu trực tiếp', listed: 'Xem mọi sự cố' },
} as const;

async function open(page: Page, locale: 'en' | 'vi', hash: string) {
  const processes = createFakeProcessEngine({ locale });
  const rules = createFakeEngine(demoSeed());
  await rememberLocale(page, locale);
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: meHoa }));
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = rules.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meHoa.user.id, permissions: meHoa.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = processes.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meHoa.user.id, permissions: meHoa.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto(`/${hash}`);
}

test.describe('live counts on the diagram', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  for (const locale of ['en', 'vi'] as const) {
    test(`CKP_091_figures_sit_under_steps_and_open_their_lists_${locale}`, async ({ page }) => {
      const words = TEXT[locale];
      await open(page, locale, '#/processes/kyc-onboarding/2');
      const tokens = page.getByRole('link', { name: words.tokens });
      const incidents = page.getByRole('link', { name: words.incidents });
      await expect(tokens).toBeVisible();
      await expect(incidents).toBeVisible();
      await expect(page.getByRole('list', { name: words.key })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      if (SHOTS !== undefined) await page.screenshot({ path: `${SHOTS}/diagram-${locale}-1440x900.png` });

      // A new read moves nothing: the figures keep their place.
      const before = await tokens.boundingBox();
      await page.evaluate(() => { document.dispatchEvent(new Event('visibilitychange')); });
      await page.waitForTimeout(300);
      expect(await tokens.boundingBox()).toEqual(before);

      await incidents.click();
      await expect(page).toHaveURL(/#\/incidents\?process=kyc-onboarding&version=2&step=screen-sanctions/);
      await expect(page.getByRole('link', { name: words.listed })).toBeVisible();
      await expect(page.getByRole('table')).toBeVisible();
      if (SHOTS !== undefined) await page.screenshot({ path: `${SHOTS}/incidents-${locale}-1440x900.png` });
    });
  }
});
