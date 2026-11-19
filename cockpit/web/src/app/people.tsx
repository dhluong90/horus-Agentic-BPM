import { createContext, useCallback, useContext, type ReactNode } from 'react';
import { z } from 'zod';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';

/*
 * People in lists are shown by display name, as in the header. The signed-in user's name comes from /me. Other
 * people's names come from the engine with each version (`createdByName`, `requestedByName`, `approvedByName`)
 * when the host supplies them; without a name the login is shown, never an invented name.
 */

const PeopleContext = createContext<BpmMeV1['user'] | undefined>(undefined);

export function PeopleProvider({ me, children }: { me: BpmMeV1['user']; children: ReactNode }) {
  return <PeopleContext.Provider value={me}>{children}</PeopleContext.Provider>;
}

const names = z
  .object({ createdByName: z.string().min(1).optional(), requestedByName: z.string().min(1).optional(), approvedByName: z.string().min(1).optional() })
  .loose();

export type PersonRole = 'createdBy' | 'requestedBy' | 'approvedBy';

/** The display name the engine sent for one of a version's people, when it sent one. */
export function suppliedName(version: unknown, role: PersonRole): string | undefined {
  const parsed = names.safeParse(version);
  return parsed.success ? parsed.data[`${role}Name`] : undefined;
}

/** Resolves a login to the name to show. */
export function usePersonName(): (login: string, supplied?: string) => string {
  const me = useContext(PeopleContext);
  return useCallback(
    (login: string, supplied?: string) => {
      if (me !== undefined && login === me.id) return me.displayName ?? login;
      return supplied ?? login;
    },
    [me],
  );
}
