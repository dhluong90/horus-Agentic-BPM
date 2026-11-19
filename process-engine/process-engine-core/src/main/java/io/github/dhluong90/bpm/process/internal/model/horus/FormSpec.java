package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:form}: a form key, a schema path or a link.
 *
 * @param formKey formKey
 * @param schema schema
 * @param link link
 */
public record FormSpec(
        String formKey,
        String schema,
        String link) implements HorusExtension {
}
