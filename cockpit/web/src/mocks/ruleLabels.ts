/*
 * The author's display labels in the seed rule sets, in both languages, as the rule set's `label` carries them
 * (#392). A rule set whose clause has no label for a language shows the English-form name: a known limit of
 * the engine label, which holds the author's text and which the cockpit never translates itself.
 */
export type ClauseLabel = Readonly<Record<'en' | 'vi', string>>;

const LABELS = {
  tier: { en: 'Tier', vi: 'Hạng' },
  balance: { en: 'Balance', vi: 'Số dư' },
  txCount: { en: 'Tx count', vi: 'Giao dịch' },
  fee: { en: 'Fee', vi: 'Phí' },
  waived: { en: 'Waived', vi: 'Miễn phí' },
  monthlySpend: { en: 'Monthly spend', vi: 'Chi tiêu hàng tháng' },
  rate: { en: 'Rate', vi: 'Tỷ lệ' },
  cap: { en: 'Cap', vi: 'Mức trần' },
  channel: { en: 'Channel', vi: 'Kênh' },
  amount: { en: 'Amount', vi: 'Số tiền' },
  country: { en: 'Country', vi: 'Quốc gia' },
  monthlyIncome: { en: 'Monthly income', vi: 'Thu nhập hàng tháng' },
  risk: { en: 'Risk', vi: 'Rủi ro' },
  nationalId: { en: 'National ID', vi: 'Số định danh' },
  segment: { en: 'Segment', vi: 'Phân khúc' },
  route: { en: 'Route', vi: 'Tuyến xử lý' },
} as const satisfies Record<string, ClauseLabel>;

export function labelOf(name: keyof typeof LABELS): ClauseLabel {
  return LABELS[name];
}
