package io.github.dhluong90.bpm.rules.api;

import java.util.List;
import java.util.Optional;

/**
 * Imports rule set versions and moves them through the lifecycle with maker-checker (TR-SEC-04).
 * Safe for concurrent use.
 *
 * <p>Transactions: every command joins the caller's transaction when there is one, or runs in its
 * own otherwise. Each command writes the version row, one outbox row and, with an idempotency key,
 * one {@code rle_idempotency} row, all in that transaction. The tenant and the actor come from the
 * {@code TenantResolver} and {@code CallerResolver}, never from an argument.
 *
 * <p>Permissions (ADR-0010) are checked here, not only over REST: import and {@code requestPublish} need
 * {@code rules:author}, {@code approve} and {@code reject} need {@code rules:approve}, {@code withdraw} needs {@code rules:author} and
 * the caller must be the requester, {@code replaceDraft} needs {@code rules:author} and the draft's authorship, every read and
 * {@code export} need {@code rules:read}. A
 * caller without them gets {@link PermissionDeniedException}; an unresolved caller or tenant gets
 * {@link UnauthenticatedException}. The approver must be neither the requester nor the author of the version.
 */
public interface VersionService {

    /**
     * Imports a document as version 1 of a new rule set, in status {@code DRAFT}. The rule set name is
     * read from the document.
     *
     * @param request the source document
     * @return the created version
     * @throws RuleSetExistsException when the tenant already has the rule set
     * @throws ValidationFailedException when the document cannot be read or compiled
     */
    RuleSetVersion importRuleSet(ImportRequest request);

    /**
     * Imports a document as the next draft version of an existing rule set.
     *
     * @param ruleSet the rule set name; must equal the name in the document
     * @param request the source document
     * @return the created version
     * @throws RuleSetNotFoundException when the tenant has no such rule set
     */
    RuleSetVersion importVersion(String ruleSet, ImportRequest request);

    /**
     * Replaces the document of a draft in place (RLE-047): the same reading, validation and violations as
     * {@link #importVersion}, the canonical and source hashes recomputed, the version number, author and creation time
     * kept, and the lock version incremented. Writes one {@code DRAFT_UPDATED} history row with the old and new
     * canonical hash and one {@code rules.version.draft-updated.v1} event. Needs {@code rules:author}, the
     * caller must be the draft's author, and the host must allow draft authoring
     * ({@code horus.bpm.rules.authoring.cockpit-drafts}).
     *
     * @param ruleSet the rule set name; must equal the name in the document
     * @param version the version number
     * @param request the new source document
     * @param options idempotency key and optimistic lock; the key of {@code options} wins over the request's
     * @return the version with its new document
     * @throws DraftAuthoringDisabledException when the host turned draft authoring off
     * @throws PermissionDeniedException when the caller lacks {@code rules:author} or is not the draft's author
     * @throws InvalidStateException when the version is not a draft
     * @throws VersionConflictException when the lock version is stale
     * @throws ValidationFailedException when the document cannot be read or compiled; nothing is stored
     * @throws InputInvalidException when the document names another rule set
     */
    RuleSetVersion replaceDraft(String ruleSet, int version, ImportRequest request, WriteOptions options);

    /**
     * Compares two versions of a rule set row by row, from their canonical models (RLE-028, RLE-048). Writes nothing.
     * Needs {@code rules:read}.
     *
     * @param ruleSet the rule set name
     * @param version the version compared
     * @param base the version it is compared against; must differ from {@code version}
     * @return the diff
     * @throws InputInvalidException when {@code base} equals {@code version}
     * @throws RuleSetNotFoundException when the tenant has no such rule set
     * @throws VersionNotFoundException when either version does not exist
     */
    VersionDiff diff(String ruleSet, int version, int base);

    /**
     * Moves a draft to {@code PENDING_APPROVAL} and records the caller as requester.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key and optimistic lock
     * @return the updated version
     * @throws InvalidStateException when the version is not a draft
     * @throws ValidationFailedException when the draft no longer passes the checks of import (RLE-004); it stays a draft
     */
    RuleSetVersion requestPublish(String ruleSet, int version, WriteOptions options);

    /**
     * Checks a stored version with the same reading, parsing and type checking as import and
     * {@link #requestPublish}, without changing it (RLE-004). Every problem is listed with its rule, column, code
     * (for example {@code UNKNOWN_INPUT}, {@code TYPE_MISMATCH} or {@code FORBIDDEN_CONSTRUCT}) and character
     * position. Needs {@code rules:read}.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @throws ValidationFailedException when the version does not pass, listing every problem
     * @throws VersionNotFoundException when the version does not exist
     * @throws RuleSetNotFoundException when the rule set does not exist
     */
    default void validate(String ruleSet, int version) {
        throw new UnsupportedOperationException("validate is not supported by " + getClass().getName());
    }

    /**
     * Approves a pending version as a second person; it becomes {@code PUBLISHED}.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, optimistic lock and comment
     * @return the published version
     * @throws SelfApprovalForbiddenException when the caller requested the publication
     * @throws InvalidStateException when the version is not pending approval
     * @throws AuthorUnknownException when the version has no recorded author (PD-105)
     */
    RuleSetVersion approve(String ruleSet, int version, WriteOptions options);

    /**
     * Sends a pending version back to {@code DRAFT} with a reason (DESIGN section 3.3, RLE-025). The requester may
     * not reject their own request; they withdraw it instead (PD-089).
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, optimistic lock and the reason in {@link WriteOptions#comment()}, required
     * @return the version, now a draft
     * @throws InputInvalidException when the reason is blank or longer than {@code lifecycle.reason-max-length}
     * @throws InvalidStateException when the version is not pending approval
     * @throws SelfApprovalForbiddenException when the caller requested the publication
     */
    RuleSetVersion reject(String ruleSet, int version, WriteOptions options);

    /**
     * Takes a published version out of service by hand (ADR-0025, PD-080, RLE-026). In the default
     * {@code lifecycle.retire-approval: single} mode one holder of {@code rules:approve} with a reason is enough. In
     * {@code four-eyes} mode, and for the last version in effect with {@code lifecycle.retire-last-version: four-eyes},
     * this only records a retirement request: the version stays {@code PUBLISHED} and serving, one
     * {@code RETIRE_REQUESTED} history row and one {@code rules.version.retire-requested.v1} event are written, and
     * {@link #confirmRetire} by another approver retires it (RLE-038); a second request while one is pending is
     * {@link InvalidStateException}. The
     * version becomes {@code RETIRED} from the transaction's clock on: evaluation stops selecting it on every replica,
     * while a clock before it still does and logged decisions still replay. Older published versions it had
     * superseded are retired with it, so a retire never brings an older version back. Writes one {@code RETIRED}
     * history row with the approval mode and one {@code rules.version.retired.v1} event.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, optimistic lock and the required reason in {@link WriteOptions#comment()}
     * @return the version, now retired
     * @throws InputInvalidException when the reason is blank or longer than {@code lifecycle.reason-max-length}
     * @throws InvalidStateException when the version is not published, or a newer version in effect supersedes it
     * @throws VersionConflictException when the lock version is stale
     */
    RuleSetVersion retire(String ruleSet, int version, WriteOptions options);

    /**
     * Retires a published version as {@link #retire(String, int, WriteOptions)} does, and lets it be the last version
     * in effect of its rule set when {@code confirmNoVersionInEffect} is true (PD-101). Without the confirmation, a
     * retirement that would leave no version in effect is refused with {@link LastVersionInEffectException}; with it,
     * the history row and the event record that no version remains in effect. A host with
     * {@code horus.bpm.lifecycle.retire-last-version: refuse} refuses it either way.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, optimistic lock and the required reason
     * @param confirmNoVersionInEffect true to accept that no version will be in effect afterwards
     * @return the version, now retired
     * @throws LastVersionInEffectException when it is the last version in effect and that is not confirmed or allowed
     */
    RuleSetVersion retire(String ruleSet, int version, WriteOptions options, boolean confirmNoVersionInEffect);

    /**
     * Confirms a pending retirement as the second approver (PD-080, RLE-038). The version becomes {@code RETIRED} with
     * the same effects as a retirement in {@code single} mode, and the history row and the
     * {@code rules.version.retired.v1} event record {@code approvalMode: four-eyes}, the requester, the confirmer and
     * the reason of the request. The last-version rule (PD-101) is checked again at this moment: when no other
     * version would remain in effect, the request or this call must carry the confirmation, and a host with
     * {@code retire-last-version: refuse} refuses it.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key and the optimistic lock (required); a comment is not used
     * @param confirmNoVersionInEffect true to accept that no version will be in effect afterwards
     * @return the version, now retired
     * @throws InvalidStateException when no retirement is pending, or a newer version in effect supersedes it
     * @throws SelfApprovalForbiddenException when the caller requested the retirement
     * @throws LastVersionInEffectException when it is the last version in effect and that is not confirmed or allowed
     * @throws PreconditionRequiredException when no lock version is given
     * @throws VersionConflictException when the lock version is stale
     */
    RuleSetVersion confirmRetire(String ruleSet, int version, WriteOptions options, boolean confirmNoVersionInEffect);

    /**
     * Withdraws a pending retirement request with a reason (RLE-038). The version stays {@code PUBLISHED} and serving;
     * one {@code RETIRE_CANCELLED} history row records who withdrew it and why. Any holder of {@code rules:approve},
     * the requester included, may withdraw it.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, the optimistic lock (required) and the reason in {@link WriteOptions#comment()}
     * @return the version, still published, with no pending retirement
     * @throws InputInvalidException when the reason is blank or longer than {@code lifecycle.reason-max-length}
     * @throws InvalidStateException when no retirement is pending
     * @throws PreconditionRequiredException when no lock version is given
     * @throws VersionConflictException when the lock version is stale
     */
    RuleSetVersion cancelRetire(String ruleSet, int version, WriteOptions options);

    /**
     * Takes the caller's own publication request back (PD-089, RLE-046): a pending version returns to {@code DRAFT},
     * nothing is published and the version in effect does not change. Writes one {@code WITHDRAWN} history row and
     * one {@code rules.version.withdrawn.v1} event. Needs {@code rules:author}, and the caller must be the requester.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, optimistic lock and the optional reason in {@link WriteOptions#comment()}
     * @return the version, now a draft
     * @throws InputInvalidException when the reason is longer than {@code lifecycle.reason-max-length}
     * @throws InvalidStateException when the version is not pending approval
     * @throws PermissionDeniedException when the caller lacks {@code rules:author} or is not the requester
     * @throws VersionConflictException when the lock version is stale
     */
    RuleSetVersion withdraw(String ruleSet, int version, WriteOptions options);

    /**
     * Lists the rule sets of the caller's tenant by name, a page at a time.
     *
     * @param page the page to read
     * @return the page
     * @throws QueryInvalidException when the limit is out of range or the cursor was not issued by this list
     */
    Page<RuleSetSummary> listRuleSets(PageRequest page);

    /**
     * Reads the summary of one rule set of the caller's tenant.
     *
     * @param ruleSet the rule set name
     * @return the summary, or empty when absent in the caller's tenant
     */
    Optional<RuleSetSummary> findRuleSet(String ruleSet);

    /**
     * Lists the versions of a rule set, newest first, a page at a time.
     *
     * @param ruleSet the rule set name
     * @param page the page to read
     * @return the page
     * @throws RuleSetNotFoundException when the tenant has no such rule set
     * @throws QueryInvalidException when the limit is out of range or the cursor was not issued by this list
     */
    Page<VersionSummary> listVersions(String ruleSet, PageRequest page);

    /**
     * Reads the lifecycle history of a version, oldest first.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @return the entries
     * @throws RuleSetNotFoundException when the tenant has no such rule set
     * @throws VersionNotFoundException when the rule set has no such version
     */
    List<VersionHistoryEntry> history(String ruleSet, int version);

    /**
     * Reads a version of the caller's tenant.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param includeSource whether to include the source document
     * @return the version, or empty when absent in the caller's tenant
     */
    Optional<RuleSetVersion> find(String ruleSet, int version, boolean includeSource);

    /**
     * Exports a version of the caller's tenant as a file that imports back with the same canonical hash. The file
     * carries no tenant, status, lock version, effective date, requester, approver or decision data. Writes nothing.
     * Needs {@code rules:read}.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param format {@link SourceFormat#JSON} (the canonical document) or {@link SourceFormat#DMN_XML} (DMN 1.5)
     * @return the document text, UTF-8
     * @throws RuleSetNotFoundException when the tenant has no such rule set
     * @throws VersionNotFoundException when the rule set has no such version
     * @throws InputInvalidException for a format this release cannot write
     * @throws ValidationFailedException when the version uses a construct with no DMN decision table form
     */
    String export(String ruleSet, int version, SourceFormat format);
}
