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
    /** {@code :discard}: the author throws away a draft that is no longer wanted (RLE-098). */
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
                    // Only the author discards, as only the author replaces (RLE-047, RLE-098).
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

    /** The caller does not hold the permission the action needs. */
    public static final String DENIED_PERMISSION_REQUIRED = "PERMISSION_REQUIRED";
    /** The caller requested or authored the version (four eyes). */
    public static final String DENIED_SELF_APPROVAL = "SELF_APPROVAL_FORBIDDEN";
    /** The version has no recorded author, so nobody can approve it (PD-105). */
    public static final String DENIED_AUTHOR_UNKNOWN = "AUTHOR_UNKNOWN";

    /**
     * Why the caller may not approve or reject a pending version, as codes, for the actions {@link #allowed} leaves
     * out (#31, RLE-190). An action the caller may send has no entry. A hint for screens, like {@link #allowed}.
     *
     * @param createdBy the version's author, or null
     * @param requestedBy the publication requester
     * @param caller the caller's id
     * @param permissions the caller's {@code rules:*} permissions
     * @return {@code approve} and {@code reject} to a reason code, in that order, for those the caller may not send
     */
    public static java.util.Map<String, String> pendingDenials(String createdBy, String requestedBy, String caller,
            Set<String> permissions) {
        java.util.Map<String, String> denied = new java.util.LinkedHashMap<>(2);
        if (!permissions.contains(Permissions.APPROVE)) {
            denied.put(APPROVE, DENIED_PERMISSION_REQUIRED);
            denied.put(REJECT, DENIED_PERMISSION_REQUIRED);
            return denied;
        }
        if (caller != null && caller.equals(requestedBy)) {
            denied.put(APPROVE, DENIED_SELF_APPROVAL);
            denied.put(REJECT, DENIED_SELF_APPROVAL);
        } else if (createdBy == null) {
            denied.put(APPROVE, DENIED_AUTHOR_UNKNOWN);
        } else if (createdBy.equals(caller)) {
            denied.put(APPROVE, DENIED_SELF_APPROVAL);
        }
        return denied;
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
