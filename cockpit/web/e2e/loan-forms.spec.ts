import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { meAn, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { QUEUE_TASKS } from '../src/mocks/taskQueueDemo';
import { mockHost, rememberLocale } from './mockHost';

/*
 * The proposal and approval forms of the loan showcase (#623), served from the example's own form files and drawn by the
 * cockpit's form screen, at 1440 and 390 wide. The host engine is the fake one; only the form and the task title are
 * swapped for the loan task. Screenshots go to LOAN_SHOTS_DIR, or e2e/screenshots/ by default.
 */

const SHOTS = process.env['LOAN_SHOTS_DIR'] ?? 'e2e/screenshots';
const FORMS = fileURLToPath(new URL('../../../examples/rule-and-process/src/main/resources/forms', import.meta.url));
const form = (name: string) => JSON.parse(readFileSync(resolve(FORMS, `${name}.form.json`), 'utf8')) as object;
const SIZES = [{ width: 1440, height: 900 }, { width: 390, height: 844 }] as const;

const TASKS = {
  proposal: { form: 'loan-proposal', title: { en: 'Prepare the credit proposal (maker)', vi: 'Lập đề xuất tín dụng (người lập)' }, node: 'prepare-proposal' },
  approval: { form: 'loan-approval', title: { en: 'Approve (credit manager, four eyes)', vi: 'Phê duyệt (quản lý tín dụng, bốn mắt)' }, node: 'approve-manager' },
} as const;

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

async function openLoanTask(page: Page, which: keyof typeof TASKS, size: { width: number; height: number }) {
  const task = TASKS[which];
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await rememberLocale(page, 'en');
  const engine = createFakeProcessEngine({ locale: 'en' });
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: meAn }));
  await page.route('**/api/bpm/rules/v1/**', (route) => route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ status: 403, code: 'PERMISSION_DENIED' }) }));
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const url = request.url();
    if (request.method() === 'GET' && url.endsWith(`/tasks/${QUEUE_TASKS.myCreditReview}/form`)) {
      await route.fulfill({ headers: { ETag: '"1"' }, json: { schemaVersion: 1, taskId: QUEUE_TASKS.myCreditReview, lockVersion: 1, form: { ...form(task.form), data: {} } } });
      return;
    }
    const answer = engine.handle({ method: request.method(), path: url, headers: request.headers(), body: request.postData() ?? '', user: meAn.user.id, permissions: meAn.permissions });
    const body = request.method() === 'GET' && url.endsWith(`/tasks/${QUEUE_TASKS.myCreditReview}`)
      ? JSON.stringify({ ...(JSON.parse(answer.body) as object), name: task.title.en, nodeId: task.node })
      : answer.body;
    await route.fulfill({ status: answer.status, headers: { ...answer.headers, 'content-length': String(Buffer.byteLength(body)) }, body });
  });
  await page.goto(`./#/tasks/${QUEUE_TASKS.myCreditReview}`);
  await expect(page.getByRole('form')).toBeVisible();
  await expect(page.locator('.skeleton')).toHaveCount(0);
}

for (const size of SIZES) {
  for (const which of ['proposal', 'approval'] as const) {
    test(`TR_UI_623_loan_${which}_form_${String(size.width)}`, async ({ page }) => {
      await openLoanTask(page, which, size);
      const complete = page.getByRole('button', { name: 'Complete task' });
      await expect(complete).toBeVisible();
      if (which === 'proposal') {
        await expect(page.getByLabel(/^Proposed amount/)).toBeVisible();
        await expect(page.getByLabel(/^Term in months/)).toBeVisible();
        await expect(page.getByRole('radio', { name: 'APPROVE' })).toBeVisible();
      } else {
        await expect(page.getByRole('radio', { name: 'APPROVED' })).toBeVisible();
        await expect(page.getByRole('radio', { name: 'REJECTED' })).toBeVisible();
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      if (size.width === 1440) {
        const box = await complete.boundingBox();
        expect((box?.y ?? 9999) + (box?.height ?? 0)).toBeLessThanOrEqual(size.height);
      }
      await page.screenshot({ path: `${SHOTS}/loan-${which}-${String(size.width)}.png`, fullPage: false });
    });
  }
}
