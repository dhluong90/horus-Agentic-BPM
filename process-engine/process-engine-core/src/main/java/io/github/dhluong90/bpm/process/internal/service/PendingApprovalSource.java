package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.PendingApproval;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A kind of proposal that appears in the pending approvals list beside definition versions (#473): the
 * migration plans and the calendar changes that wait for a second person. Each source reads its own store, in the
 * caller's tenant only, oldest proposal first on {@code (requestedAt, id)}, and shows what the caller may do with each
 * item; the commands check again and stay the authority.
 */
public interface PendingApprovalSource {

    /**
     * The items after a position.
     *
     * @param tenant the caller's tenant
     * @param caller the caller's id
     * @param held the permissions the caller holds
     * @param afterAt keyset: the {@code requestedAt} of the last item of the previous page, or null
     * @param afterId keyset: its id, or null
     * @param limit most items
     * @return at most {@code limit} items, oldest first
     */
    List<PendingApproval> pending(String tenant, String caller, Set<String> held, Instant afterAt, String afterId,
            int limit);
}
