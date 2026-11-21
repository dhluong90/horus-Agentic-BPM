package io.github.dhluong90.bpm.rules.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The lifecycle commands a caller may send for a version now, from its status, the caller's permissions and the
 * four-eyes rule. A hint for screens: every command checks again and is the authority.
 */
public final class VersionActions {

    /** {@code :request-publish}. */
    public static final String REQUEST_PUBLISH = "request-publish";

    /** {@code :approve}. */
    public static final String APPROVE = "approve";

    /** {@code :reject}. */
    public static final String REJECT = "reject";

    /** {@code :retire}: take a published version out of service (ADR-0025). */
    public static final String RETIRE = "retire";
    /** {@code :withdraw}: the requester takes their own publication request back (PD-089). */
    public static final String WITHDRAW = "withdraw";
    /** {@code :discard}: the author throws away a draft that is no longer wanted (RLE-091). */
    public static final String DISCARD = "discard";
    /** {@code :confirm-retire}: a second approver confirms a pending four-eyes retirement (RLE-038). */
    public static final String CONFIRM_RETIRE = "confirm-retire";
    /** {@code :cancel-retire}: an approver withdraws a pending retirement request, with a reason (RLE-038). */
    public static final String CANCEL_RETIRE = "cancel-retire";

    private VersionActions() {
    }

    /**
     * Computes the allowed actions.
     *
     * @param status the version's status
     * @param createdBy the version's author, or null
     * @param requestedBy the publication requester, or null
     * @param caller the caller's id
     * @param permissions the caller's {@code rules:*} permissions
     * @return the actions in a fixed order: {@code request-publish}, {@code approve}, {@code reject},
     *     {@code withdraw}
     */
    public static List<String> allowed(VersionStatus status, String createdBy, String requestedBy, String caller,
            Set<String> permissions) {
        return allowed(status, createdBy, requestedBy, null, caller, permissions);
    }

    /**
     * Computes the allowed actions of a version that may have a pending retirement.
     *
     * @param status the version's status
     * @param createdBy the version's author, or null
     * @param requestedBy the publication requester, or null
     * @param retireRequestedBy who requested the pending retirement, or null when none is pending
     * @param caller the caller's id
     * @param permissions the caller's {@code rules:*} permissions
     * @return the actions in a fixed order: {@code request-publish}, {@code approve}, {@code reject},
     *     {@code withdraw}, {@code discard} for the author of a draft, then {@code retire}, or {@code confirm-retire} and {@code cancel-retire} while a
     *     retirement is pending
     */
    public static List<String> allowed(VersionStatus status, String createdBy, String requestedBy,
            String retireRequestedBy, String caller, Set<String> permissions) {
        List<String> actions = new ArrayList<>(2);
        switch (status) {
            case DRAFT -> {
                if (permissions.contains(Permissions.AUTHOR)) {
                    actions.add(REQUEST_PUBLISH);
                    // Only the author discards, as only the author replaces (RLE-047, RLE-091).
                    if (caller != null && caller.equals(createdBy)) {
                        actions.add(DISCARD);
                    }
                }
            }
            case PENDING_APPROVAL -> {
                if (permissions.contains(Permissions.APPROVE) && caller != null && !caller.equals(requestedBy)) {
                    // Four eyes: neither the requester nor the author may approve. The requester may not reject
                    // either; they take the request back with :withdraw (PD-089).
                    // A version with no recorded author cannot be approved by anyone (PD-105); it can be rejected.
                    if (createdBy != null && !caller.equals(createdBy)) {
                        actions.add(APPROVE);
                    }
                    actions.add(REJECT);
                }
                if (permissions.contains(Permissions.AUTHOR) && caller != null && caller.equals(requestedBy)) {
                    actions.add(WITHDRAW);
                }
            }
            case PUBLISHED -> {
                if (permissions.contains(Permissions.APPROVE)) {
                    if (retireRequestedBy == null) {
                        actions.add(RETIRE);
                    } else {
                        // Four eyes on retirement: the requester never confirms their own request (RLE-038).
                        if (caller != null && !caller.equals(retireRequestedBy)) {
                            actions.add(CONFIRM_RETIRE);
                        }
                        actions.add(CANCEL_RETIRE);
                    }
                }
            }
            case RETIRED, DISCARDED -> {
                // A retired or discarded version never changes state again; a rollback is a new version (DESIGN
                // section 3.3).
            }
        }
        return List.copyOf(actions);
    }

    /** The permission strings of ADR-0010 that decide lifecycle actions. */
    public static final class Permissions {

        /** Import, request publication and withdraw one's own request. */
        public static final String AUTHOR = "rules:author";

        /** Approve and reject. */
        public static final String APPROVE = "rules:approve";

        private Permissions() {
        }
    }
}
