package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Imports and exports process definitions and moves their versions through the lifecycle with four eyes (ADR-0012, DESIGN
 * section 3.2): {@code DRAFT -> PENDING_APPROVAL -> ACTIVE -> RETIRED}, and back to {@code DRAFT} on a reject. Safe
 * for concurrent use.
 *
 * <p>Transactions: parsing and validation run before any transaction opens; every command joins the caller's
 * transaction when there is one, or runs in its own. A command writes the version row, one history row and its
 * event in that transaction; an event listener that throws rolls the command back. Commands of one key serialise on
 * the rows of the key. Never calls the network.
 *
 * <p>The tenant and the actor come from the {@code TenantResolver} and {@code CallerResolver}, never from an
 * argument; another tenant's version answers {@link DefinitionNotFoundException}. Permissions: import needs
 * {@code processes:deploy}; {@code requestApproval} needs {@code processes:author} or {@code processes:deploy};
 * {@code approve}, {@code reject} and retiring an active version need {@code processes:approve}; withdrawing a draft
 * needs {@code processes:author}; reads need {@code processes:read}. The requester of a version can never approve
 * it ({@link SelfApprovalForbiddenException}).
 *
 * <p>Every command takes an idempotency key (null for none): a replay returns the first answer and writes nothing.
 * {@code expectedLockVersion} is the version's {@code lockVersion}; a stale value is {@link VersionConflictException}.
 */
public interface DefinitionService {

    /**
     * Imports a definition document as the next version of its key in the caller's tenant. Needs
     * {@code processes:deploy}. The version is {@code DRAFT}, never the effective version (PRE-018), or
     * {@code ACTIVE} with approver {@code system:dev-auto-approve} when
     * {@code horus.bpm.processes.definitions.auto-approve} is on, which the starter accepts on the {@code dev}
     * profile only (PRE-022). A document whose canonical hash equals the latest version of its key creates no new
     * version; that version is returned.
     *
     * @param request the document
     * @return the stored version
     * @throws BpmnUnsafeXmlException when the XML has a DOCTYPE or entity, or exceeds the depth limit
     * @throws BpmnUnsupportedElementException naming every element outside the supported subset
     * @throws ValidationFailedException when the graph is not executable
     * @throws DefinitionTooLargeException when a size limit is exceeded
     */
    DefinitionVersion importDefinition(ImportRequest request);

    /**
     * Moves a draft to {@code PENDING_APPROVAL} and records the caller as requester.
     *
     * @param ref the version
     * @param expectedLockVersion its lock version
     * @param effectiveFrom when it should take effect once approved; null means at approval; never in the past
     * @param idempotencyKey idempotency key, or null
     * @return the version
     * @throws InvalidStateException when the version is not a draft
     * @throws InputInvalidException when {@code effectiveFrom} is in the past
     */
    DefinitionVersion requestApproval(DefinitionRef ref, long expectedLockVersion, Instant effectiveFrom,
            String idempotencyKey);

    /**
     * Approves a pending version as a second person; it becomes {@code ACTIVE} and is the effective version from
     * its {@code effectiveFrom} (PRE-020). At that time every older active version of the key is retired, at once
     * when the date has come, otherwise by a replica-safe effective-date job.
     *
     * @param ref the version
     * @param expectedLockVersion its lock version
     * @param comment an optional comment, stored in the history
     * @param idempotencyKey idempotency key, or null
     * @return the version
     * @throws SelfApprovalForbiddenException when the caller requested the approval (PRE-019); nothing is written
     * @throws VersionSupersededException when a newer version of the key is already active (PRE-021)
     * @throws InvalidStateException when the version is not pending approval
     */
    default DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String comment,
            String idempotencyKey) {
        return approve(ref, expectedLockVersion, null, comment, idempotencyKey);
    }

    /**
     * Approves a pending version as a second person, bound to the content the approver reviewed (PRE-177, PD-139):
     * {@code expectedDefinitionHash} is the {@code definitionHash} the approver saw, and the version is approved only
     * when it is still the stored one. The check runs under the key lock after the status, four-eyes and lock-version
     * checks, so a refusal writes, publishes and records nothing. With
     * {@code horus.bpm.processes.approval.require-content-hash} on (the default; {@code false} is accepted on the
     * {@code dev} profile only) an approval without a hash is refused before anything is read.
     *
     * <p>Where the hash comes from: the approver's screen shows the version and, when the change list against the
     * effective version is complete, its hash. A change list cut off at its limit carries no hash that may be
     * approved, so the client has nothing to send until every page has been read.
     *
     * @param ref the version
     * @param expectedLockVersion its lock version
     * @param expectedDefinitionHash the {@code definitionHash} the approver reviewed, 64 lower-case hexadecimal
     *     characters; null only when the setting is off
     * @param comment an optional comment, stored in the history
     * @param idempotencyKey idempotency key, or null
     * @return the version
     * @throws ContentHashRequiredException when no hash is named and the setting is on
     * @throws ContentChangedException when the stored content is not the content reviewed; nothing is written
     * @throws SelfApprovalForbiddenException when the caller requested the approval (PRE-019); nothing is written
     * @throws VersionSupersededException when a newer version of the key is already active (PRE-021)
     * @throws InvalidStateException when the version is not pending approval
     * @throws InputInvalidException when the hash is not 64 lower-case hexadecimal characters
     */
    default DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String expectedDefinitionHash,
            String comment, String idempotencyKey) {
        return approve(ref, expectedLockVersion, expectedDefinitionHash, null, comment, idempotencyKey);
    }

    /**
     * Approves a pending version, bound to the content reviewed and, when the change set against the effective version
     * has more than {@link GraphDiff#MAX_CHANGES} changed values, to the whole change set the approver read page by
     * page (PRE-199, #426, PD-139). Everything of {@link #approve(DefinitionRef, long, String, String, String)} holds.
     * In addition, when the change set is larger than the limit, {@code expectedChangeSetHash} is required: without it
     * the approval is refused with {@code CHANGE_SET_TRUNCATED} as before, and with a hash that is not the hash of the
     * change set now (the content or the effective version changed after the review) it is refused with
     * {@code CONTENT_CHANGED}. Either way nothing is written. For a change set within the limit the hash is optional,
     * and checked when given.
     *
     * @param ref the version
     * @param expectedLockVersion its lock version
     * @param expectedDefinitionHash the {@code definitionHash} the approver reviewed
     * @param expectedChangeSetHash the {@code changeSetHash} of the last page of the paged diff, 64 lower-case
     *     hexadecimal characters; null when the change set was read in one piece
     * @param comment an optional comment, stored in the history
     * @param idempotencyKey idempotency key, or null
     * @return the version
     * @throws ChangeSetTruncatedException when the change set exceeds the limit and no change-set hash is named
     * @throws ContentChangedException when the stored content or the change set is not the one reviewed
     * @throws InputInvalidException when a hash is not 64 lower-case hexadecimal characters
     */
    DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String expectedDefinitionHash,
            String expectedChangeSetHash, String comment, String idempotencyKey);

    /**
     * Sends a pending version back to {@code DRAFT} with a reason (PRE-021).
     *
     * @param ref the version
     * @param expectedLockVersion its lock version
     * @param reason why; required
     * @param idempotencyKey idempotency key, or null
     * @return the version, now a draft
     * @throws InputInvalidException when the reason is blank or too long
     * @throws InvalidStateException when the version is not pending approval
     */
    DefinitionVersion reject(DefinitionRef ref, long expectedLockVersion, String reason, String idempotencyKey);

    /**
     * Retires an active version ({@code processes:approve}, PD-102) or withdraws a draft ({@code processes:author}).
     * Retiring stops new starts only: running instances keep their version. A retirement of an active version needs a
     * reason and runs in the {@code single} approval mode ({@code horus.bpm.lifecycle.retire-approval}); the history
     * row and {@code retired.v1} record the mode. Retiring the last version in effect of the key follows
     * {@code horus.bpm.lifecycle.retire-last-version} (PD-101): under {@code confirm}, the default, it needs
     * {@code confirmNoVersionInEffect}; under {@code refuse} it is refused.
     *
     * @param ref the version
     * @param expectedLockVersion its lock version
     * @param reason why; required for an active version, optional for a draft
     * @param confirmNoVersionInEffect the caller accepts that new starts of the key will fail until a new version is
     *     approved
     * @param idempotencyKey idempotency key, or null
     * @return the version, now retired
     * @throws InvalidStateException when the version is pending approval or already retired
     * @throws InputInvalidException when an active version is retired without a reason
     * @throws LastVersionInEffectException when it is the last version in effect and that is not confirmed or allowed
     */
    DefinitionVersion retire(DefinitionRef ref, long expectedLockVersion, String reason, boolean confirmNoVersionInEffect,
            String idempotencyKey);

    /**
     * @param ref the version
     * @return the version in the caller's tenant
     */
    Optional<DefinitionVersion> find(DefinitionRef ref);

    /**
     * @param ref the version
     * @return its lifecycle history, oldest first
     * @throws DefinitionNotFoundException when the tenant has no such version
     */
    List<DefinitionHistoryEntry> history(DefinitionRef ref);

    /**
     * Reads one version of a definition in the caller's tenant. Needs {@code processes:read}.
     *
     * @param key the definition key
     * @param version the version number, from 1
     * @return the version, without its source document
     * @throws DefinitionNotFoundException when the tenant has no such version
     */
    DefinitionVersion version(String key, int version);

    /**
     * Writes a stored version of the caller's tenant as a document of the given format (PRE-034). Needs
     * {@code processes:read}. The stored source is read again and must still give the recorded definition hash
     * before anything is written; importing the result gives a version with the same hash. Read-only: nothing is
     * stored and no event is published.
     *
     * @param key the definition key
     * @param version the version number within the key
     * @param format the document format; {@link SourceFormat#BPMN_XML} in this release
     * @return the document
     * @throws DefinitionNotFoundException when the tenant has no such version (also for another tenant's version)
     * @throws InputInvalidException when the format cannot be exported
     * @throws DefinitionIntegrityException when the stored source no longer gives the recorded hash
     */
    DefinitionExport exportDefinition(String key, int version, SourceFormat format);

    /**
     * Compares two stored versions of one definition key in the caller's tenant (PRE-025, PRE-176): the steps and
     * links added, removed and changed, computed from the stored, hash-checked definitions of any lifecycle status.
     * Needs {@code processes:read}. Read-only: nothing is stored and no event is published. The same two versions
     * always give the same diff; layout is never part of it.
     *
     * @param base the version compared from
     * @param target the version compared to, of the same key
     * @return the diff
     * @throws DefinitionNotFoundException when the tenant has no such version (also for another tenant's version)
     * @throws InputInvalidException when the two versions belong to different keys
     * @throws DefinitionIntegrityException when a stored source no longer gives its recorded hash
     */
    GraphDiff diff(DefinitionRef base, DefinitionRef target);

    /**
     * Compares a stored version with the effective version of its key, as {@link #diff(DefinitionRef, DefinitionRef)}.
     *
     * @param target the version compared to
     * @return the diff from the effective version to {@code target}
     * @throws NoEffectiveVersionException when the key has no effective version
     * @throws DefinitionNotFoundException when the tenant has no such version
     */
    GraphDiff diffFromEffective(DefinitionRef target);

    /**
     * The fields of a version that declare no classification (PD-146, #438), in step order: inputs of agent steps
     * without {@code mask}, then the output schema properties without {@code mask}. Empty when every field is
     * classified, or when {@code horus.bpm.processes.classification.warn-unclassified} is {@code false}. Permission
     * {@code processes:read}; another tenant's version is not found.
     *
     * @param ref the version
     * @return the unclassified fields; a reminder only, never a reason to refuse
     * @throws DefinitionNotFoundException when the version does not exist for the caller's tenant
     */
    default List<UnclassifiedField> unclassifiedFields(DefinitionRef ref) {
        return List.of();
    }

    /**
     * Reads the complete change set between two versions one page at a time (PRE-199, #426): every change on exactly
     * one page, in a fixed order. The last page carries the change-set hash and the approvable hash; each cursor
     * carries a digest of everything before it. Needs {@code processes:read}; read-only.
     *
     * @param base the version compared from
     * @param target the version compared to, of the same key
     * @param limit changes per page, 1 to 500; null for 200
     * @param cursor the previous page's {@code nextCursor}; null for the first page
     * @return the page
     * @throws QueryInvalidException when the limit or the cursor is not valid
     */
    GraphDiffPage diffPage(DefinitionRef base, DefinitionRef target, Integer limit, String cursor);

    /**
     * As {@link #diffPage(DefinitionRef, DefinitionRef, Integer, String)}, against the effective version of the key.
     *
     * @param target the version compared to
     * @param limit changes per page, 1 to 500; null for 200
     * @param cursor the previous page's {@code nextCursor}; null for the first page
     * @return the page
     * @throws NoEffectiveVersionException when the key has no effective version
     */
    GraphDiffPage diffPageFromEffective(DefinitionRef target, Integer limit, String cursor);
}
