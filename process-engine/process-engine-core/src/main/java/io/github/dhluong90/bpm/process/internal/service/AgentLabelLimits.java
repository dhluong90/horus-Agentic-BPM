package io.github.dhluong90.bpm.process.internal.service;

import java.util.Map;

/**
 * Bounds of the labels an agent reports with its answer (PD-014, #59, DESIGN section 4.1), bound by the starter from
 * {@code horus.bpm.processes.agents.labels.*}. The engine records labels in history and never acts on one; the bounds
 * keep history rows small. They may be lowered, never raised.
 *
 * @param maxCount {@code agents.labels.max-count}: labels per answer, 0 to {@value #MAX_COUNT}
 * @param maxKeyLength {@code agents.labels.max-key-length}: characters of a key, 1 to {@value #MAX_KEY_LENGTH}
 * @param maxValueLength {@code agents.labels.max-value-length}: characters of a value, 1 to
 *     {@value #MAX_VALUE_LENGTH}
 */
public record AgentLabelLimits(int maxCount, int maxKeyLength, int maxValueLength) {

    /** Default and highest {@code max-count}. */
    public static final int MAX_COUNT = 16;
    /** Default and highest {@code max-key-length}. */
    public static final int MAX_KEY_LENGTH = 64;
    /** Default and highest {@code max-value-length}. */
    public static final int MAX_VALUE_LENGTH = 256;
    /** Error code of an answer whose labels break the bounds: a failed attempt, retried like any other. */
    public static final String LABELS_INVALID = "AGENT_LABELS_INVALID";
    /** The defaults. */
    public static final AgentLabelLimits DEFAULTS = new AgentLabelLimits(MAX_COUNT, MAX_KEY_LENGTH, MAX_VALUE_LENGTH);

    public AgentLabelLimits {
        if (maxCount < 0 || maxCount > MAX_COUNT) {
            throw new IllegalArgumentException("agents.labels.max-count must be 0 to " + MAX_COUNT);
        }
        if (maxKeyLength < 1 || maxKeyLength > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("agents.labels.max-key-length must be 1 to " + MAX_KEY_LENGTH);
        }
        if (maxValueLength < 1 || maxValueLength > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("agents.labels.max-value-length must be 1 to " + MAX_VALUE_LENGTH);
        }
    }

    /**
     * @param labels an answer's labels
     * @return null when they are within the bounds, otherwise which bound they break, never a key or a value
     */
    public String violation(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        if (labels.size() > maxCount) {
            return labels.size() + " labels, at most " + maxCount;
        }
        for (Map.Entry<String, String> label : labels.entrySet()) {
            String key = label.getKey();
            if (key == null || key.isBlank() || key.length() > maxKeyLength) {
                return "a label key is blank or longer than " + maxKeyLength + " characters";
            }
            if (label.getValue() == null || label.getValue().length() > maxValueLength) {
                return "the value of a label is missing or longer than " + maxValueLength + " characters";
            }
        }
        return null;
    }
}
