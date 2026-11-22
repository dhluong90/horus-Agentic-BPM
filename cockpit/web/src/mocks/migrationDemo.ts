import { DEMO_DEFINITIONS, KYC_ONBOARDING_V3_BPMN, KYC_V3_HASH, type DemoDefinition } from './processDemo';

/*
 * Demo data of the mock migration API: the onboarding process with its version 2 retired and version 3 active, where
 * version 3 renamed the high-risk approval and dropped the one-day wait. 52 cases are running on version 2: 14 at the
 * approval, 30 at the wait (2 of them have a call in flight) and 8 at the identity check. The run ends with one case
 * that was busy (its reference is masked for the reader) and the two with a call in flight left on version 2.
 */

export const MIGRATION_PLAN_ID = '01JD4M1GRAT10N00000000PLAN';
export const MIGRATION_KEY = 'kyc-onboarding';

/** Version 3 with the high-risk approval renamed `senior-approval`, as an operations lead would see after a redesign. */
export const MIGRATION_V3_BPMN = KYC_ONBOARDING_V3_BPMN
  .replace('<bpmn:userTask id="approve" name="Approve high-risk customer"', '<bpmn:userTask id="senior-approval" name="Senior credit approval"')
  .replace(/sourceRef="approve"/g, 'sourceRef="senior-approval"')
  .replace(/targetRef="approve"/g, 'targetRef="senior-approval"');

/** The tenant's definitions for the migration demo: version 2 retired, version 3 in effect. */
export const MIGRATION_DEFINITIONS: readonly DemoDefinition[] = DEMO_DEFINITIONS.map((d) => {
  if (d.key !== MIGRATION_KEY) return d;
  return d.version === 2
    ? { ...d, status: 'RETIRED', effectiveVersion: 3 }
    : { ...d, status: 'ACTIVE', effectiveVersion: 3, bpmn: MIGRATION_V3_BPMN, definitionHash: KYC_V3_HASH, effectiveFrom: '2026-11-18T00:00:00Z' };
});

/** The cases waiting at each step of version 2. `inFlight` stays on version 2 when the step's work is cancelled. */
export const WAITING = [
  { nodeId: 'approve', name: 'Approve high-risk customer', type: 'userTask', cases: 14, inFlight: 0 },
  { nodeId: 'cooling', name: 'Wait one day', type: 'intermediateCatchEvent:timer', cases: 30, inFlight: 2 },
  { nodeId: 'verify-docs', name: 'Verify identity documents', type: 'serviceTask:agentTask', cases: 8, inFlight: 0 },
] as const;

/** A case id of the demo: 26 characters, the number in the tail. */
export const migrationCaseId = (n: number): string => `01JD4MIGRATE${String(n).padStart(14, '0')}`;
export const CASE_COUNT = 52;
export const IN_FLIGHT_CASES = [50, 51] as const;
export const FAILED_CASES = [49] as const;

/** The references of the cases left behind; the first is masked for a reader who may not see it. */
export const CASE_KEYS: Readonly<Record<number, string>> = { 49: '***', 50: 'KYC-2207', 51: 'KYC-2219' };

/** Hex of the right length for the plan's hash, stable for the same text (the mock is not a security boundary). */
export function fakeHash(text: string): string {
  let a = 0x811c9dc5;
  let out = '';
  for (let round = 0; round < 8; round += 1) {
    for (let index = 0; index < text.length; index += 1) a = Math.imul(a ^ text.charCodeAt(index) ^ round, 0x01000193) >>> 0;
    out += a.toString(16).padStart(8, '0');
  }
  return out;
}
