package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the history of an agent step keeps of its inputs and of a reviewer's override (#59, DESIGN section 4.1). Pure
 * functions; the masking key never passes through here, only the host's {@link InputMasker}.
 */
final class AgentAudit {

    private static final System.Logger LOG = System.getLogger(AgentAudit.class.getName());

    private AgentAudit() {
    }

    /**
     * The audit copy of an agent step's inputs: an input the approved definition marks {@code mask="true"} becomes
     * what the masker returns, never its plain value; every other input is kept as the agent received it. A masker
     * that fails, or returns the plain value, gives {@value InputMasker#REDACTED}: masking fails closed.
     *
     * @param tenantId the instance's tenant
     * @param node the agent step of the version being run
     * @param inputs the resolved inputs
     * @param masker the host's masker
     * @return the audit copy
     */
    static Map<String, Object> maskInputs(String tenantId, FlowNode node, Map<String, Object> inputs,
            InputMasker masker) {
        Set<String> masked = new LinkedHashSet<>();
        node.extensions().all(InputMapping.class).stream().filter(InputMapping::mask).map(InputMapping::name)
                .forEach(masked::add);
        Map<String, Object> out = new LinkedHashMap<>();
        (inputs == null ? Map.<String, Object>of() : inputs).forEach((name, value) -> out.put(name,
                masked.contains(name) ? mask(tenantId, value, masker) : value));
        return out;
    }

    private static String mask(String tenantId, Object value, InputMasker masker) {
        if (value == null) {
            return InputMasker.REDACTED;
        }
        String plain = value instanceof String text ? text : Values.write(Map.of("v", value));
        if (!(value instanceof String)) {
            // The JSON form of the value alone, without the wrapper the writer needs for a scalar.
            plain = plain.substring("{\"v\":".length(), plain.length() - 1);
        }
        try {
            String token = masker.mask(tenantId, plain);
            return token == null || token.isBlank() || token.equals(plain) ? InputMasker.REDACTED : token;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "process.agent.mask.failed tenant={0} exception={1}", tenantId,
                    e.getClass().getName());
            return InputMasker.REDACTED;
        }
    }

    /**
     * The fields a reviewer changed: both answers masked by the same policy, so a field masked in both shows as
     * unchanged, because neither plain value is kept.
     *
     * @param refused the refused answer as the refusal recorded it, masked
     * @param reviewed the reviewer's answer, masked
     * @return per changed field, {@code from} and {@code to}; a field one side lacks has null there
     */
    static Map<String, Object> diff(Map<String, Object> refused, Map<String, Object> reviewed) {
        // Both through JSON, so 85 read from history and 85 from a request compare equal.
        Map<String, Object> before = refused == null ? Map.of() : Values.readMap(Values.write(refused));
        Map<String, Object> after = reviewed == null ? Map.of() : Values.readMap(Values.write(reviewed));
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : fields) {
            Object from = before.get(field);
            Object to = after.get(field);
            if (!Objects.equals(from, to)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", from);
                change.put("to", to);
                changes.put(field, change);
            }
        }
        return changes;
    }
}
