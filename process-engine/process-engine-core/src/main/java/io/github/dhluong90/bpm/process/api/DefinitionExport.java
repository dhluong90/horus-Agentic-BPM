package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * A definition version written as a document (PRE-034). Importing {@code document} gives a version with the same
 * {@code definitionHash}.
 *
 * @param key the definition key
 * @param version the version number within the key
 * @param definitionHash the definition hash of the exported version
 * @param format the format of {@code document}
 * @param document the document text, UTF-8 when written as bytes
 */
public record DefinitionExport(String key, int version, String definitionHash, SourceFormat format, String document) {

    public DefinitionExport {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(definitionHash, "definitionHash must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(document, "document must not be null");
    }
}
