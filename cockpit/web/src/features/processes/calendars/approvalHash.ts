import type { CalendarChange } from '../../../api/processes/schemas';

/**
 * The hash Approve names: the engine's `contentHash` of the change as it was first shown. The screen keeps the
 * change it read, so a change that moved on is refused, never approved blind (PD-139).
 */
export function useApprovalHash(change: CalendarChange): string {
  return change.contentHash;
}
