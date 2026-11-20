package io.github.dhluong90.bpm.process.internal.service;

/**
 * Bounds of the memory an agent step receives from earlier steps (#101, DESIGN section 4.1), bound by the starter
 * from {@code horus.bpm.processes.agents.memory.*}. They may be lowered, never raised.
 *
 * @param maxEntries {@code agents.memory.max-entries}: memory entries per agent step, checked at import, 0 to
 *     {@value #MAX_ENTRIES}; 0 refuses every step that declares memory
 * @param maxBytes {@code agents.memory.max-bytes}: UTF-8 bytes of the resolved memory as canonical JSON, checked when
 *     the step is reached, 1 to {@value #MAX_BYTES}
 */
public record AgentMemoryLimits(int maxEntries, int maxBytes) {

    /** Default and highest {@code max-entries}. */
    public static final int MAX_ENTRIES = 16;
    /** Default and highest {@code max-bytes}: 16 KiB. */
    public static final int MAX_BYTES = 16 * 1024;
    /** The defaults. */
    public static final AgentMemoryLimits DEFAULTS = new AgentMemoryLimits(MAX_ENTRIES, MAX_BYTES);

    public AgentMemoryLimits {
        if (maxEntries < 0 || maxEntries > MAX_ENTRIES) {
            throw new IllegalArgumentException("agents.memory.max-entries must be 0 to " + MAX_ENTRIES);
        }
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("agents.memory.max-bytes must be 1 to " + MAX_BYTES);
        }
    }
}
