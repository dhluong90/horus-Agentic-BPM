package io.github.dhluong90.bpm.process.api;

/**
 * Broadcasts a signal to the waiting subscriptions of the caller's tenant (#492). The tenant comes from the tenant
 * resolver, never from the request, so a signal never reaches another tenant. Safe for concurrent use.
 *
 * <p>A broadcast runs in one transaction and only writes: the signal, one wake-up job for each waiting subscription,
 * and the instances that a signal start event of the same name starts. The waiters continue when their jobs fire, each
 * in a transaction of its own, so a large broadcast never holds the locks of the instances it wakes. Idempotent by the
 * {@code Idempotency-Key}: a replay returns the first answer and wakes nobody twice.
 */
public interface SignalService {

    /**
     * @param request the signal name and the idempotency key
     * @return the broadcast: its id, how many waiting subscriptions it will wake, and the instances it started
     * @throws PermissionDeniedException without {@code processes:signal:broadcast}
     * @throws PreconditionRequiredException without an idempotency key
     * @throws InputInvalidException when the name is not a signal name
     */
    SignalBroadcast broadcast(SignalRequest request);
}
