package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored version of a process definition.
 *
 * @param id the version id (ULID)
 * @param key the definition key (kebab-case), from the BPMN {@code process/@id}
 * @param version the version number within the key, from 1
 * @param status {@code DRAFT}, {@code PENDING_APPROVAL}, {@code ACTIVE} or {@code RETIRED}
 * @param name the display name, or null
 * @param definitionHash SHA-256 of the RFC 8785 form of the canonical JSON
 * @param sourceFormat format of the stored source document
 * @param sourceHash SHA-256 of the source bytes
 * @param nodeCount number of flow nodes
 * @param approvedBy approver, {@code system:dev-auto-approve} on the dev profile, or null
 * @param createdBy the importing identity
 * @param createdAt when it was imported
 * @param source how it arrived: {@code IMPORT}, {@code STARTUP}, {@code AUTHORED} or {@code BPMN}
 * @param lockVersion optimistic lock; send it as {@code If-Match} or {@code expectedLockVersion}
 * @param effectiveFrom when the version takes effect; requested with the approval request, null means at approval
 * @param requestedBy who requested approval, or null
 * @param requestedAt when approval was requested, or null
 * @param approvedAt when it was approved, or null
 * @param decisionComment the approval comment or the reason of the last rejection, or null
 * @param retiredBy who retired it ({@code system:effective-date} when a newer version took effect), or null
 * @param retiredAt when it was retired, or null
 * @param supersededBy the version number that superseded it, or null
 */
public record DefinitionVersion(String id, String key, int version, String status, String name,
        String definitionHash, SourceFormat sourceFormat, String sourceHash, int nodeCount, String approvedBy,
        String createdBy, Instant createdAt, String source, long lockVersion, Instant effectiveFrom, String requestedBy,
        Instant requestedAt, Instant approvedAt, String decisionComment, String retiredBy, Instant retiredAt,
        Integer supersededBy) {

    public DefinitionVersion {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
    }

    /**
     * The 0.1.0 form, without the lifecycle fields.
     *
     * @param id the version id
     * @param key the definition key
     * @param version the version number
     * @param status the status
     * @param name the display name, or null
     * @param definitionHash the definition hash
     * @param sourceFormat the source format
     * @param sourceHash the source hash
     * @param nodeCount number of flow nodes
     * @param approvedBy approver or null
     * @param createdBy the importing identity
     * @param createdAt when it was imported
     */
    public DefinitionVersion(String id, String key, int version, String status, String name, String definitionHash,
            SourceFormat sourceFormat, String sourceHash, int nodeCount, String approvedBy, String createdBy,
            Instant createdAt) {
        this(id, key, version, status, name, definitionHash, sourceFormat, sourceHash, nodeCount, approvedBy, createdBy,
                createdAt, null, 0, null, null, null, null, null, null, null, null);
    }

    /** @return the reference of this version */
    public DefinitionRef ref() {
        return new DefinitionRef(key, version);
    }
}
