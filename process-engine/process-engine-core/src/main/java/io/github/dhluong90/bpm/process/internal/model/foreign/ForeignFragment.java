package io.github.dhluong90.bpm.process.internal.model.foreign;

import java.util.Objects;

/**
 * A vendor or unknown extension element kept as an opaque, size-limited XML fragment. Never parsed beyond
 * well-formedness and never executed; written back on export (ADR-0018).
 *
 * @param namespace the element namespace
 * @param localName the element local name
 * @param xml the fragment as read
 */
public record ForeignFragment(String namespace, String localName, String xml) {

    public ForeignFragment {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(localName, "localName must not be null");
        Objects.requireNonNull(xml, "xml must not be null");
    }
}
