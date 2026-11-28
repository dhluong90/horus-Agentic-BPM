package io.github.dhluong90.bpm.expr.feel;

/**
 * A FEEL range value. A bound is null when open-ended. Both bounds are of one orderable type: number, string, date,
 * time, date and time, duration or money of one currency.
 *
 * @param start lower bound, or null
 * @param startIncluded whether the lower bound belongs to the range
 * @param end upper bound, or null
 * @param endIncluded whether the upper bound belongs to the range
 */
public record FeelRange(Object start, boolean startIncluded, Object end, boolean endIncluded) { }
