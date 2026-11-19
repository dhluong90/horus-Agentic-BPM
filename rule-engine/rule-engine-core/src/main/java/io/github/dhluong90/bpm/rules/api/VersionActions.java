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
     * @return the actions in a fixed order: {@code request-publish}, {@code approve}, {@code reject}
     */
    public static List<String> allowed(VersionStatus status, String createdBy, String requestedBy, String caller,
            Set<String> permissions) {
        List<String> actions = new ArrayList<>(2);
        switch (status) {
            case DRAFT -> {
                if (permissions.contains(Permissions.AUTHOR)) {
                    actions.add(REQUEST_PUBLISH);
                }
            }
            case PENDING_APPROVAL -> {
                if (permissions.contains(Permissions.APPROVE) && caller != null && !caller.equals(requestedBy)) {
                    // Four eyes: neither the requester nor the author may approve. The requester may not reject
                    // either; they take the request back with :withdraw (PD-089), which arrives with RLE-046.
                    if (!caller.equals(createdBy)) {
                        actions.add(APPROVE);
                    }
                    actions.add(REJECT);
                }
            }
            case PUBLISHED, RETIRED -> {
                // Retire arrives with its own feature; nothing is allowed yet.
            }
        }
        return List.copyOf(actions);
    }

    /** The permission strings of ADR-0010 that decide lifecycle actions. */
    public static final class Permissions {

        /** Import and request publication. */
        public static final String AUTHOR = "rules:author";

        /** Approve and reject. */
        public static final String APPROVE = "rules:approve";

        private Permissions() {
        }
    }
}
