/*
 * Demo data of the mock Process Engine: three definitions as the engine exports them (BPMN 2.0 with the horus
 * extension namespace), one case escalated by its agent step, and the review task it created. The loan process
 * carries a boundary timer, a node type this cockpit does not draw, as a newer host would send it (CKP-070).
 */

const HEAD = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                  id="Definitions_1" targetNamespace="urn:horus-bpm:demo">`;

const flow = (id: string, source: string, target: string, name?: string, condition?: string) =>
  condition === undefined
    ? `<bpmn:sequenceFlow id="${id}" sourceRef="${source}" targetRef="${target}"${name ? ` name="${name}"` : ''}/>`
    : `<bpmn:sequenceFlow id="${id}" sourceRef="${source}" targetRef="${target}"${name ? ` name="${name}"` : ''}><bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${condition}</bpmn:conditionExpression></bpmn:sequenceFlow>`;

export const LOAN_ORIGINATION_BPMN = `${HEAD}
  <bpmn:process id="loan-origination" name="Loan origination" isExecutable="true">
    <bpmn:startEvent id="received" name="Application received"/>
    <bpmn:userTask id="capture" name="Capture application" horus:assignee="role:LOAN_OFFICER">
      <bpmn:dataOutputAssociation id="capture-out"><bpmn:sourceRef>capture-form</bpmn:sourceRef><bpmn:targetRef>application-ref</bpmn:targetRef></bpmn:dataOutputAssociation>
    </bpmn:userTask>
    <bpmn:boundaryEvent id="no-documents" name="No documents in 2 days" attachedToRef="capture" cancelActivity="false">
      <bpmn:timerEventDefinition><bpmn:timeDuration>P2D</bpmn:timeDuration></bpmn:timerEventDefinition>
    </bpmn:boundaryEvent>
    <bpmn:serviceTask id="remind" name="Remind applicant" horus:handler="loans.remindApplicant"/>
    <bpmn:endEvent id="reminded" name="Reminder sent"/>
    <bpmn:serviceTask id="assess-documents" name="Assess documents" horus:type="agentTask" horus:agent="doc-assessor" horus:timeout="PT30S">
      <bpmn:dataInputAssociation id="assess-in"><bpmn:sourceRef>application-ref</bpmn:sourceRef><bpmn:targetRef>application-input</bpmn:targetRef></bpmn:dataInputAssociation>
      <bpmn:extensionElements>
        <horus:input name="applicationId" value="\${instance.applicationId}"/>
        <horus:input name="nationalId" value="\${instance.nationalId}" mask="true"/>
        <horus:guardrail decision="agent-output-guardrails" onViolation="route-to-human" reviewer="role:COMPLIANCE_REVIEWER"/>
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:businessRuleTask id="credit-decision" name="Credit decision" horus:ruleSet="credit-limit">
      <bpmn:dataInputAssociation id="decision-in"><bpmn:sourceRef>bureau-ref</bpmn:sourceRef><bpmn:targetRef>bureau-input</bpmn:targetRef></bpmn:dataInputAssociation>
    </bpmn:businessRuleTask>
    <bpmn:exclusiveGateway id="approved" name="Approved?" default="to-decline"/>
    <bpmn:serviceTask id="book-loan" name="Book loan" horus:handler="loans.book"/>
    <bpmn:boundaryEvent id="book-undo" attachedToRef="book-loan"><bpmn:compensateEventDefinition/></bpmn:boundaryEvent>
    <bpmn:serviceTask id="reverse-booking" name="Reverse booking" isForCompensation="true" horus:handler="loans.reverseBooking"/>
    <bpmn:association id="book-undo-link" associationDirection="One" sourceRef="book-undo" targetRef="reverse-booking"/>
    <bpmn:scriptTask id="compute-offer" name="Compute offer terms" horus:resultVariable="offerTerms"><bpmn:script>decision.limit * 12</bpmn:script>
      <bpmn:dataOutputAssociation id="offer-out"><bpmn:sourceRef>offer-result</bpmn:sourceRef><bpmn:targetRef>offer-ref</bpmn:targetRef></bpmn:dataOutputAssociation>
    </bpmn:scriptTask>
    <bpmn:serviceTask id="send-offer" name="Send offer" horus:handler="loans.sendOffer">
      <bpmn:dataInputAssociation id="send-in"><bpmn:sourceRef>offer-ref</bpmn:sourceRef><bpmn:targetRef>offer-input</bpmn:targetRef></bpmn:dataInputAssociation>
    </bpmn:serviceTask>
    <bpmn:dataObject id="application" name="Loan application"/>
    <bpmn:dataObject id="offer" name="Offer terms"/>
    <bpmn:dataObjectReference id="application-ref" name="Loan application" dataObjectRef="application"/>
    <bpmn:dataObjectReference id="offer-ref" name="Offer terms" dataObjectRef="offer"/>
    <bpmn:dataStoreReference id="bureau-ref" name="Credit bureau"/>
    <bpmn:endEvent id="booked" name="Loan booked"/>
    <bpmn:serviceTask id="send-decline" name="Send decline letter" horus:handler="loans.sendDecline"/>
    <bpmn:endEvent id="declined" name="Declined"/>
    ${flow('f1', 'received', 'capture')}
    ${flow('f2', 'capture', 'assess-documents')}
    ${flow('f3', 'assess-documents', 'credit-decision')}
    ${flow('f4', 'credit-decision', 'approved')}
    ${flow('to-book', 'approved', 'book-loan', 'Approved', 'decision.approved == true')}
    ${flow('to-decline', 'approved', 'send-decline', 'Declined')}
    ${flow('f5', 'book-loan', 'compute-offer')}
    ${flow('f5b', 'compute-offer', 'send-offer')}
    ${flow('f6', 'send-offer', 'booked')}
    ${flow('f7', 'send-decline', 'declined')}
    ${flow('f8', 'no-documents', 'remind')}
    ${flow('f9', 'remind', 'reminded')}
  </bpmn:process>
</bpmn:definitions>`;

export const KYC_ONBOARDING_BPMN = `${HEAD}
  <bpmn:process id="kyc-onboarding" name="Customer onboarding (KYC)" isExecutable="true">
    <bpmn:startEvent id="start" name="Onboarding requested"/>
    <bpmn:parallelGateway id="split" name="Checks in parallel"/>
    <bpmn:serviceTask id="screen-sanctions" name="Screen sanctions lists" horus:handler="kyc.screen"/>
    <bpmn:serviceTask id="verify-docs" name="Verify identity documents" horus:type="agentTask" horus:agent="id-verifier"/>
    <bpmn:parallelGateway id="join" name="Both checks done"/>
    <bpmn:businessRuleTask id="risk-rating" name="Rate customer risk" horus:ruleSet="kyc-risk"/>
    <bpmn:exclusiveGateway id="high-risk" name="High risk?" default="to-open"/>
    <bpmn:userTask id="approve" name="Approve high-risk customer" horus:assignee="role:CREDIT_OFFICER" horus:fourEyes="true"/>
    <bpmn:intermediateCatchEvent id="cooling" name="Wait one day">
      <bpmn:timerEventDefinition><bpmn:timeDuration>P1D</bpmn:timeDuration></bpmn:timerEventDefinition>
    </bpmn:intermediateCatchEvent>
    <bpmn:serviceTask id="open-account" name="Open account" horus:handler="kyc.openAccount"/>
    <bpmn:endEvent id="end" name="Customer onboarded"/>
    ${flow('k1', 'start', 'split')}
    ${flow('k2', 'split', 'screen-sanctions')}
    ${flow('k3', 'split', 'verify-docs')}
    ${flow('k4', 'screen-sanctions', 'join')}
    ${flow('k5', 'verify-docs', 'join')}
    ${flow('k6', 'join', 'risk-rating')}
    ${flow('k7', 'risk-rating', 'high-risk')}
    ${flow('to-approve', 'high-risk', 'approve', 'High', 'risk.band == "HIGH"')}
    ${flow('to-open', 'high-risk', 'open-account', 'Standard')}
    ${flow('k8', 'approve', 'cooling')}
    ${flow('k9', 'cooling', 'open-account')}
    ${flow('k10', 'open-account', 'end')}
  </bpmn:process>
</bpmn:definitions>`;

/*
 * Version 3 of the onboarding process, waiting for approval: a fraud check after the document check, the high-risk
 * approval moved to senior credit officers, and the one-day wait before opening the account removed (CKP-024).
 */
export const KYC_ONBOARDING_V3_BPMN = KYC_ONBOARDING_BPMN
  .replace(flow('k5', 'verify-docs', 'join'), `${flow('k5a', 'verify-docs', 'fraud-check')}\n    ${flow('k5b', 'fraud-check', 'join')}`)
  .replace('<bpmn:parallelGateway id="join"', '<bpmn:serviceTask id="fraud-check" name="Check for fraud" horus:handler="kyc.fraudCheck"/>\n    <bpmn:parallelGateway id="join"')
  .replace('horus:assignee="role:CREDIT_OFFICER"', 'horus:assignee="role:SENIOR_CREDIT_OFFICER"')
  .replace(/\s*<bpmn:intermediateCatchEvent id="cooling"[\s\S]*?<\/bpmn:intermediateCatchEvent>/, '')
  .replace(flow('k8', 'approve', 'cooling'), flow('k8', 'approve', 'open-account'))
  .replace(/\s*<bpmn:sequenceFlow id="k9"[^>]*\/>/, '');

export const CARD_DISPUTE_BPMN = `${HEAD}
  <bpmn:process id="card-dispute" name="Card dispute" isExecutable="true">
    <bpmn:startEvent id="start" name="Dispute raised"/>
    <bpmn:userTask id="review" name="Review dispute" horus:assignee="role:DISPUTE_OFFICER"/>
    <bpmn:serviceTask id="chargeback" name="Raise chargeback" horus:handler="cards.chargeback"/>
    <bpmn:endEvent id="end" name="Dispute closed"/>
    ${flow('c1', 'start', 'review')}
    ${flow('c2', 'review', 'chargeback')}
    ${flow('c3', 'chargeback', 'end')}
  </bpmn:process>
</bpmn:definitions>`;

export interface DemoDefinition {
  key: string;
  name: string;
  version: number;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'RETIRED';
  effectiveVersion: number | null;
  nodeCount: number;
  lastChangedAt: string;
  bpmn: string;
  /** SHA-256 of the canonical form, 64 lower-case hexadecimal characters. */
  definitionHash?: string;
  createdBy?: string;
  requestedBy?: string;
  requestedAt?: string;
  /** On a pending version, the requested date it takes effect. */
  effectiveFrom?: string;
}

export const KYC_V2_HASH = '3f1c0b9e5d7a4b2c8e6f0a1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3';
export const KYC_V3_HASH = '9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b';

export const DEMO_DEFINITIONS: readonly DemoDefinition[] = [
  { key: 'card-dispute', name: 'Card dispute', version: 1, status: 'DRAFT', effectiveVersion: null, nodeCount: 4, lastChangedAt: '2026-11-19T07:45:00Z', bpmn: CARD_DISPUTE_BPMN },
  { key: 'kyc-onboarding', name: 'Customer onboarding (KYC)', version: 2, status: 'ACTIVE', effectiveVersion: 2, nodeCount: 11, lastChangedAt: '2026-11-12T02:10:00Z', bpmn: KYC_ONBOARDING_BPMN, definitionHash: KYC_V2_HASH },
  {
    key: 'kyc-onboarding', name: 'Customer onboarding (KYC)', version: 3, status: 'PENDING_APPROVAL', effectiveVersion: 2, nodeCount: 11, lastChangedAt: '2026-11-20T08:40:00Z',
    bpmn: KYC_ONBOARDING_V3_BPMN, definitionHash: KYC_V3_HASH, createdBy: 'lan', requestedBy: 'lan', requestedAt: '2026-11-20T08:40:00Z', effectiveFrom: '2027-05-31T17:00:00Z',
  },
  { key: 'loan-origination', name: 'Loan origination', version: 3, status: 'ACTIVE', effectiveVersion: 3, nodeCount: 13, lastChangedAt: '2026-11-18T09:20:00Z', bpmn: LOAN_ORIGINATION_BPMN },
];

/** The escalated case: instance I9 of loan-origination v3, started by Lan, its agent answer refused by rule R2. */
export const REVIEW_TASK_ID = '01JD4T4REV1EW000000000AT04';
export const MASKED_TASK_ID = '01JD4T5REV1EW000000000AT05';
export const FORM_TASK_ID = '01JD4T6F0RM00000000000UT06';
export const INSTANCE_ID = '01JD4I9NSTANCE0000000000I9';

export const REVIEW_CONTEXT = {
  code: 'GUARDRAIL_VIOLATION',
  reasons: ['Declared income is 38% above the income on the salary slips', 'Recommendation APPROVE needs income verified within 20%'],
  ruleIds: ['R2'],
  guardrailDecisionId: '01JD4G2DEC1S10N0000000R2X7',
  inputs: {
    applicationId: 'APP-1042',
    requestedAmount: { currency: 'VND', amount: 850000000 },
    declaredMonthlyIncome: { currency: 'VND', amount: 68000000 },
    nationalId: 'hmac:k2026:9f4a1c7e2b8d4f60a1c3e5b7d9f0a2c4',
    documents: ['salary-slip-2026-09.pdf', 'salary-slip-2026-10.pdf'],
  },
  output: { riskScore: 38, recommendation: 'APPROVE', incomeVerified: true },
  outputFields: 3,
  attemptId: '4S2Q8M0Z7XK1D9V3B6N5R2T8WA',
  reportedBy: 'engine:loans-7f9c',
} as const;

/*
 * The same demo as a Vietnamese tenant authors it: process, step and flow names, the guardrail's reasons and the
 * answer's words are the tenant's data, so a Vietnamese bank sees them in Vietnamese. Field names stay as the
 * agent's output schema declares them.
 */
const VI_NAMES: Readonly<Record<string, string>> = {
  'Loan origination': 'Khởi tạo khoản vay',
  'Application received': 'Nhận hồ sơ vay',
  'Capture application': 'Nhập hồ sơ vay',
  'No documents in 2 days': 'Chưa có chứng từ sau 2 ngày',
  'Remind applicant': 'Nhắc khách hàng',
  'Reminder sent': 'Đã gửi lời nhắc',
  'Assess documents': 'Thẩm định chứng từ',
  'Credit decision': 'Quyết định tín dụng',
  'Approved?': 'Được duyệt?',
  'Book loan': 'Ghi sổ khoản vay',
  'Reverse booking': 'Đảo bút toán ghi sổ',
  'Send offer': 'Gửi đề nghị',
  'Loan booked': 'Đã ghi sổ',
  'Send decline letter': 'Gửi thư từ chối',
  Declined: 'Từ chối',
  Approved: 'Duyệt',
  'Customer onboarding (KYC)': 'Mở khách hàng mới (KYC)',
  'Onboarding requested': 'Yêu cầu mở khách hàng',
  'Checks in parallel': 'Kiểm tra song song',
  'Screen sanctions lists': 'Sàng lọc danh sách cấm vận',
  'Verify identity documents': 'Xác minh giấy tờ tuỳ thân',
  'Both checks done': 'Xong cả hai kiểm tra',
  'Rate customer risk': 'Xếp hạng rủi ro khách hàng',
  'High risk?': 'Rủi ro cao?',
  'Approve high-risk customer': 'Duyệt khách hàng rủi ro cao',
  'Wait one day': 'Chờ một ngày',
  'Open account': 'Mở tài khoản',
  'Customer onboarded': 'Đã mở khách hàng',
  'Check for fraud': 'Kiểm tra gian lận',
  'Senior credit approval': 'Duyệt tín dụng cấp cao',
  High: 'Cao',
  Standard: 'Thường',
  'Card dispute': 'Khiếu nại thẻ',
  'Dispute raised': 'Nhận khiếu nại',
  'Review dispute': 'Xem xét khiếu nại',
  'Raise chargeback': 'Yêu cầu hoàn tiền',
  'Dispute closed': 'Đóng khiếu nại',
};

const viName = (name: string): string => VI_NAMES[name] ?? name;

function viBpmn(bpmn: string): string {
  return bpmn.replace(/ name="([^"]+)"/g, (whole, name: string) => (whole.includes('horus:') ? whole : ` name="${viName(name)}"`));
}

/** Any demo definitions as a tenant of the given language authors them. */
export const localiseDemo = (definitions: readonly DemoDefinition[], locale: 'en' | 'vi'): readonly DemoDefinition[] =>
  locale === 'vi' ? definitions.map((d) => ({ ...d, name: viName(d.name), bpmn: viBpmn(d.bpmn) })) : definitions;

export const VI_DEMO_DEFINITIONS: readonly DemoDefinition[] = DEMO_DEFINITIONS.map((d) => ({ ...d, name: viName(d.name), bpmn: viBpmn(d.bpmn) }));

export const VI_REVIEW_CONTEXT = {
  ...REVIEW_CONTEXT,
  reasons: ['Thu nhập kê khai cao hơn 38% so với thu nhập trên phiếu lương', 'Đề xuất Duyệt cần thu nhập được xác minh trong biên độ 20%'],
  output: { riskScore: 38, recommendation: 'Duyệt', incomeVerified: true },
} as const;

/** The review task names, as the engine names the task after its step. */
export const REVIEW_TASK_NAME = { en: 'Assess documents', vi: 'Thẩm định chứng từ' } as const;
export const FORM_TASK_NAME = { en: 'Capture application', vi: 'Nhập hồ sơ vay' } as const;
/** The answer's allowed recommendations, as the step's output schema lists them for each tenant. */
export const RECOMMENDATIONS = { en: ['APPROVE', 'REFER', 'DECLINE'], vi: ['Duyệt', 'Xem lại', 'Từ chối'] } as const;

/**
 * The engine's change list from version 2 of the onboarding process (in effect) to version 3 (waiting), as
 * getDefinitionDiff answers it, in the tenant's language. `truncated` is the answer for a change too large to list:
 * the hashes are then left out and nothing may be approved against it (PD-139).
 */
export function kycDiff(locale: 'en' | 'vi', truncated = false) {
  const name = (value: string) => (locale === 'vi' ? viName(value) : value);
  const link = (id: string, from: string, fromName: string, to: string, toName: string, kind = 'next') => ({ id, from, fromName: name(fromName), to, toName: name(toName), kind });
  return {
    definitionKey: 'kyc-onboarding',
    approvableHash: truncated ? null : KYC_V3_HASH,
    base: { version: 2, status: 'ACTIVE', ...(truncated ? {} : { definitionHash: KYC_V2_HASH }) },
    target: { version: 3, status: 'PENDING_APPROVAL', ...(truncated ? {} : { definitionHash: KYC_V3_HASH }) },
    identical: false,
    nodesAdded: [{ id: 'fraud-check', name: name('Check for fraud'), type: 'serviceTask' }],
    nodesRemoved: [{ id: 'cooling', name: name('Wait one day'), type: 'timer' }],
    nodesChanged: [
      { id: 'approve', name: name('Approve high-risk customer'), type: 'userTask', path: '/assignee/role', from: 'CREDIT_OFFICER', to: 'SENIOR_CREDIT_OFFICER' },
      { id: 'risk-rating', name: name('Rate customer risk'), type: 'decision', path: '/inputs/highRiskAmount', from: { currency: 'VND', amount: 500000000 }, to: { currency: 'VND', amount: 300000000 } },
    ],
    edgesAdded: [
      link('k8', 'approve', 'Approve high-risk customer', 'open-account', 'Open account'),
      link('k5b', 'fraud-check', 'Check for fraud', 'join', 'Both checks done'),
      link('k5a', 'verify-docs', 'Verify identity documents', 'fraud-check', 'Check for fraud'),
    ],
    edgesRemoved: [
      link('k8', 'approve', 'Approve high-risk customer', 'cooling', 'Wait one day'),
      link('k9', 'cooling', 'Wait one day', 'open-account', 'Open account'),
      link('k5', 'verify-docs', 'Verify identity documents', 'join', 'Both checks done'),
    ],
    truncated,
  };
}
