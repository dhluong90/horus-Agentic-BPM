package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Objects;

/**
 * Key of a compile-time facet of a {@link CompiledProcess}, for example a parsed condition per sequence flow.
 *
 * @param name unique name, prefixed by the owning stream
 * @param type value type
 * @param <T> value type
 */
public record FacetKey<T>(String name, Class<T> type) {

    public FacetKey {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }
}
