import type { DisplayNames } from '../api/displayNames';

/*
 * The host's display names for the task queue demo (#415, #442): roles, groups and users of tenant bank-a, in each
 * language the tenant has titles for. A role the host has no title for is left out, so the cockpit shows its key
 * in words; `operations-supervisor` is that role here, in both languages.
 */

type Names = Pick<DisplayNames, 'roles' | 'groups' | 'users'>;

export const DEMO_NAMES: Readonly<Record<'en' | 'vi', Names>> = {
  en: {
    roles: { 'credit-officer': 'Credit officer', 'credit-approver': 'Credit approver', COMPLIANCE_REVIEWER: 'Compliance reviewer', CREDIT_OFFICER: 'Credit officer', SENIOR_CREDIT_OFFICER: 'Senior credit officer' },
    groups: { 'loans-operations': 'Loans operations' },
    users: { an: 'An Nguyen', hoa: 'Hoa Tran', sau: 'Sau Le', lan: 'Lan Pham', minh: 'Minh Vo' },
  },
  vi: {
    roles: { 'credit-officer': 'Cán bộ tín dụng', 'credit-approver': 'Người duyệt tín dụng', COMPLIANCE_REVIEWER: 'Chuyên viên tuân thủ', CREDIT_OFFICER: 'Cán bộ tín dụng', SENIOR_CREDIT_OFFICER: 'Cán bộ tín dụng cao cấp' },
    groups: { 'loans-operations': 'Vận hành tín dụng' },
    users: { an: 'Nguyễn Văn An', hoa: 'Trần Thị Hoa', sau: 'Lê Văn Sáu', lan: 'Phạm Thị Lan', minh: 'Võ Quang Minh' },
  },
};

const pick = (known: Readonly<Record<string, string>>, ids: string | null) =>
  Object.fromEntries((ids ?? '').split(',').flatMap((id) => (known[id] !== undefined ? [[id, known[id]]] : [])));

/** The answer of GET /api/bpm/display-names for a request URL and Accept-Language, as the host's default would give it. */
export function displayNamesAnswer(url: string, acceptLanguage: string | null | undefined): DisplayNames {
  const language = (acceptLanguage ?? '').toLowerCase().startsWith('vi') ? 'vi' : 'en';
  const query = new URL(url, 'http://host').searchParams;
  const known = DEMO_NAMES[language];
  return { language, roles: pick(known.roles, query.get('roles')), groups: pick(known.groups, query.get('groups')), users: pick(known.users, query.get('users')) };
}
