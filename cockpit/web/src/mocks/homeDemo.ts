import type { AuditOptions, StoredDecision } from './auditEngine';
import { demoDecisions } from './auditEngine';
import type { ProcessSeed } from './processEngine';
import { demoSeed, type StoredVersion } from './ruleEngine';
import { QUEUE_NOW, type QueueSeedTask } from './taskQueueDemo';

/*
 * Demo data of Home for the dev server (?scenario=home-operations): the operations lead's own tasks on the engine
 * clock QUEUE_NOW (21 Nov 2026, 09:30 in Hanoi), requests waiting for her review, and today's decisions by hour.
 */

const NOW = Date.parse(QUEUE_NOW);
const at = (hours: number) => new Date(NOW + hours * 3_600_000).toISOString();

const task = (n: number, name: { en: string; vi: string }, key: string, dueInHours: number): QueueSeedTask => ({
  id: `01JD6H0ME${String(n).padStart(17, '0')}`, name, nodeId: 'credit-review', businessKey: key, assignee: 'user:daniel', status: 'OPEN', claimedBy: null,
  dueAt: at(dueInHours), createdAt: at(-72), fourEyes: false, startedBy: 'lan',
});

const TASKS: readonly QueueSeedTask[] = [
  task(1, { en: 'Underwrite loan for Emily Carter', vi: 'Thẩm định khoản vay của Emily Carter' }, 'LN-2026-0161', 2),
  task(2, { en: 'Reconcile end-of-day card transactions', vi: 'Đối soát giao dịch thẻ cuối ngày' }, 'CD-2026-2140', 5.5),
  task(3, { en: 'Resolve double-charge dispute', vi: 'Xử lý khiếu nại bị trừ tiền hai lần' }, 'DS-2026-0088', -26),
  task(4, { en: 'Renew credit limit for Nathan Vega', vi: 'Gia hạn hạn mức tín dụng của Nathan Vega' }, 'CL-2026-0412', -50),
  task(5, { en: 'Review KYC refresh for Tran Co.', vi: 'Rà soát cập nhật KYC của Công ty Trần' }, 'KY-2026-0310', 3),
  task(6, { en: 'Approve fee waiver for Pham Lan', vi: 'Duyệt miễn phí cho Phạm Lan' }, 'FW-2026-0071', 6),
  task(7, { en: 'Check collateral papers for LN-0158', vi: 'Kiểm tra giấy tờ tài sản bảo đảm LN-0158' }, 'LN-2026-0158', 7),
];

export function homeDemoProcessSeed(locale: 'en' | 'vi'): ProcessSeed {
  return { locale, extraTasks: TASKS.map((item) => ({ ...item, name: { en: item.name.en, vi: item.name.vi } })) };
}

/** Pending requests from colleagues beside the demo ones, so the approvals card has four rows. */
export function homeDemoVersions(): StoredVersion[] {
  const [first, pending, third] = demoSeed();
  if (first === undefined || pending === undefined || third === undefined) return demoSeed();
  const request = (ruleSet: string, requestedBy: string, requestedAt: string): StoredVersion => ({
    ...structuredClone(pending), ruleSet, version: 1, createdBy: requestedBy, requestedBy, requestedAt, createdAt: requestedAt,
    history: [
      { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: requestedBy, reason: null, lockVersion: 0, occurredAt: requestedAt },
      { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: requestedBy, reason: null, lockVersion: 1, occurredAt: requestedAt },
    ],
  });
  return [first, pending, third, request('transfer-limit', 'hoa', '2026-11-21T01:58:00Z'), request('gold-card-fee', 'sau', '2026-11-20T09:30:00Z')];
}

const PER_HOUR = [42, 68, 91, 77, 53, 64, 88, 112, 96, 71] as const;

/** Today's decisions: ten working hours (08:00 to 17:59 in Hanoi, the engine clock's zone), split over two rule sets. */
export function homeDemoAudit(): AuditOptions {
  const today: StoredDecision[] = PER_HOUR.flatMap((count, slot) => Array.from({ length: count }, (_, n): StoredDecision => {
    const cardFee = n % 5 < 3;
    const minute = Math.min(59, Math.floor((n * 60) / count));
    return {
      decisionId: `01KF4H${String(slot).padStart(2, '0')}${String(n).padStart(18, '0')}`, ruleSet: cardFee ? 'card-fee' : 'kyc-risk', version: 1, outcome: 'MATCHED', errorCode: null,
      inputs: { tier: 'GOLD' }, outputs: { fee: 0 }, matchedRuleIds: [cardFee ? 'R3' : 'K2'], correlationId: null,
      evaluatedAt: `2026-11-21T${String(1 + slot).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00Z`,
    };
  }));
  return { decisions: [...demoDecisions(), ...today] };
}
