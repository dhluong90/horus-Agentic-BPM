import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { BOOKED_INSTANCE_ID, INCIDENT_INSTANCE_ID, KYC_INSTANCE_ID } from '../src/mocks/caseDemo';
import { INSTANCE_ID, MASKED_TASK_ID, RECOMMENDATIONS, REVIEW_TASK_ID } from '../src/mocks/processDemo';
import { createFakeProcessEngine, type ProcessSeed } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * The process screens at the two laptop sizes, in both themes and both languages (CKP-069, CKP-070, CKP-007):
 * the definition list, the loan process diagram with its boundary timer, and the review of the escalated agent
 * step: open, overriding, blocked by four eyes (the starter, the reporter) and with a masked answer. Each run checks
 * the fold (title and primary action visible at 1440x900, no sideways scrolling) and runs axe-core. The demo data is
 * the tenant's, in the tenant's language. Screenshots go to e2e/screenshots/.
 */

const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

async function mockEngines(page: Page, seed: ProcessSeed = {}, user = meHoa) {
  const processes = createFakeProcessEngine(seed);
  const rules = createFakeEngine(demoSeed());
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = rules.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: user.user.id, permissions: user.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = processes.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: user.user.id, permissions: user.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return processes;
}

interface Screen {
  name: string;
  hash: string;
  title: Record<Locale, string | RegExp>;
  /** The fake engine's seed beyond the language: who started the case, who reported the answer. */
  seed?: Omit<ProcessSeed, 'locale'>;
  /** The signed-in user, when not Hoa. */
  user?: typeof meHoa;
  /** Steps after the page opens, for example taking the review and starting an override. */
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  /** The element that must be visible without scrolling at 1440x900: the primary action, or the diagram. */
  focal: (page: Page, locale: Locale) => ReturnType<Page['locator']>;
}

const T = {
  start: { en: 'Start review', vi: 'Bắt đầu xem xét' },
  override: { en: 'Override', vi: 'Sửa' },
  record: { en: 'Record override', vi: 'Ghi nhận sửa đổi' },
  approve: { en: 'Approve answer', vi: 'Duyệt câu trả lời' },
  reason: { en: 'Reason', vi: 'Lý do' },
  review: { en: "Review the agent's answer", vi: 'Xem xét câu trả lời của tác tử AI' },
  loan: { en: 'Loan origination', vi: 'Khởi tạo khoản vay' },
  started: { en: 'You started this case, so someone else decides.', vi: 'Bạn đã khởi tạo hồ sơ này, nên người khác quyết định.' },
  reported: { en: 'You reported this answer, so someone else decides.', vi: 'Bạn đã gửi câu trả lời này, nên người khác quyết định.' },
  secondPerson: { en: 'A second person decides this answer, not you.', vi: 'Người thứ hai quyết định câu trả lời này, không phải bạn.' },
  masked: { en: 'Part of the answer is masked, so only an override is possible.', vi: 'Một phần câu trả lời đã được che, nên chỉ có thể sửa.' },
  cases: { en: 'Cases', vi: 'Hồ sơ' },
  timeline: { en: 'Timeline', vi: 'Dòng thời gian' },
  openTask: { en: 'Open the task', vi: 'Mở công việc' },
  kyc: { en: 'Customer onboarding (KYC)', vi: 'Mở khách hàng mới (KYC)' },
  dispute: { en: 'Card dispute', vi: 'Khiếu nại thẻ' },
  noCases: { en: 'No case of this process has started yet.', vi: 'Chưa có hồ sơ nào của quy trình này.' },
  incident: { en: 'The agent did not answer in time.', vi: 'Tác tử AI không trả lời kịp thời hạn.' },
  capture: { en: 'Capture application', vi: 'Nhập hồ sơ vay' },
  verify: { en: 'Verify identity documents', vi: 'Xác minh giấy tờ tuỳ thân' },
  noActivities: { en: 'This service does not report step activity yet.', vi: 'Dịch vụ này chưa cung cấp hoạt động theo từng bước.' },
  notFound: { en: 'This service has no case with this reference.', vi: 'Dịch vụ này không có hồ sơ với mã tham chiếu này.' },
  blocked: { en: 'Your permissions do not include working on tasks.', vi: 'Quyền của bạn không bao gồm xử lý công việc.' },
  pick: { en: 'Select a step to see what happened there.', vi: 'Chọn một bước để xem điều gì đã diễn ra.' },
} as const;

const caseTitle = (key: string) => ({ en: `Case ${key}`, vi: `Hồ sơ ${key}` });
const selectStep = (nodeId: string) => async (page: Page) => {
  await page.locator(`[data-node="${nodeId}"]`).click();
};

const SCREENS: readonly Screen[] = [
  {
    name: 'processes-list',
    hash: '#/processes',
    title: { en: 'Processes', vi: 'Quy trình' },
    focal: (page, locale) => page.getByRole('link', { name: T.loan[locale] }),
  },
  {
    name: 'definition-diagram',
    hash: '#/processes/loan-origination/3',
    title: T.loan,
    focal: (page) => page.locator('[data-node="booked"]'),
  },
  {
    name: 'review-open',
    hash: `#/tasks/${REVIEW_TASK_ID}`,
    title: T.review,
    focal: (page, locale) => page.getByRole('button', { name: T.start[locale] }),
  },
  {
    name: 'review-override',
    hash: `#/tasks/${REVIEW_TASK_ID}`,
    title: T.review,
    prepare: async (page, locale) => {
      await page.getByRole('button', { name: T.start[locale] }).click();
      await page.getByRole('button', { name: T.override[locale], exact: true }).click();
      await page.getByLabel('Recommendation').fill(RECOMMENDATIONS[locale][1]);
      await page.getByLabel('Risk score').fill('72');
    },
    focal: (page, locale) => page.getByRole('button', { name: T.record[locale] }),
  },
  {
    name: 'review-blocked-starter',
    hash: `#/tasks/${REVIEW_TASK_ID}`,
    title: T.review,
    seed: { startedBy: 'hoa' },
    focal: (page, locale) => page.getByText(T.started[locale]),
  },
  {
    name: 'review-blocked-reporter',
    hash: `#/tasks/${REVIEW_TASK_ID}`,
    title: T.review,
    seed: { reportedBy: 'hoa' },
    focal: (page, locale) => page.getByText(T.reported[locale]),
  },
  {
    name: 'review-masked',
    hash: `#/tasks/${MASKED_TASK_ID}`,
    title: T.review,
    focal: (page, locale) => page.getByRole('button', { name: T.record[locale] }),
  },
  {
    name: 'definition-cases',
    hash: '#/processes/loan-origination/3/cases',
    title: T.loan,
    focal: (page) => page.getByRole('link', { name: 'APP-1038' }),
  },
  {
    name: 'definition-cases-empty',
    hash: '#/processes/card-dispute/1/cases',
    title: T.dispute,
    focal: (page, locale) => page.getByText(T.noCases[locale]),
  },
  {
    name: 'instance-waiting',
    hash: `#/instances/${INSTANCE_ID}`,
    title: caseTitle('APP-1042'),
    focal: (page, locale) => page.getByRole('link', { name: T.openTask[locale] }),
  },
  {
    name: 'instance-step-completed',
    hash: `#/instances/${INSTANCE_ID}`,
    title: caseTitle('APP-1042'),
    prepare: selectStep('capture'),
    focal: (page) => page.locator('.inspector__summary'),
  },
  {
    name: 'instance-timeline',
    hash: `#/instances/${INSTANCE_ID}`,
    title: caseTitle('APP-1042'),
    prepare: async (page, locale) => { await page.getByRole('tab', { name: T.timeline[locale] }).click(); },
    focal: (page) => page.locator('.timeline tbody tr').first(),
  },
  {
    name: 'instance-incident',
    hash: `#/instances/${INCIDENT_INSTANCE_ID}`,
    title: caseTitle('APP-1045'),
    focal: (page, locale) => page.locator('.inspector__incident').getByText(T.incident[locale]),
  },
  {
    name: 'instance-kyc-step',
    hash: `#/instances/${KYC_INSTANCE_ID}`,
    title: caseTitle('KYC-2207'),
    prepare: selectStep('verify-docs'),
    focal: (page) => page.locator('.inspector__summary'),
  },
  {
    name: 'instance-completed',
    hash: `#/instances/${BOOKED_INSTANCE_ID}`,
    title: caseTitle('APP-1038'),
    focal: (page, locale) => page.getByText(T.pick[locale]),
  },
  {
    name: 'instance-task-not-allowed',
    hash: `#/instances/${INSTANCE_ID}`,
    title: caseTitle('APP-1042'),
    user: { ...meHoa, permissions: ['rules:read', 'processes:read'] },
    focal: (page, locale) => page.getByText(T.blocked[locale]),
  },
  {
    name: 'instance-no-activities',
    hash: `#/instances/${INSTANCE_ID}`,
    title: caseTitle('APP-1042'),
    seed: { activities: 'missing' },
    focal: (page, locale) => page.getByText(T.noActivities[locale]),
  },
  {
    name: 'instance-activities-error',
    hash: `#/instances/${INSTANCE_ID}`,
    title: caseTitle('APP-1042'),
    seed: { activities: 'failing' },
    focal: (page) => page.locator('.inspector .alert--error'),
  },
  {
    name: 'instance-not-found',
    hash: '#/instances/01JD4I0N0SUCH0000000000000',
    title: { en: /^Case$/, vi: /^Hồ sơ$/ },
    focal: (page, locale) => page.getByText(T.notFound[locale]),
  },
];

async function openScreen(page: Page, screen: Screen, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: Locale) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  await mockEngines(page, { ...screen.seed, locale }, screen.user);
  await page.goto(`./${screen.hash}`);
  await expect(page.getByRole('heading', { level: 1, name: screen.title[locale] })).toBeVisible();
  await expect(page.locator('.skeleton')).toHaveCount(0);
  if (screen.prepare) await screen.prepare(page, locale);
}

async function bottomOf(locator: ReturnType<Page['locator']>): Promise<number> {
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  return box.y + box.height;
}

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `proc-${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`TR_UI_03_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          await openScreen(page, screen, viewport, theme, locale);
          const focal = screen.focal(page, locale);
          await expect(focal).toBeVisible();
          expect(await bottomOf(focal)).toBeLessThanOrEqual(viewport.height);
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
          await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
        });
      }
    }
  }
}

for (const screen of SCREENS) {
  for (const theme of THEMES) {
    test(`TR_UI_03_axe_no_serious_violations_${screen.name}_${theme}`, async ({ page }) => {
      await openScreen(page, screen, { width: 1440, height: 900 }, theme, 'en');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
      expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
    });
  }
}

test('CKP_007_an_override_is_recorded_with_its_reason_by_keyboard_alone', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page);
  await page.goto(`./#/tasks/${REVIEW_TASK_ID}`);
  await expect(page.getByRole('heading', { level: 1, name: "Review the agent's answer" })).toBeVisible();

  const start = page.getByRole('button', { name: 'Start review' });
  for (let step = 0; step < 40; step += 1) {
    await page.keyboard.press('Tab');
    if (await start.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(start).toBeFocused();
  expect(await start.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');

  await page.getByRole('button', { name: 'Override', exact: true }).focus();
  await page.keyboard.press('Enter');
  await page.getByLabel('Recommendation').fill('DECLINE');
  await page.getByLabel('Reason').fill('Salary slips do not support the declared income');
  await page.getByRole('button', { name: 'Record override' }).focus();
  await page.keyboard.press('Enter');

  await expect(page.getByText(/Decided by hoa/)).toBeVisible();
  expect(engine.overrides).toEqual([expect.objectContaining({ reviewedBy: 'hoa', result: { riskScore: 38, recommendation: 'DECLINE', incomeVerified: true } })]);
});

test('CKP_069_the_diagram_chunk_loads_only_when_a_diagram_opens', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockEngines(page);
  const scripts: string[] = [];
  page.on('request', (request) => { if (request.resourceType() === 'script') scripts.push(new URL(request.url()).pathname); });
  await page.goto('./#/processes');
  await expect(page.getByRole('link', { name: 'Loan origination' })).toBeVisible();
  const before = scripts.length;
  await page.getByRole('link', { name: 'Loan origination' }).click();
  await expect(page.locator('[data-layout="auto"]')).toBeVisible();
  expect(scripts.slice(before).some((path) => /DefinitionDiagram/.test(path))).toBe(true);
});

for (const locale of LOCALES) {
  const fourEyes = async (page: Page, seed: Omit<ProcessSeed, 'locale'>, line: string) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await rememberLocale(page, locale);
    const engine = await mockEngines(page, { ...seed, locale });
    await page.goto(`./#/tasks/${REVIEW_TASK_ID}`);
    const dock = page.getByRole('region', { name: locale === 'en' ? 'Your decision' : 'Quyết định của bạn' });
    const reason = dock.getByText(line);
    await expect(reason).toBeVisible();
    // One line: the text box is no taller than one line of body text plus its padding.
    expect((await reason.boundingBox())?.height ?? 99).toBeLessThanOrEqual(40);
    const start = dock.getByRole('button', { name: T.start[locale] });
    await expect(start).toHaveAccessibleDescription(line);
    await expect(dock.getByRole('button', { name: T.approve[locale] })).toHaveCount(0);
    // The subtitle agrees with the line: a second person decides.
    await expect(page.getByText(T.secondPerson[locale])).toBeVisible();
    // Not actionable: marked disabled, drawn without the primary fill, and a click or Enter sends nothing.
    await expect(start).toHaveAttribute('aria-disabled', 'true');
    const look = await start.evaluate((element) => {
      const probe = document.createElement('span');
      probe.style.color = 'var(--primary-fill)';
      document.body.append(probe);
      const primary = getComputedStyle(probe).color;
      probe.remove();
      return { background: getComputedStyle(element).backgroundColor, primary, cursor: getComputedStyle(element).cursor };
    });
    expect(look.background).not.toBe(look.primary);
    expect(look.cursor).toBe('not-allowed');
    await start.click({ force: true });
    await start.focus();
    await page.keyboard.press('Enter');
    await expect(start).toHaveText(T.start[locale]);
    expect(engine.requests.some((request) => request.path.endsWith(':claim'))).toBe(false);
  };

  test(`CKP_007_the_starter_is_told_in_one_line_why_they_cannot_decide_${locale}`, async ({ page }) => {
    await fourEyes(page, { startedBy: 'hoa' }, T.started[locale]);
  });

  test(`CKP_007_the_reporter_is_told_in_one_line_why_they_cannot_decide_${locale}`, async ({ page }) => {
    await fourEyes(page, { reportedBy: 'hoa' }, T.reported[locale]);
  });

  test(`CKP_007_a_masked_answer_opens_on_the_override_and_says_why_in_one_line_${locale}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await rememberLocale(page, locale);
    await mockEngines(page, { locale });
    await page.goto(`./#/tasks/${MASKED_TASK_ID}`);
    const dock = page.getByRole('region', { name: locale === 'en' ? 'Your decision' : 'Quyết định của bạn' });
    const line = dock.getByText(T.masked[locale]);
    await expect(line).toBeVisible();
    expect((await line.boundingBox())?.height ?? 99).toBeLessThanOrEqual(40);
    const approve = dock.getByRole('button', { name: locale === 'en' ? 'Approve' : 'Duyệt', exact: true });
    await expect(approve).toBeDisabled();
    await expect(approve).toHaveAccessibleDescription(T.masked[locale]);
    await expect(dock.getByRole('button', { name: T.override[locale], exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(dock.getByRole('button', { name: T.record[locale] })).toBeEnabled();
    // The masked input is one chip with a copy action; no fingerprint text anywhere on the page.
    await expect(page.locator('main')).not.toContainText(/hmac|9f4a/);
  });
}

for (const viewport of VIEWPORTS) {
  test(`CKP_069_diagram_text_is_at_least_12px_at_fit_and_fit_uses_the_canvas_${String(viewport.height)}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockEngines(page);
    await page.goto('./#/processes/loan-origination/3');
    const svg = page.locator('[data-layout="auto"]');
    await expect(svg).toBeVisible();
    const sizes = await svg.evaluate((element) => {
      const scale = element.getBoundingClientRect().width / (element as SVGSVGElement).viewBox.baseVal.width;
      const texts = Array.from(element.querySelectorAll('text')).filter((text) => text.textContent.trim() !== '' && !text.classList.contains('dg-node__glyph'));
      const frame = element.parentElement as HTMLElement;
      return {
        smallest: Math.min(...texts.map((text) => parseFloat(getComputedStyle(text).fontSize) * scale)),
        truncated: texts.some((text) => text.textContent.includes('…')),
        svgWidth: element.getBoundingClientRect().width,
        frameWidth: frame.clientWidth,
        scrolls: frame.scrollWidth > frame.clientWidth + 1,
      };
    });
    expect(sizes.smallest).toBeGreaterThanOrEqual(12);
    expect(sizes.truncated).toBe(false);
    // Fit fills the canvas's width, or the drawing scrolls inside it at 100 % rather than shrink its text.
    expect(sizes.scrolls || sizes.svgWidth >= sizes.frameWidth - 24).toBe(true);
    await expect(page.locator('[data-node="no-documents"]')).toContainText('Timer');
  });
}
