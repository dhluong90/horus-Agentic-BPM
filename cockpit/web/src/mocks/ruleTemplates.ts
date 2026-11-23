/*
 * The four templates the engine ships (rule-engine-core, RLE-197), as GET /rule-set-templates answers them: label and
 * description in English and Vietnamese, hit policy, inputs with the values to pick from, outputs, and the first rows.
 */

type Cell = string | number | boolean;
export interface TemplateAnswer {
  id: string;
  labels: Record<'en' | 'vi', string>;
  descriptions: Record<'en' | 'vi', string>;
  source: 'BUILT_IN' | 'HOST';
  hitPolicy: 'first' | 'unique' | 'collect';
  inputs: { name: string; type: string; allowed: string[] }[];
  outputs: { name: string; type: string }[];
  rows: { id: string; when: Record<string, string>; then: Record<string, Cell> }[];
}

export const TEMPLATES: readonly TemplateAnswer[] = [
  {
    id: 'fee-by-tier', source: 'BUILT_IN', hitPolicy: 'first',
    labels: { en: 'Fee by tier', vi: 'Phí theo hạng' },
    descriptions: {
      en: "A monthly fee that depends on the customer's tier. The first row that matches wins.",
      vi: 'Phí hằng tháng phụ thuộc vào hạng của khách hàng. Dòng khớp đầu tiên được áp dụng.',
    },
    inputs: [{ name: 'tier', type: 'string', allowed: ['GOLD', 'PLATINUM', 'CLASSIC'] }],
    outputs: [{ name: 'fee', type: 'money' }],
    rows: [
      { id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } },
      { id: 'R2', when: { tier: "== 'PLATINUM'" }, then: { fee: '20000 VND' } },
      { id: 'R3', when: { tier: "== 'CLASSIC'" }, then: { fee: '50000 VND' } },
      { id: 'R4', when: {}, then: { fee: '50000 VND' } },
    ],
  },
  {
    id: 'eligibility-with-reason', source: 'BUILT_IN', hitPolicy: 'first',
    labels: { en: 'Eligibility with reason codes', vi: 'Điều kiện đủ tư cách kèm mã lý do' },
    descriptions: {
      en: 'Decides whether a customer is eligible and says why not. The first row that matches wins.',
      vi: 'Quyết định khách hàng có đủ điều kiện hay không và nêu lý do. Dòng khớp đầu tiên được áp dụng.',
    },
    inputs: [{ name: 'age', type: 'integer', allowed: [] }, { name: 'monthlyIncome', type: 'money', allowed: [] }],
    outputs: [{ name: 'eligible', type: 'boolean' }, { name: 'reason', type: 'string' }],
    rows: [
      { id: 'R1', when: { age: '< 18' }, then: { eligible: false, reason: "'UNDER_AGE'" } },
      { id: 'R2', when: { monthlyIncome: '< 10000000 VND' }, then: { eligible: false, reason: "'INCOME_TOO_LOW'" } },
      { id: 'R3', when: {}, then: { eligible: true, reason: "'OK'" } },
    ],
  },
  {
    id: 'limit-by-segment', source: 'BUILT_IN', hitPolicy: 'unique',
    labels: { en: 'Limit by segment', vi: 'Hạn mức theo phân khúc' },
    descriptions: {
      en: 'A daily transfer limit for each customer segment. Exactly one row must match.',
      vi: 'Hạn mức chuyển tiền mỗi ngày cho từng phân khúc khách hàng. Phải có đúng một dòng khớp.',
    },
    inputs: [{ name: 'segment', type: 'string', allowed: ['RETAIL', 'PREMIUM', 'BUSINESS'] }],
    outputs: [{ name: 'dailyLimit', type: 'money' }],
    rows: [
      { id: 'R1', when: { segment: "== 'RETAIL'" }, then: { dailyLimit: '20000000 VND' } },
      { id: 'R2', when: { segment: "== 'PREMIUM'" }, then: { dailyLimit: '100000000 VND' } },
      { id: 'R3', when: { segment: "== 'BUSINESS'" }, then: { dailyLimit: '500000000 VND' } },
    ],
  },
  {
    id: 'blank', source: 'BUILT_IN', hitPolicy: 'first',
    labels: { en: 'Blank', vi: 'Trống' },
    descriptions: {
      en: 'One input, one output and one default row to start from.',
      vi: 'Một đầu vào, một đầu ra và một dòng mặc định để bắt đầu.',
    },
    inputs: [{ name: 'input1', type: 'string', allowed: [] }],
    outputs: [{ name: 'result', type: 'string' }],
    rows: [{ id: 'R1', when: {}, then: { result: "'TBD'" } }],
  },
];
