package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One page of a rule set's history: its versions, newest first, each with its lifecycle events oldest first, and on the
 * first page the rule set's own events, newest first (PD-116).
 *
 * @param ruleSet the rule set name
 * @param versions the versions of this page, newest first
 * @param ruleSetEvents the rule set's own events, newest first; empty after the first page
 * @param ruleSetEventsTruncated whether the rule set has more own events than listed
 * @param nextCursor the cursor of the next page, or null on the last page
 */
public record RuleSetHistory(String ruleSet, List<VersionLine> versions, List<RuleSetEvent> ruleSetEvents,
        boolean ruleSetEventsTruncated, String nextCursor) {

    /** Most events listed per version; the version history endpoint lists all of them. */
    public static final int MAX_EVENTS_PER_VERSION = 100;

    /** Most rule set events listed on the first page. */
    public static final int MAX_RULE_SET_EVENTS = 100;

    public RuleSetHistory {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        versions = List.copyOf(versions);
        ruleSetEvents = List.copyOf(ruleSetEvents);
    }

    /**
     * One version with its lifecycle fields and events.
     *
     * @param version the version number
     * @param status the status now
     * @param effectiveFrom when it takes or took effect, or null
     * @param effectiveTo when it was retired, by hand or superseded, or null
     * @param canonicalHash the canonical hash
     * @param createdBy the author, or null when not recorded
     * @param createdAt when it was created
     * @param requestedBy the latest requester, or null
     * @param requestedAt when, or null
     * @param approvedBy the approver, or null
     * @param approvedAt when, or null
     * @param approvalComment the approval comment, or null
     * @param rejectedBy the actor of the latest {@code REJECTED} event listed, or null
     * @param rejectedAt when, or null
     * @param rejectionReason its reason, or null
     * @param events the version's events, oldest first, at most {@link #MAX_EVENTS_PER_VERSION}
     * @param eventsTruncated whether the version has more events than listed
     */
    public record VersionLine(int version, VersionStatus status, Instant effectiveFrom, Instant effectiveTo,
            String canonicalHash, String createdBy, Instant createdAt, String requestedBy, Instant requestedAt,
            String approvedBy, Instant approvedAt, String approvalComment, String rejectedBy, Instant rejectedAt,
            String rejectionReason, List<VersionHistoryEntry> events, boolean eventsTruncated) {

        public VersionLine {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            events = List.copyOf(events);
        }
    }

    /**
     * An event of the rule set itself rather than of one version.
     *
     * @param action what happened
     * @param actor who did it
     * @param occurredAt when
     * @param evidence what was exported
     */
    public record RuleSetEvent(Action action, String actor, Instant occurredAt, EvidenceSummary evidence) {

        public RuleSetEvent {
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** The actions of rule set events. */
    public enum Action {
        /** An evidence file of versions and decisions was exported. */
        EVIDENCE_EXPORTED
    }

    /**
     * What one evidence export held.
     *
     * @param format the file format
     * @param versions the version numbers, ascending
     * @param decisionIds the decision ids, in the order listed
     * @param contentSha256 the SHA-256 of the file's content
     * @param bytes the file size in bytes
     */
    public record EvidenceSummary(EvidenceFormat format, List<Integer> versions, List<String> decisionIds,
            String contentSha256, long bytes) {

        public EvidenceSummary {
            Objects.requireNonNull(format, "format must not be null");
            versions = List.copyOf(versions);
            decisionIds = List.copyOf(decisionIds);
            Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
        }
    }
}
