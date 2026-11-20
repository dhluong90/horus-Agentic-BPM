package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Imports process definitions and moves their versions through the lifecycle with four eyes (ADR-0012, DESIGN
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
    DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String comment, String idempotencyKey);

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
}
