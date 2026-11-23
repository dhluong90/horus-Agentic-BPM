package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.List;
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
 * @param warnings what an import kept in the stored source but did not read, such as {@code FOREIGN_EXTENSION_IGNORED};
 *     empty except in the answer to an import
 * @param alsoImported the other executable pools of the same file, each a definition of its own (#497); empty except
 *     in the answer to an import of a collaboration
 */
public record DefinitionVersion(String id, String key, int version, String status, String name,
        String definitionHash, SourceFormat sourceFormat, String sourceHash, int nodeCount, String approvedBy,
        String createdBy, Instant createdAt, String source, long lockVersion, Instant effectiveFrom, String requestedBy,
        Instant requestedAt, Instant approvedAt, String decisionComment, String retiredBy, Instant retiredAt,
        Integer supersededBy, List<DefinitionProblem> warnings, List<DefinitionRef> alsoImported) {

    public DefinitionVersion {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        alsoImported = alsoImported == null ? List.of() : List.copyOf(alsoImported);
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
    }

    /**
     * The form without warnings.
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
     * @param source how it arrived
     * @param lockVersion optimistic lock
     * @param effectiveFrom when it takes effect, or null
     * @param requestedBy who requested approval, or null
     * @param requestedAt when, or null
     * @param approvedAt when approved, or null
     * @param decisionComment the comment, or null
     * @param retiredBy who retired it, or null
     * @param retiredAt when, or null
     * @param supersededBy the superseding version, or null
     */
    public DefinitionVersion(String id, String key, int version, String status, String name, String definitionHash,
            SourceFormat sourceFormat, String sourceHash, int nodeCount, String approvedBy, String createdBy,
            Instant createdAt, String source, long lockVersion, Instant effectiveFrom, String requestedBy,
            Instant requestedAt, Instant approvedAt, String decisionComment, String retiredBy, Instant retiredAt,
            Integer supersededBy) {
        this(id, key, version, status, name, definitionHash, sourceFormat, sourceHash, nodeCount, approvedBy,
                createdBy, createdAt, source, lockVersion, effectiveFrom, requestedBy, requestedAt, approvedAt,
                decisionComment, retiredBy, retiredAt, supersededBy, List.of(), List.of());
    }

    /** @param found what the import ignored @return a copy that carries it */
    public DefinitionVersion withWarnings(List<DefinitionProblem> found) {
        return new DefinitionVersion(id, key, version, status, name, definitionHash, sourceFormat, sourceHash,
                nodeCount, approvedBy, createdBy, createdAt, source, lockVersion, effectiveFrom, requestedBy,
                requestedAt, approvedAt, decisionComment, retiredBy, retiredAt, supersededBy, found, alsoImported);
    }

    /** @param others the other pools of the same file that became definitions (#497) @return a copy that names them */
    public DefinitionVersion withAlsoImported(List<DefinitionRef> others) {
        return new DefinitionVersion(id, key, version, status, name, definitionHash, sourceFormat, sourceHash,
                nodeCount, approvedBy, createdBy, createdAt, source, lockVersion, effectiveFrom, requestedBy,
                requestedAt, approvedAt, decisionComment, retiredBy, retiredAt, supersededBy, warnings, others);
    }

    /**
     * The form with warnings and no other pools.
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
     * @param source how it arrived
     * @param lockVersion optimistic lock
     * @param effectiveFrom when it takes effect, or null
     * @param requestedBy who requested approval, or null
     * @param requestedAt when, or null
     * @param approvedAt when approved, or null
     * @param decisionComment the comment, or null
     * @param retiredBy who retired it, or null
     * @param retiredAt when, or null
     * @param supersededBy the superseding version, or null
     * @param warnings what the import ignored
     */
    public DefinitionVersion(String id, String key, int version, String status, String name, String definitionHash,
            SourceFormat sourceFormat, String sourceHash, int nodeCount, String approvedBy, String createdBy,
            Instant createdAt, String source, long lockVersion, Instant effectiveFrom, String requestedBy,
            Instant requestedAt, Instant approvedAt, String decisionComment, String retiredBy, Instant retiredAt,
            Integer supersededBy, List<DefinitionProblem> warnings) {
        this(id, key, version, status, name, definitionHash, sourceFormat, sourceHash, nodeCount, approvedBy,
                createdBy, createdAt, source, lockVersion, effectiveFrom, requestedBy, requestedAt, approvedAt,
                decisionComment, retiredBy, retiredAt, supersededBy, warnings, List.of());
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
