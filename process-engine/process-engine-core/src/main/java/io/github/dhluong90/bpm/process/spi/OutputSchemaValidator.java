package io.github.dhluong90.bpm.process.spi;

import java.util.List;
import java.util.Map;

/**
 * Checks a step result against the step's output schema, inside the result transaction of an async step (#363). The
 * engine default supports a documented subset of JSON Schema (DESIGN section 4.1) and refuses any other keyword, so an
 * unsupported schema fails closed instead of passing. A host may register its own validator; the starter's default is
 * {@code @ConditionalOnMissingBean} (ADR-0024).
 *
 * <p>Runs inside a database transaction that holds the instance row lock: an implementation must be pure, in-process
 * and fast. It must never call the network, read files or run host scripts (ADR-0008, TR-SEC-01).
 */
@FunctionalInterface
public interface OutputSchemaValidator {

    /**
     * @param schema the step's output schema, as a JSON object
     * @param output the result to check
     * @return the violations, empty when the result is valid. Each names a path and a rule, never a value, because
     *     the output may carry personal data
     */
    List<String> validate(Map<String, Object> schema, Map<String, Object> output);

    /**
     * Checks a schema itself when a definition is imported (#52), so a schema this validator cannot evaluate is
     * refused before it becomes a draft instead of failing closed on every answer. The default accepts every schema;
     * the engine default refuses any keyword outside its subset.
     *
     * @param schema an agent step's output schema, as a JSON object
     * @return the problems, empty when the schema can be evaluated. Each names a path and a rule
     */
    default List<String> checkSchema(Map<String, Object> schema) {
        return List.of();
    }
}
