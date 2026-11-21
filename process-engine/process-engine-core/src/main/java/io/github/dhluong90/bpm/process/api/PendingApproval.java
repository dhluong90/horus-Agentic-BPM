package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A definition version waiting for a second person, as the pending approvals list shows it (#31, PRE-190): what the
 * reviewer needs to open it and to approve exactly what was reviewed, never the definition, the source or a variable.
 *
 * @param key the definition key
 * @param name the process name, or null
 * @param version the version number
 * @param definitionHash SHA-256 of the canonical definition: the value a reviewer sends back as
 *     {@code expectedDefinitionHash} (PRE-177, PD-139)
 * @param lockVersion the optimistic lock a decision sends as {@code If-Match}
 * @param requestedBy who requested approval
 * @param requestedAt when approval was requested
 * @param allowedActions what the caller may send now, in the order {@code approve}, {@code reject}
 * @param deniedActions for {@code approve} and {@code reject}, when the caller may not send it, the reason as a code:
 *     {@code PERMISSION_REQUIRED} or {@code SELF_APPROVAL_FORBIDDEN}
 */
public record PendingApproval(String key, String name, int version, String definitionHash, long lockVersion,
        String requestedBy, Instant requestedAt, List<String> allowedActions, Map<String, String> deniedActions) {

    /** {@code approve} and {@code reject}: the actions of the queue. */
    public static final String APPROVE = "approve";
    /** See {@link #APPROVE}. */
    public static final String REJECT = "reject";
    /** The caller does not hold {@code processes:approve}. */
    public static final String PERMISSION_REQUIRED = "PERMISSION_REQUIRED";
    /** The caller requested or made the version (four eyes, PRE-019, PD-096). */
    public static final String SELF_APPROVAL_FORBIDDEN = "SELF_APPROVAL_FORBIDDEN";

    public PendingApproval {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        allowedActions = List.copyOf(allowedActions);
        deniedActions = Collections.unmodifiableMap(new LinkedHashMap<>(deniedActions));
    }
}
