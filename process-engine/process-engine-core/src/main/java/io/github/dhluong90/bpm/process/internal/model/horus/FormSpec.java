package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:form}, {@code horus:formSchema} with {@code horus:formHints}, or {@code horus:formLink}: exactly one
 * of a form key, a schema path or a link (PRE-031). A form is declared by the definition and resolved by the host's
 * {@link io.github.dhluong90.bpm.process.spi.FormRegistry}; the engine never reads a file itself.
 *
 * @param formKey a form key registered by the host, or null
 * @param schema the path of a JSON Schema the host resolves, or null
 * @param hints display hints as canonical JSON, only with {@code schema}; null for none
 * @param link a URL template for the host's own screen ({@code {taskId}} and {@code {instanceId}} are replaced), or
 *     null
 */
public record FormSpec(
        String formKey,
        String schema,
        String hints,
        String link) implements HorusExtension {

    /** @return true when the task is completed in the host's own screen */
    public boolean isLink() {
        return link != null;
    }
}
