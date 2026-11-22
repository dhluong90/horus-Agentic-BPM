import type { MessageKey } from '../../../../i18n/en';

/** The bases of the messages that read differently for one and for many; each has a `.one` and an `.other` key. */
export type CountedBase =
  | 'migration.badge.cases' | 'migration.badge.arrive' | 'migration.cases' | 'migration.steps.inFlight' | 'migration.result.can'
  | 'migration.impact.mapped' | 'migration.impact.cancelled' | 'migration.impact.inFlight' | 'migration.impact.unmapped'
  | 'migration.propose' | 'migration.moveNow' | 'migration.approve';

/** The key for `count`: exactly one reads as the singular, everything else (including none) as the plural. */
export function counted(base: CountedBase, count: number): MessageKey {
  return count === 1 ? `${base}.one` : `${base}.other`;
}
