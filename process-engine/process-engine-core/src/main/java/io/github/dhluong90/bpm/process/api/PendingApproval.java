package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A proposal waiting for a second person, as the pending approvals list shows it (#31, PRE-190; migration plans and
 * calendar changes #473): what the reviewer needs to open it and to approve exactly what was reviewed, never the
 * definition, the source, a calendar's hours or a variable.
 *
 * @param kind {@link #DEFINITION}, {@link #MIGRATION_PLAN} or {@link #CALENDAR_CHANGE}
 * @param id the id of the version, the migration plan or the calendar change; the tie-break of the keyset; null on an
 *     item built before #473
 * @param key the definition key, or the calendar key of a calendar change
 * @param name the process name, or null
 * @param version the definition version; for a migration plan the version it moves to; for a calendar change the
 *     version the calendar takes when approved
 * @param sourceVersion for a migration plan the version the instances run on, otherwise null
 * @param definitionHash SHA-256 of the canonical definition: the value a reviewer sends back as
 *     {@code expectedDefinitionHash} (PRE-177, PD-139); null for the other kinds
 * @param contentHash the hash of what the reviewer was shown, to send back with the approval: for a definition the
 *     {@code definitionHash}, for a migration plan its {@code planHash}, for a calendar change the hash of the key, the
 *     base version and the proposed calendar (PD-139)
 * @param lockVersion the optimistic lock a decision sends as {@code If-Match}; for a calendar change the version it
 *     was proposed against
 * @param requestedBy who requested approval or proposed the change
 * @param requestedAt when
 * @param reason the reason the proposer gave, or null (a definition request carries none)
 * @param allowedActions what the caller may send now, in the order {@code approve}, {@code reject}
 * @param deniedActions for {@code approve} and {@code reject}, when the caller may not send it, the reason as a code:
 *     {@code PERMISSION_REQUIRED} or {@code SELF_APPROVAL_FORBIDDEN}
 * @param requestedByName the requester's readable name in the caller's language (#442), or the id when the host knows
 *     none
 */
public record PendingApproval(String kind, String id, String key, String name, int version, Integer sourceVersion,
        String definitionHash, String contentHash, long lockVersion, String requestedBy, Instant requestedAt,
        String reason, List<String> allowedActions, Map<String, String> deniedActions, String requestedByName) {

    /** {@code approve} and {@code reject}: the actions of the queue. */
    public static final String APPROVE = "approve";
    /** See {@link #APPROVE}. */
    public static final String REJECT = "reject";
    /** The requester takes their own request back (PRE-052). */
    public static final String WITHDRAW = "withdraw";
    /** The caller does not hold the permission of the decision. */
    public static final String PERMISSION_REQUIRED = "PERMISSION_REQUIRED";
    /** The caller requested or made the version (four eyes, PRE-019, PD-096). */
    public static final String SELF_APPROVAL_FORBIDDEN = "SELF_APPROVAL_FORBIDDEN";
    /** A definition version waiting for approval. */
    public static final String DEFINITION = "DEFINITION";
    /** A proposed execution of a migration plan (#473). */
    public static final String MIGRATION_PLAN = "MIGRATION_PLAN";
    /** A proposed change of a calendar (#473). */
    public static final String CALENDAR_CHANGE = "CALENDAR_CHANGE";

    public PendingApproval {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        allowedActions = List.copyOf(allowedActions);
        deniedActions = Collections.unmodifiableMap(new LinkedHashMap<>(deniedActions));
        if (requestedByName == null || requestedByName.isBlank()) {
            requestedByName = requestedBy;
        }
    }

    /**
     * A definition version waiting for approval, as before #473.
     *
     * @param id see the record
     * @param key see the record
     * @param name see the record
     * @param version see the record
     * @param definitionHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param allowedActions see the record
     * @param deniedActions see the record
     * @param requestedByName see the record
     * @return the item
     */
    public static PendingApproval definition(String id, String key, String name, int version, String definitionHash,
            long lockVersion, String requestedBy, Instant requestedAt, List<String> allowedActions,
            Map<String, String> deniedActions, String requestedByName) {
        return new PendingApproval(DEFINITION, id, key, name, version, null, definitionHash, definitionHash,
                lockVersion, requestedBy, requestedAt, null, allowedActions, deniedActions, requestedByName);
    }

    /**
     * A definition version whose requester is shown by its readable name.
     *
     * @param key see the record
     * @param name see the record
     * @param version see the record
     * @param definitionHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param allowedActions see the record
     * @param deniedActions see the record
     * @param requestedByName see the record
     */
    public PendingApproval(String key, String name, int version, String definitionHash, long lockVersion,
            String requestedBy, Instant requestedAt, List<String> allowedActions, Map<String, String> deniedActions,
            String requestedByName) {
        this(DEFINITION, null, key, name, version, null, definitionHash, definitionHash, lockVersion, requestedBy,
                requestedAt, null, allowedActions, deniedActions, requestedByName);
    }

    /**
     * A definition version whose requester is shown by id, as before #442.
     *
     * @param key see the record
     * @param name see the record
     * @param version see the record
     * @param definitionHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param allowedActions see the record
     * @param deniedActions see the record
     */
    public PendingApproval(String key, String name, int version, String definitionHash, long lockVersion,
            String requestedBy, Instant requestedAt, List<String> allowedActions, Map<String, String> deniedActions) {
        this(key, name, version, definitionHash, lockVersion, requestedBy, requestedAt, allowedActions, deniedActions,
                null);
    }
}
