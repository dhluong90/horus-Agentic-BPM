import { BOOKED_INSTANCE_ID, INCIDENT_INSTANCE_ID, KYC_INSTANCE_ID } from './caseDemo';
import { INSTANCE_ID } from './processDemo';

/*
 * Demo incidents of tenant bank-a (CKP-008, PRE-077, PRE-078): an agent step that timed out, a limit check refused on
 * a business rule, a core banking call that timed out three times, an undo that must be done by hand, and a result
 * Linh proposed that waits for a second person (PD-126). Cause summaries are built from codes and numbers only, as the
 * engine builds them.
 */

export const INCIDENTS = {
  agentTimeout: '01JD6N1AGENTT1ME0UT00000N1',
  limitRefused: '01JD6N2L1M1TREFUSED00000N2',
  coreTimeout: '01JD6N3C0RET1ME0UT000000N3',
  manualUndo: '01JD6N4MANUA1UND0000000N04',
  proposed: '01JD6N5PR0P0SED0000000N005',
} as const;

export interface StoredIncident {
  id: string;
  instanceId: string;
  nodeId: string;
  tokenId: string | null;
  errorCode: string;
  causeSummary: string;
  retryCount: number;
  status: 'OPEN' | 'RESOLUTION_PENDING' | 'RESOLVED';
  lockVersion: number;
  retrying: boolean;
  createdAt: string;
  resolvedAt: string | null;
  resolution: string | null;
  resolvedBy: string | null;
  pendingResolution: { proposedBy: string; proposedAt: string; reason: string | null; output: Record<string, unknown>; approvalMode: 'four-eyes' | 'single' | null } | null;
  /** Output fields the step's schema requires; a proposal without one is 422 OUTPUT_INVALID naming it. */
  requires: readonly string[];
}

const base = { tokenId: null, lockVersion: 0, retrying: false, resolvedAt: null, resolution: null, resolvedBy: null, pendingResolution: null } as const;

export function demoIncidents(locale: 'en' | 'vi'): StoredIncident[] {
  return [
    { ...base, id: INCIDENTS.agentTimeout, instanceId: INCIDENT_INSTANCE_ID, nodeId: 'assess-documents', errorCode: 'AGENT_TIMEOUT', causeSummary: 'AGENT_TIMEOUT after 3 attempts, timeout PT30S', retryCount: 2, status: 'OPEN', createdAt: '2026-11-20T01:54:10Z', requires: ['riskScore', 'recommendation'] },
    { ...base, id: INCIDENTS.limitRefused, instanceId: INSTANCE_ID, nodeId: 'credit-decision', errorCode: 'UNHANDLED_BUSINESS_ERROR', causeSummary: 'UNHANDLED_BUSINESS_ERROR LIMIT_EXCEEDED', retryCount: 0, status: 'OPEN', createdAt: '2026-11-21T01:12:00Z', requires: ['approvedLimit'] },
    { ...base, id: INCIDENTS.coreTimeout, instanceId: KYC_INSTANCE_ID, nodeId: 'screen-sanctions', errorCode: 'ADAPTER_TIMEOUT', causeSummary: 'ADAPTER_TIMEOUT after 3 attempts, timeout PT10S', retryCount: 3, status: 'OPEN', createdAt: '2026-11-21T02:05:00Z', requires: ['screened'] },
    { ...base, id: INCIDENTS.manualUndo, instanceId: BOOKED_INSTANCE_ID, nodeId: 'book-loan', errorCode: 'COMPENSATION_MANUAL_REQUIRED', causeSummary: 'COMPENSATION_MANUAL_REQUIRED compensate=manual', retryCount: 0, status: 'OPEN', createdAt: '2026-11-21T00:40:00Z', requires: ['reversalReference'] },
    {
      ...base, id: INCIDENTS.proposed, instanceId: KYC_INSTANCE_ID, nodeId: 'verify-docs', errorCode: 'SERVICE_ERROR', causeSummary: 'SERVICE_ERROR status 503', retryCount: 1, status: 'RESOLUTION_PENDING', lockVersion: 1, createdAt: '2026-11-20T23:30:00Z', requires: ['documentsVerified'],
      pendingResolution: {
        proposedBy: 'linh', proposedAt: '2026-11-21T01:45:00Z', approvalMode: 'four-eyes',
        reason: locale === 'vi' ? 'Chi nhánh HN-03 đã đối chiếu bản gốc, biên bản KYC-77' : 'Branch HN-03 checked the originals, memo KYC-77',
        output: { documentsVerified: true, checkedBy: locale === 'vi' ? 'Chi nhánh HN-03' : 'Branch HN-03', feeWaived: { amount: 50000, currency: 'VND' } },
      },
    },
  ];
}
