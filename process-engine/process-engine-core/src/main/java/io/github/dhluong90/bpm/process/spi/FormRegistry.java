package io.github.dhluong90.bpm.process.spi;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the reference a {@code userTask} declares with {@code horus:form} (a form key) or {@code horus:formSchema}
 * (a schema path) to a form the host owns (PRE-031, PRE-216). The engine asks at import, so an unknown reference is
 * refused before the definition is stored, and when a task form is read or a completion is checked.
 *
 * <p>The starter's default reads JSON documents from the classpath under {@code tasks.form-resource-root} and nothing
 * else; a host may register its own (for example one backed by its form service). The default is
 * {@code @ConditionalOnMissingBean} (ADR-0024). An implementation must be fast and side-effect free, and must never
 * run host scripts: the engine only evaluates the returned schema with its own validator (ADR-0008, TR-SEC-01).
 */
@FunctionalInterface
public interface FormRegistry {

    /** A registry that knows no form: every reference is refused at import. */
    FormRegistry NONE = (tenantId, reference) -> Optional.empty();

    /**
     * @param tenantId the tenant of the caller
     * @param reference a form key or a schema path, as the definition wrote it
     * @return the form, or empty when this host has none of that name
     */
    Optional<FormDefinition> find(String tenantId, String reference);

    /**
     * A resolved form.
     *
     * @param schema a JSON Schema for the task result, limited to what the task form contract allows
     *     ({@code task-form.v1.schema.json}); never null
     * @param hints display hints ({@code order}, {@code widgets}, {@code labels}, {@code readOnly}); never null,
     *     empty for none. A definition's own hints override these key by key
     */
    record FormDefinition(Map<String, Object> schema, Map<String, Object> hints) {

        /** Rejects a null schema and makes both maps unmodifiable. */
        public FormDefinition {
            schema = Map.copyOf(java.util.Objects.requireNonNull(schema, "schema must not be null"));
            hints = hints == null ? Map.of() : Map.copyOf(hints);
        }
    }
}
