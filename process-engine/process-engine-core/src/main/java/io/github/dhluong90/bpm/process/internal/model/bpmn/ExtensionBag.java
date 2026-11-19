package io.github.dhluong90.bpm.process.internal.model.bpmn;

import io.github.dhluong90.bpm.process.internal.model.foreign.ForeignFragment;
import io.github.dhluong90.bpm.process.internal.model.horus.HorusExtension;
import java.util.List;
import java.util.Optional;

/**
 * Contents of {@code extensionElements}: typed {@code horus} records and foreign fragments kept opaque.
 *
 * @param horus horus
 * @param foreign foreign
 */
public record ExtensionBag(
        List<HorusExtension> horus,
        List<ForeignFragment> foreign) {

    public ExtensionBag {
        horus = horus == null ? List.of() : List.copyOf(horus);
        foreign = foreign == null ? List.of() : List.copyOf(foreign);
    }

    /** An empty bag. */
    public static final ExtensionBag EMPTY = new ExtensionBag(List.of(), List.of());

    /**
     * @param type the extension record type
     * @param <T> the type
     * @return the first extension of that type
     */
    public <T extends HorusExtension> Optional<T> first(Class<T> type) {
        return horus.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /**
     * @param type the extension record type
     * @param <T> the type
     * @return every extension of that type, in document order
     */
    public <T extends HorusExtension> List<T> all(Class<T> type) {
        return horus.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
