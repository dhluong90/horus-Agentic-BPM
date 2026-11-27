import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { meAn, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { QUEUE_FORMS, QUEUE_TASKS, type QueueForm } from '../src/mocks/taskQueueDemo';
import { mockHost, rememberLocale } from './mockHost';

/*
 * The proposal and approval forms of the loan showcase (#623), served from the example's own form files and drawn by the
 * cockpit's form screen, at 1440 and 390 wide. The host engine is the fake one; only the form and the task title are
 * swapped for the loan task. Screenshots go to LOAN_SHOTS_DIR, or e2e/screenshots/ by default.
 */

type Locale = 'en' | 'vi';
const SHOTS = process.env['LOAN_SHOTS_DIR'] ?? 'e2e/screenshots';
const FORMS = fileURLToPath(new URL('../../../examples/rule-and-process/src/main/resources/forms', import.meta.url));
const form = (name: string) => JSON.parse(readFileSync(resolve(FORMS, `${name}.form.json`), 'utf8')) as object;
const SIZES = [{ width: 1440, height: 900 }, { width: 390, height: 844 }] as const;

const TASKS = {
  proposal: { form: 'loan-proposal', title: { en: 'Prepare the credit proposal (maker)', vi: 'Lập đề xuất tín dụng (người lập)' }, node: 'prepare-proposal' },
  approval: { form: 'loan-approval', title: { en: 'Approve (credit manager, four eyes)', vi: 'Phê duyệt (quản lý tín dụng, bốn mắt)' }, node: 'approve-manager' },
} as const;

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

const mutableForms = QUEUE_FORMS as Record<string, QueueForm>;
const original = QUEUE_FORMS[QUEUE_TASKS.myCreditReview];
test.afterEach(() => { if (original !== undefined) mutableForms[QUEUE_TASKS.myCreditReview] = original; });

async function openLoanTask(page: Page, which: keyof typeof TASKS, size: { width: number; height: number }, locale: Locale = 'en', patch?: (task: Record<string, unknown>) => Record<string, unknown>) {
  const task = TASKS[which];
  await page.setViewportSize(size);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  // The fake engine declares the credit review form for this task; for these tests it declares the loan form instead.
  mutableForms[QUEUE_TASKS.myCreditReview] = { ...form(task.form), data: {} } as QueueForm;
  const engine = createFakeProcessEngine({ locale });
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: meAn }));
  await page.route('**/api/bpm/rules/v1/**', (route) => route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ status: 403, code: 'PERMISSION_DENIED' }) }));
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const url = request.url();
    const answer = engine.handle({ method: request.method(), path: url, headers: request.headers(), body: request.postData() ?? '', user: meAn.user.id, permissions: meAn.permissions });
    const isTask = request.method() === 'GET' && url.endsWith(`/tasks/${QUEUE_TASKS.myCreditReview}`);
    const named = isTask ? { ...(JSON.parse(answer.body) as Record<string, unknown>), name: task.title[locale], nodeId: task.node, assignee: 'role:CREDIT_MANAGER' } : undefined;
    const body = named !== undefined ? JSON.stringify(patch !== undefined ? patch(named) : named) : answer.body;
    await route.fulfill({ status: answer.status, headers: { ...answer.headers, 'content-length': String(Buffer.byteLength(body)) }, body });
  });
  await page.goto(`./#/tasks/${QUEUE_TASKS.myCreditReview}`);
  await expect(page.locator('.skeleton')).toHaveCount(0);
}

const L = {
  en: { amount: /^Proposed amount/, term: /^Term/, approveRec: 'Recommend approval', approve: 'Approve', reject: 'Reject', complete: 'Complete task', reasonStar: /^Reason \(required when rejecting\)/, fill: 'Fill in this field.', done: /^Completed by/ },
  vi: { amount: /^Số tiền đề xuất/, term: /^Kỳ hạn/, approveRec: 'Đề nghị phê duyệt', approve: 'Phê duyệt', reject: 'Từ chối', complete: 'Hoàn thành tác vụ', reasonStar: /^Lý do \(bắt buộc khi từ chối\)/, fill: /./, done: /^Hoàn thành bởi/ },
} as const;

const noHorizontalScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

for (const locale of ['en', 'vi'] as const) {
  for (const size of SIZES) {
    for (const which of ['proposal', 'approval'] as const) {
      test(`TR_UI_623_loan_${which}_form_${String(size.width)}_${locale}`, async ({ page }) => {
        await openLoanTask(page, which, size, locale);
        const l = L[locale];
        const complete = page.getByRole('button', { name: l.complete });
        await expect(page.getByRole('form')).toBeVisible();
        await expect(complete).toBeVisible();
        if (which === 'proposal') {
          await expect(page.getByLabel(l.amount)).toBeVisible();
          await expect(page.getByLabel(l.term)).toBeVisible();
          await expect(page.getByRole('radio', { name: l.approveRec })).toBeVisible();
          // No raw code is shown as a choice.
          await expect(page.getByRole('radio', { name: 'APPROVE', exact: true })).toHaveCount(0);
          // The amount is grouped as the language groups it while typing.
          await page.getByLabel(l.amount).fill('900000000');
          await expect(page.getByLabel(l.amount)).toHaveValue(locale === 'vi' ? '900.000.000' : '900,000,000');
          await expect(page.getByText(locale === 'vi' ? 'tháng' : 'months', { exact: true })).toBeVisible();
        } else {
          await expect(page.getByRole('radio', { name: l.approve, exact: true })).toBeVisible();
          await expect(page.getByRole('radio', { name: l.reject, exact: true })).toBeVisible();
          await expect(page.getByRole('radio', { name: 'APPROVED', exact: true })).toHaveCount(0);
          // What each choice does is stated beside the action.
          await expect(page.locator('.taskform__effects li')).toHaveCount(2);
        }
        expect(await noHorizontalScroll(page)).toBe(true);
        if (size.width === 1440) {
          const box = await complete.boundingBox();
          expect((box?.y ?? 9999) + (box?.height ?? 0)).toBeLessThanOrEqual(size.height);
        } else {
          // On a phone the action card follows the fields: it never floats over one.
          expect(await page.locator('.review-dock').evaluate((dock) => getComputedStyle(dock).position)).toBe('static');
        }
        await page.screenshot({ path: `${SHOTS}/loan-${which}-${String(size.width)}-${locale}-v2.png`, fullPage: false });
      });
    }
  }
}

test('TR_UI_623_rejecting_needs_a_reason', async ({ page }) => {
  await openLoanTask(page, 'approval', { width: 1440, height: 900 });
  await page.getByRole('radio', { name: 'Reject', exact: true }).check();
  await expect(page.getByLabel(L.en.reasonStar)).toBeVisible();
  await page.getByRole('button', { name: 'Complete task' }).click();
  await expect(page.getByText('Fill in this field.')).toBeVisible();
  await expect(page.getByLabel(L.en.reasonStar)).toHaveAttribute('aria-invalid', 'true');
  await page.screenshot({ path: `${SHOTS}/loan-approval-reject-1440-en-v2.png`, fullPage: false });
  // Approving needs no reason.
  await page.getByRole('radio', { name: 'Approve', exact: true }).check();
  await expect(page.getByText('Fill in this field.')).toHaveCount(0);
});

test('TR_UI_623_the_proposer_sees_a_calm_four_eyes_notice', async ({ page }) => {
  await openLoanTask(page, 'approval', { width: 1440, height: 900 }, 'en', (task) => ({ ...task, status: 'OPEN', claimedBy: null, fourEyes: true, allowedActions: [] }));
  const note = page.locator('.dock__self');
  await expect(note).toBeVisible();
  await expect(note).toContainText('You worked on this case');
  await expect(note).toContainText('credit manager');
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.screenshot({ path: `${SHOTS}/loan-approval-foureyes-1440-en-v2.png`, fullPage: false });
});

test('TR_UI_623_after_approval_the_result_is_shown', async ({ page }) => {
  await openLoanTask(page, 'approval', { width: 1440, height: 900 });
  await page.getByRole('radio', { name: 'Approve', exact: true }).check();
  await page.getByRole('button', { name: 'Complete task' }).click();
  await expect(page.getByText(L.en.done)).toBeVisible();
  await expect(page.getByRole('link', { name: /Back to/ })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/loan-approval-result-1440-en-v2.png`, fullPage: false });
});
