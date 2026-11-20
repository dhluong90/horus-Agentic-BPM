package io.github.dhluong90.bpm.process.internal.model.horus;

import java.util.List;
import java.util.Objects;

/**
 * One {@code <horus:memory from="extract-id" fields="fullName,dob" as="applicant"/>} of an agent step (#101, DESIGN
 * section 4.1): declared fields of an earlier agent step's recorded result in the same instance, passed to the agent
 * under the name {@code as}. Part of the definition, so the definition hash, the diff and the approval cover it,
 * {@link #includeMasked()} included.
 *
 * @param from the id of the earlier agent step whose recorded result is read
 * @param fields the field paths read from that result, dot-separated for nested objects; never a wildcard
 * @param as the name the fields are passed under
 * @param required whether the step fails with {@code AGENT_MEMORY_MISSING} when the earlier step has no recorded result
 *     in the instance; without it the entry is left out and history lists it as missing
 * @param includeMasked whether fields the source step's output schema marks {@code mask: true} may be sent; history
 *     still keeps them only as keyed fingerprints
 */
public record MemoryEntry(String from, List<String> fields, String as, boolean required, boolean includeMasked)
        implements HorusExtension {

    public MemoryEntry {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(as, "as must not be null");
        fields = List.copyOf(fields);
    }
}
