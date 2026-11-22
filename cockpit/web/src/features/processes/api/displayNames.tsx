import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { isEmpty, NO_NAMES, type WantedNames } from '../../../api/displayNames';
import { usePersonName } from '../../../app/people';
import { useI18n } from '../../../i18n/I18nProvider';
import { parseAssignee, readableName, type Assignee } from '../../tasks/model/queue';
import { useProcessesSession } from './processesContext';

/*
 * Roles, groups and users appear as readable names in the caller's language (#415, #442). A screen says which
 * identifiers it shows (`useWantNames`); the provider asks the host once for all of them and a screen reads the answer
 * through `useNames`. Until the answer arrives, or when the host knows no name, the identifier is shown in words as
 * before; a failed lookup is never an error on screen, the names are labels only.
 */

const NONE: WantedNames = { roles: [], groups: [], users: [] };

interface Registry {
  register: (wanted: Partial<WantedNames>) => void;
  names: typeof NO_NAMES;
}

const NamesContext = createContext<Registry | undefined>(undefined);

/** Adds the new identifiers to what is already wanted, keeping the same object when nothing is new. */
function union(current: WantedNames, more: Partial<WantedNames>): WantedNames {
  let changed = false;
  const next = { ...current };
  for (const kind of ['roles', 'groups', 'users'] as const) {
    const fresh = (more[kind] ?? []).filter((id) => !current[kind].includes(id));
    if (fresh.length > 0) {
      next[kind] = [...current[kind], ...fresh];
      changed = true;
    }
  }
  return changed ? next : current;
}

export function DisplayNamesProvider({ children }: { children: ReactNode }) {
  const { client, hostId, tenant } = useProcessesSession();
  const { locale } = useI18n();
  const [wanted, setWanted] = useState<WantedNames>(NONE);
  const register = useCallback((more: Partial<WantedNames>) => { setWanted((current) => union(current, more)); }, []);
  const lookup = useQuery({
    queryKey: [hostId, tenant, 'display-names', locale, wanted.roles.join(','), wanted.groups.join(','), wanted.users.join(',')],
    queryFn: ({ signal }) => client.getDisplayNames(wanted, locale, signal),
    enabled: !isEmpty(wanted),
    // The wanted set only grows, so the last answer covers everything but the newest identifiers.
    placeholderData: keepPreviousData,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const value = useMemo<Registry>(() => ({ register, names: lookup.data ?? NO_NAMES }), [register, lookup.data]);
  return <NamesContext.Provider value={value}>{children}</NamesContext.Provider>;
}

/** Says which identifiers this screen shows, so the provider can look their names up. */
export function useWantNames(wanted: Partial<WantedNames>): void {
  const registry = useContext(NamesContext);
  const register = registry?.register;
  const key = [wanted.roles, wanted.groups, wanted.users].map((ids) => (ids ?? []).join(',')).join('|');
  useEffect(() => { register?.({ roles: wanted.roles ?? [], groups: wanted.groups ?? [], users: wanted.users ?? [] }); },
    // The key stands for the three lists.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [register, key]);
}

export interface Names {
  role: (id: string) => string;
  group: (id: string) => string;
  /** A person by name; `supplied` is the name the engine sent with the item, used when the host lookup has none. */
  user: (id: string, supplied?: string) => string;
  /** `role:<id>`, `group:<id>` or `user:<id>` as the engine writes an assignee, with the kind in words. */
  assignee: (value: string) => string;
  /** The same without the kind: "Credit officer" rather than "Role: Credit officer". */
  assigneeName: (value: string) => string;
}

/** The identifiers an assignee value names, to hand to `useWantNames`. */
export function wantedOf(values: readonly (string | null | undefined)[], users: readonly (string | null | undefined)[] = []): WantedNames {
  const parsed = values.flatMap((value): Assignee[] => (value === null || value === undefined ? [] : [parseAssignee(value)]));
  const some = (ids: readonly (string | null | undefined)[]) => [...new Set(ids.filter((id): id is string => id !== null && id !== undefined && id !== ''))];
  return {
    roles: some(parsed.map((a) => (a.kind === 'role' ? a.name : undefined))),
    groups: some(parsed.map((a) => (a.kind === 'group' ? a.name : undefined))),
    users: some([...parsed.map((a) => (a.kind === 'user' ? a.id : undefined)), ...users]),
  };
}

/** Readable names, in the caller's language; the signed-in user is named as in the header. */
export function useNames(): Names {
  const names = useContext(NamesContext)?.names ?? NO_NAMES;
  const { t, label } = useI18n();
  const person = usePersonName();
  return useMemo<Names>(() => {
    // The host's name first, then the cockpit's own label for a well-known role, then the id in words.
    const role = (id: string) => names.roles[id]
      ?? label(`label.role.${id.replace(/^ROLE_/, '').replace(/[-.\s]+/g, '_').toUpperCase()}`) ?? readableName(id);
    const group = (id: string) => names.groups[id] ?? readableName(id);
    const user = (id: string, supplied?: string) => person(id, names.users[id] ?? supplied);
    return {
      role,
      group,
      user,
      assigneeName: (value) => {
        const assignee = parseAssignee(value);
        return assignee.kind === 'role' ? role(assignee.name) : assignee.kind === 'group' ? group(assignee.name) : user(assignee.id);
      },
      assignee: (value) => {
        const assignee = parseAssignee(value);
        switch (assignee.kind) {
          case 'role': return t('tasks.assignee.role', { name: role(assignee.name) });
          case 'group': return t('tasks.assignee.group', { name: group(assignee.name) });
          case 'user': return user(assignee.id);
        }
      },
    };
  }, [names, person, t, label]);
}
