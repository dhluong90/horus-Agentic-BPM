package io.github.dhluong90.bpm.process.api;

import java.util.List;
import java.util.Optional;

/**
 * The diagram layout of a definition version, kept apart from the definition (#102, PRE-026, DESIGN section 5.4).
 * Revisions are appended, never changed; saving one needs no approval and never changes the definition, its status, its
 * hash or its lock version, so tidying a diagram does not disturb an approved version. A layout may list a subset of
 * the steps; a client places the rest by automatic layout.
 *
 * <p>The tenant and the caller come from the resolvers; another tenant's version answers
 * {@link DefinitionNotFoundException}. Saving needs {@code processes:author}; reading needs {@code processes:read}.
 * Saving is allowed while {@code authoring.cockpit-drafts} is off, because a layout is not a draft. Safe for
 * concurrent use.
 */
public interface LayoutService {

    /**
     * Appends a revision to the layout of a version of any status.
     *
     * @param ref the version
     * @param expectedRevision the latest revision the client saw, 0 when the version has none
     * @param layoutJson the layout document, {@code definition-layout.v1}, at most 256 KB
     * @param idempotencyKey idempotency key, or null; a repeat returns the first answer and writes nothing
     * @return the saved revision, {@code expectedRevision + 1}
     * @throws VersionConflictException when the latest revision is not {@code expectedRevision}, including a save that
     *     lost a race with another
     * @throws LayoutInvalidException when the document breaks the schema or names a step the definition does not have
     * @throws DefinitionNotFoundException when the caller's tenant has no such version
     * @throws IdempotencyKeyReuseException when the key was used with a different request
     */
    LayoutRevision saveLayout(DefinitionRef ref, long expectedRevision, String layoutJson, String idempotencyKey);

    /**
     * @param ref the version
     * @return the latest revision with its document, or empty when none was saved
     * @throws DefinitionNotFoundException when the caller's tenant has no such version
     */
    Optional<LayoutRevision> latestLayout(DefinitionRef ref);

    /**
     * @param ref the version
     * @param revision the revision number
     * @return that revision with its document, or empty when it does not exist
     * @throws DefinitionNotFoundException when the caller's tenant has no such version
     */
    Optional<LayoutRevision> layoutRevision(DefinitionRef ref, int revision);

    /**
     * @param ref the version
     * @param afterRevision list the revisions after this one; 0 for the first page
     * @param limit at most this many, 1 to 200
     * @return revisions in ascending order, without documents
     * @throws DefinitionNotFoundException when the caller's tenant has no such version
     */
    List<LayoutRevision> layoutRevisions(DefinitionRef ref, int afterRevision, int limit);
}
