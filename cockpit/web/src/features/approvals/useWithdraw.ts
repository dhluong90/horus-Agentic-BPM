import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import type { RuleSetVersion, VersionSummary } from '../../api/rules/schemas';
import { useRulesSession } from '../rules/api/rulesContext';

/**
 * Withdraws the user's own pending request (POST ...:withdraw, PD-089). One Idempotency-Key per intent, so a
 * repeated click or a retry replays the first answer. Lists are read again only after the engine confirms.
 */
export function useWithdraw(onDone: (version: RuleSetVersion) => void) {
  const { client, hostId, tenant } = useRulesSession();
  const queryClient = useQueryClient();
  const intent = useRef<{ fingerprint: string; key: string } | undefined>(undefined);
  return useMutation({
    mutationFn: ({ version, reason }: { version: VersionSummary; reason: string | undefined }) => {
      const fingerprint = JSON.stringify([version.ruleSet, version.version, version.lockVersion, reason]);
      if (intent.current?.fingerprint !== fingerprint) intent.current = { fingerprint, key: crypto.randomUUID() };
      return client.withdraw(version.ruleSet, version.version, version.lockVersion, reason, intent.current.key);
    },
    onSuccess: async (version) => {
      await queryClient.invalidateQueries({ queryKey: [hostId, tenant, 'rules'] });
      onDone(version);
    },
  });
}
