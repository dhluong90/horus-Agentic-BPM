package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A proposed change of a business calendar and its decision: the record of who changed what and when (PD-118,
 * PD-131). A change applies to deadlines set after its approval; due times already set never move.
 *
 * @param id the change id
 * @param calendarKey the calendar
 * @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED}
 * @param approval {@code FOUR_EYES}, or {@code SINGLE} when the dev profile let the proposer apply it alone
 * @param baseVersion the calendar version the proposal was made against, 0 for a new calendar
 * @param previous the calendar before the change, or null for a new calendar
 * @param proposed the calendar after the change
 * @param reason why, as the proposer wrote it
 * @param proposedBy the proposer
 * @param proposedAt when it was proposed
 * @param decidedBy who approved or rejected it, or null while pending
 * @param decidedAt when, or null while pending
 * @param comment the decider's comment, or null
 */
public record CalendarChange(String id, String calendarKey, String status, String approval, long baseVersion,
        WorkingCalendar previous, WorkingCalendar proposed, String reason, String proposedBy, Instant proposedAt,
        String decidedBy, Instant decidedAt, String comment) {

    public CalendarChange {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(calendarKey, "calendarKey must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
    }
}
