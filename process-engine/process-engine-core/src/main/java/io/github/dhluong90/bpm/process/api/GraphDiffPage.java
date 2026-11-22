package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * One page of the complete change set between two versions (PRE-199, #426). The change set is read as one ordered
 * sequence (steps added, steps removed, changed values, links added, links removed, each in its own sort order) and a
 * page is a window of it, so every change appears on exactly one page and none is cut off.
 *
 * <p>The change-set hash and the approvable hash are present on the last page only: an approver who wants them reads
 * every page in order, because each cursor carries a digest of everything before it.
 *
 * @param slice the changes of this page; {@code truncated} is always false
 * @param total the number of changes in the whole set
 * @param offset the position of the first change of this page in the whole set
 * @param nextCursor the cursor of the next page, or null on the last page
 * @param changeSetHash on the last page, the hash of the whole change set (both definition hashes and every change);
 *     null before it. This is the {@code expectedChangeSetHash} an approval sends when the set exceeds
 *     {@link GraphDiff#MAX_CHANGES}
 */
public record GraphDiffPage(GraphDiff slice, int total, int offset, String nextCursor, String changeSetHash) {

    public GraphDiffPage {
        Objects.requireNonNull(slice, "slice");
    }

    /** @return true when no page follows */
    public boolean last() {
        return nextCursor == null;
    }

    /**
     * The hash an approval of the target names after the whole set was read (PRE-177, PRE-199): the target's
     * definition hash on the last page, null before it.
     *
     * @return the target's definition hash on the last page, otherwise null
     */
    public String approvableHash() {
        return last() ? slice.target().definitionHash() : null;
    }
}
