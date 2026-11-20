package io.github.dhluong90.bpm.process.internal.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The check of a completed async result inside its result transaction (#363, DESIGN section 4.1). It runs in
 * {@link ProcessServices#recordAttempt}, so a result reported by the engine's own executor and a result reported
 * through the completion API pass the same check, and nothing else can accept a result. One guard per job kind;
 * a kind without one (remote service steps) accepts every completed result, as before.
 *
 * <p>A guard runs while the instance row is locked: it may only evaluate in-process (the output schema validator and
 * the Rule Engine through the {@link DecisionPort}), never call the network (ADR-0008). Any exception it throws is
 * treated as a refusal by the caller: the check fails closed.
 */
@FunctionalInterface
interface ResultGuard {

    /** Accepts every result: the guard of a kind with no checks. */
    ResultGuard NONE = (request, budget) -> new Verdict.Allow(null, List.of());

    /**
     * @param request the result and where it is for
     * @param budget the time the check may take, measured on the engine clock
     * @return the verdict
     */
    Verdict check(Request request, java.time.Duration budget);

    /** What the history keeps of a refused output when a guard has no masking of its own: no values at all. */
    String MASKED = "***";

    /**
     * The refused output as the immutable {@code ASYNC_RESULT_REFUSED} history may keep it (DESIGN section 4.1): the
     * guard's masking policy applied and its size bounded. The default keeps the field names and masks every value.
     *
     * @param payload the job payload, with the step's guard spec
     * @param output the refused output
     * @return the masked output; never the raw one
     */
    default Map<String, Object> auditOutput(Map<String, Object> payload, Map<String, Object> output) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        (output == null ? Map.<String, Object>of() : output).keySet().stream().limit(AUDIT_MAX_FIELDS)
                .forEach(k -> out.put(k, MASKED));
        return out;
    }

    /** Most top-level fields of a refused output kept in its history entry. */
    int AUDIT_MAX_FIELDS = 50;

    /**
     * One completed result to check.
     *
     * @param tenantId the instance's tenant, read from the locked instance row
     * @param instanceId the instance
     * @param nodeId the step
     * @param jobKind the job kind
     * @param payload the job payload written when the step parked, with its {@code guard} spec
     * @param output the result
     * @param caller who a guardrail decision runs for: always engine work, built by {@link ProcessServices} from the
     *     locked row (#336), never the completion API's caller
     */
    record Request(String tenantId, String instanceId, String nodeId, String jobKind, Map<String, Object> payload,
            Map<String, Object> output, DecisionCaller caller) {

        public Request {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(instanceId, "instanceId must not be null");
            Objects.requireNonNull(caller, "caller must not be null");
            payload = payload == null ? Map.of() : payload;
            output = output == null ? Map.of() : output;
        }
    }

    /** Where a refused step goes. */
    enum Route {
        /** A person reviews the result in a task and completes the step. */
        REVIEW,
        /** The job fails; an incident follows once incidents exist (#68). */
        FAIL
    }

    /** What the guard decided. */
    sealed interface Verdict {

        /**
         * The result may advance the step.
         *
         * @param decisionId the guardrail decision, or null when the kind has no guardrail
         * @param ruleIds the matched guardrail rules
         */
        record Allow(String decisionId, List<String> ruleIds) implements Verdict {
            public Allow {
                ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
            }
        }

        /**
         * The result breaks its output schema. While the runner's {@code schema-retries} are not used up, the attempt
         * is given back for a fresh one; after that the result is refused as {@code exhausted} (DESIGN section 4.1
         * step 3).
         *
         * @param code for example {@code AGENT_SCHEMA_INVALID}
         * @param violations what was wrong, paths and rules only, never values
         * @param exhausted the refusal that applies once no schema retry is left: same code, the violations as
         *     reasons, and the step's review-or-fail route
         */
        record Invalid(String code, List<String> violations, Refused exhausted) implements Verdict {
            public Invalid {
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(exhausted, "exhausted must not be null");
                violations = violations == null ? List.of() : List.copyOf(violations);
            }
        }

        /**
         * The result is refused: it never advances the step. The step goes to review or fails.
         *
         * @param code {@code GUARDRAIL_VIOLATION}, or a fail-closed code such as {@code GUARDRAIL_EVALUATION_ERROR}
         * @param reasons the guardrail's reasons, or the cause of a fail-closed refusal
         * @param ruleIds matched guardrail rules
         * @param decisionId the guardrail decision, or null when none was logged
         * @param route where the step goes
         * @param reviewer the assignee of the review task ({@code role:}, {@code user:} or {@code group:}); null unless
         *     the route is {@link Route#REVIEW}. A review task always follows the four-eyes rule: nothing in a job
         *     payload can switch it off
         */
        record Refused(String code, List<String> reasons, List<String> ruleIds, String decisionId, Route route,
                String reviewer) implements Verdict {
            public Refused {
                Objects.requireNonNull(code, "code must not be null");
                Objects.requireNonNull(route, "route must not be null");
                reasons = reasons == null ? List.of() : List.copyOf(reasons);
                ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
                if (route == Route.REVIEW && reviewer == null) {
                    throw new IllegalArgumentException("A review needs a reviewer");
                }
            }
        }
    }
}
