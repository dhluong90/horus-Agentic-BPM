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
 */
public record DefinitionVersion(String id, String key, int version, String status, String name,
        String definitionHash, SourceFormat sourceFormat, String sourceHash, int nodeCount, String approvedBy,
        String createdBy, Instant createdAt) {

    public DefinitionVersion {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
    }
}
