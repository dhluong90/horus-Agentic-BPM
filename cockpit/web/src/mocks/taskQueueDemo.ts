/*
 * Demo data of the task queue (CKP-046 to CKP-048): loan origination tasks of tenant bank-a on the engine clock
 * QUEUE_NOW (21 Nov 2026, 09:30 in Hanoi), and the host's user directory that the engine asks before a hand-over.
 * Names are the tenant's, in the tenant's language; ids, roles and groups are identifiers and stay as they are.
 */

export const QUEUE_NOW = '2026-11-21T02:30:00Z';

export interface QueueSeedTask {
  id: string;
  name: { en: string; vi: string };
  nodeId: string;
  businessKey: string;
  assignee: string;
  status: 'OPEN' | 'CLAIMED';
  claimedBy: string | null;
  dueAt: string | null;
  createdAt: string;
  fourEyes: boolean;
  /** Who started the case: four eyes keeps them from working or taking the task. */
  startedBy: string;
}

export const QUEUE_TASKS = {
  creditReviewOverdue: '01JD5Q1CRED1TREV1EW0000T01',
  creditReview: '01JD5Q2CRED1TREV1EW0000T02',
  incomeCheck: '01JD5Q3VER1FY1NC0ME0000T03',
  myCreditReview: '01JD5Q4CRED1TREV1EW0000T04',
  approveLimit: '01JD5Q5APPR0VE11M1T0000T05',
  collateral: '01JD5Q6C011ATERA10000000T6',
  offerLetter: '01JD5Q70FFER1ETTER000000T7',
  approveOwnCase: '01JD5Q8APPR0VE0WNCASE000T8',
} as const;

export const QUEUE_SEED: readonly QueueSeedTask[] = [
  {
    id: QUEUE_TASKS.creditReviewOverdue, name: { en: 'Credit review', vi: 'Thẩm định tín dụng' }, nodeId: 'credit-review', businessKey: 'LN-2026-0142',
    assignee: 'role:credit-officer', status: 'OPEN', claimedBy: null, dueAt: '2026-11-21T01:00:00Z', createdAt: '2026-11-20T02:10:00Z', fourEyes: false, startedBy: 'lan',
  },
  {
    id: QUEUE_TASKS.myCreditReview, name: { en: 'Credit review', vi: 'Thẩm định tín dụng' }, nodeId: 'credit-review', businessKey: 'LN-2026-0139',
    assignee: 'role:credit-officer', status: 'CLAIMED', claimedBy: 'an', dueAt: '2026-11-21T04:00:00Z', createdAt: '2026-11-20T01:40:00Z', fourEyes: false, startedBy: 'lan',
  },
  {
    id: QUEUE_TASKS.creditReview, name: { en: 'Credit review', vi: 'Thẩm định tín dụng' }, nodeId: 'credit-review', businessKey: 'LN-2026-0145',
    assignee: 'role:credit-officer', status: 'OPEN', claimedBy: null, dueAt: '2026-11-21T07:00:00Z', createdAt: '2026-11-20T08:25:00Z', fourEyes: false, startedBy: 'lan',
  },
  {
    id: QUEUE_TASKS.collateral, name: { en: 'Collateral valuation', vi: 'Định giá tài sản bảo đảm' }, nodeId: 'collateral-valuation', businessKey: 'LN-2026-0128',
    assignee: 'role:credit-officer', status: 'CLAIMED', claimedBy: 'hoa', dueAt: '2026-11-20T09:00:00Z', createdAt: '2026-11-18T03:00:00Z', fourEyes: false, startedBy: 'lan',
  },
  {
    id: QUEUE_TASKS.incomeCheck, name: { en: 'Verify income documents', vi: 'Xác minh chứng từ thu nhập' }, nodeId: 'verify-income', businessKey: 'LN-2026-0147',
    assignee: 'group:loans-operations', status: 'OPEN', claimedBy: null, dueAt: '2026-11-24T03:00:00Z', createdAt: '2026-11-21T01:05:00Z', fourEyes: false, startedBy: 'lan',
  },
  {
    id: QUEUE_TASKS.approveLimit, name: { en: 'Approve credit limit', vi: 'Phê duyệt hạn mức tín dụng' }, nodeId: 'approve-limit', businessKey: 'LN-2026-0131',
    assignee: 'role:credit-approver', status: 'OPEN', claimedBy: null, dueAt: '2026-11-25T10:00:00Z', createdAt: '2026-11-20T06:30:00Z', fourEyes: true, startedBy: 'hoa',
  },
  {
    id: QUEUE_TASKS.offerLetter, name: { en: 'Prepare offer letter', vi: 'Soạn thư chào khoản vay' }, nodeId: 'prepare-offer', businessKey: 'LN-2026-0120',
    assignee: 'user:an', status: 'OPEN', claimedBy: null, dueAt: null, createdAt: '2026-11-19T09:00:00Z', fourEyes: false, startedBy: 'lan',
  },
  {
    id: '01JD5Q8APPR0VE0WNCASE000T8', name: { en: 'Approve credit limit', vi: 'Phê duyệt hạn mức tín dụng' }, nodeId: 'approve-limit', businessKey: 'LN-2026-0150',
    assignee: 'role:credit-approver', status: 'OPEN', claimedBy: null, dueAt: '2026-11-26T10:00:00Z', createdAt: '2026-11-21T01:20:00Z', fourEyes: true, startedBy: 'sau',
  },
];

/** The host's user directory: the roles and groups each user holds in tenant bank-a. */
export const DIRECTORY: Readonly<Record<string, { roles: readonly string[]; groups: readonly string[] }>> = {
  an: { roles: ['credit-officer'], groups: ['loans-operations'] },
  hoa: { roles: ['credit-officer', 'credit-approver', 'COMPLIANCE_REVIEWER'], groups: ['loans-operations'] },
  sau: { roles: ['credit-officer', 'credit-approver', 'operations-supervisor'], groups: ['loans-operations'] },
  lan: { roles: ['COMPLIANCE_REVIEWER'], groups: [] },
  minh: { roles: [], groups: [] },
};
