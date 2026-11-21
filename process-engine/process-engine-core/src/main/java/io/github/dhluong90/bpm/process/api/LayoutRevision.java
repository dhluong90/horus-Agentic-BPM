package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One saved revision of the diagram layout of a definition version (#102, PRE-026). A revision is never changed or
 * deleted. It holds positions only, so saving one leaves the definition, its status, its hash and its lock version as
 * they were.
 *
 * @param revision the revision number, from 1, one more than the revision it was saved over
 * @param layoutJson the layout document ({@code definition-layout.v1}), canonical JSON; empty in a list of revisions
 * @param layoutHash SHA-256 of the canonical layout document, 64 lower-case hexadecimal characters
 * @param definitionHash the {@code definitionHash} of the version the layout was drawn for
 * @param source how it came in: {@code API} or {@code COCKPIT}
 * @param createdBy who saved it
 * @param createdAt when
 */
public record LayoutRevision(int revision, String layoutJson, String layoutHash, String definitionHash, String source,
        String createdBy, Instant createdAt) {

    public LayoutRevision {
        Objects.requireNonNull(layoutHash, "layoutHash");
        Objects.requireNonNull(definitionHash, "definitionHash");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** @return the same revision without its document, as a revision list shows it */
    public LayoutRevision summary() {
        return new LayoutRevision(revision, null, layoutHash, definitionHash, source, createdBy, createdAt);
    }
}
