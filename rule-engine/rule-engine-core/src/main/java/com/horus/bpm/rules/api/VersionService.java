package com.horus.bpm.rules.api;

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
 * {@code rules:author}, {@code approve} needs {@code rules:approve}, {@code find} and {@code export} need {@code rules:read}. A
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
     * Moves a draft to {@code PENDING_APPROVAL} and records the caller as requester.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key and optimistic lock
     * @return the updated version
     * @throws InvalidStateException when the version is not a draft
     */
    RuleSetVersion requestPublish(String ruleSet, int version, WriteOptions options);

    /**
     * Approves a pending version as a second person; it becomes {@code PUBLISHED}.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param options idempotency key, optimistic lock and comment
     * @return the published version
     * @throws SelfApprovalForbiddenException when the caller requested the publication
     * @throws InvalidStateException when the version is not pending approval
     */
    RuleSetVersion approve(String ruleSet, int version, WriteOptions options);

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
