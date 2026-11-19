import { useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { useRulesSession } from '../rules/api/rulesContext';

/** Reads every rule list of this host and tenant again, after a failed call. */
export function useRetryRules(): () => void {
  const queryClient = useQueryClient();
  const { hostId, tenant } = useRulesSession();
  return useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: [hostId, tenant, 'rules'] });
  }, [queryClient, hostId, tenant]);
}
