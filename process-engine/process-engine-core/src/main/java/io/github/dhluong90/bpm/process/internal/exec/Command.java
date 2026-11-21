package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Inputs of the kernel. Each is applied under the instance row lock by the shell.
 */
public sealed interface Command permits Command.Start, Command.CompleteTask, Command.JobDue, Command.AsyncResult, Command.RouteToReview, Command.CorrelateMessage, Command.DeliverSignal, Command.VariablesChanged, Command.Cancel, Command.ChildCompleted, Command.RetryStep, Command.Compensate, Command.EndCompensation {

    /**
     * Starts the instance at its none start event.
     *
     * @param variables initial variables
     * @param actor who started it
     */
    record Start(Map<String, Object> variables, String actor) implements Command {
        public Start {
            variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
    }

    /**
     * A user task was completed. The shell has checked assignment and four-eyes (refusing with SELF_APPROVAL_FORBIDDEN before applying), and a four-eyes user task behaviour refuses an actor among the participants again.
     *
     * @param taskId task id
     * @param tokenId the waiting token
     * @param result the completion result
     * @param actor who completed it
     */
    record CompleteTask(String taskId, String tokenId, Map<String, Object> result, String actor) implements Command {
        public CompleteTask {
            Objects.requireNonNull(taskId, "taskId must not be null");
            Objects.requireNonNull(tokenId, "tokenId must not be null");
            result = result == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(result));
        }
    }

    /**
     * A timer, SLA or retry job fell due.
     *
     * @param jobId job id
     * @param tokenId the waiting token
     * @param jobKind job kind
     */
    record JobDue(String jobId, String tokenId, String jobKind) implements Command {    }

    /**
     * The result of an {@link Effect.InvokeService}, in the same transaction for SYNC_LOCAL or in TX2 for ASYNC.
     *
     * @param tokenId the waiting token
     * @param attemptId the attempt id of the invocation
     * @param result the result
     */
    record AsyncResult(String tokenId, String attemptId, StepResult result) implements Command {
        public AsyncResult {
            Objects.requireNonNull(tokenId, "tokenId must not be null");
            Objects.requireNonNull(attemptId, "attemptId must not be null");
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    /**
     * The result of an async step was refused in its result transaction (#363): the step is not completed, and a
     * person reviews it instead. The token moves from {@code WAITING_ASYNC} to {@code WAITING_USER} on the same step,
     * with a review task; completing that task completes the step with the reviewer's result.
     *
     * @param tokenId the waiting token
     * @param attemptId the attempt whose result was refused; must be the token's current attempt
     * @param assignee the reviewer: {@code role:}, {@code user:} or {@code group:} and a name
     * @param fourEyes whether the review task follows the four-eyes rule
     * @param code why the result was refused, for example {@code GUARDRAIL_VIOLATION}
     * @param reasons the guardrail's reasons
     * @param context what the review task carries for the reviewer (#52); the kernel passes it through unread
     */
    record RouteToReview(String tokenId, String attemptId, String assignee, boolean fourEyes, String code,
            java.util.List<String> reasons, java.util.Map<String, Object> context) implements Command {
        public RouteToReview {
            Objects.requireNonNull(tokenId, "tokenId must not be null");
            Objects.requireNonNull(attemptId, "attemptId must not be null");
            Objects.requireNonNull(assignee, "assignee must not be null");
            Objects.requireNonNull(code, "code must not be null");
            reasons = reasons == null ? java.util.List.of() : java.util.List.copyOf(reasons);
            context = context == null ? null : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(context));
        }

        /**
         * A route whose task carries the code and reasons only.
         *
         * @param tokenId the waiting token
         * @param attemptId the refused attempt
         * @param assignee the reviewer
         * @param fourEyes whether four eyes apply
         * @param code why the result was refused
         * @param reasons the reasons
         */
        public RouteToReview(String tokenId, String attemptId, String assignee, boolean fourEyes, String code,
                java.util.List<String> reasons) {
            this(tokenId, attemptId, assignee, fourEyes, code, reasons, null);
        }
    }

    /**
     * A message was correlated to a subscription of this instance.
     *
     * @param subscriptionId subscription id
     * @param messageName message name
     * @param payload message payload
     */
    record CorrelateMessage(String subscriptionId, String messageName, Map<String, Object> payload) implements Command {
        public CorrelateMessage {
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    /**
     * A signal was broadcast to a subscription of this instance.
     *
     * @param subscriptionId subscription id
     * @param signalName signal name
     * @param payload signal payload
     */
    record DeliverSignal(String subscriptionId, String signalName, Map<String, Object> payload) implements Command {
        public DeliverSignal {
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    /**
     * Variables were set from outside; conditional events re-evaluate.
     *
     * @param changes the changed variables
     * @param actor who set them
     */
    record VariablesChanged(Map<String, Object> changes, String actor) implements Command {
        public VariablesChanged {
            changes = changes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(changes));
        }
    }

    /**
     * Cancels the instance: every live token, task, job and subscription.
     *
     * @param reason reason
     * @param actor who cancelled
     */
    record Cancel(String reason, String actor) implements Command {    }

    /**
     * A called child instance completed.
     *
     * @param childInstanceId child instance
     * @param tokenId the waiting call activity token
     * @param output child output
     */
    record ChildCompleted(String childInstanceId, String tokenId, Map<String, Object> output) implements Command {
        public ChildCompleted {
            output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        }
    }

    /**
     * An operator retries a step that waits on an incident and has no job (#68, PRE-077): a local step whose handler
     * returned a business error. The token, waiting on the step under {@code attemptId}, enters the step again, so the
     * handler runs again in this transaction under a new attempt id.
     *
     * @param tokenId the token waiting on the step
     * @param attemptId the token's current attempt id, the one the incident was raised under
     * @param actor the operator
     */
    record RetryStep(String tokenId, String attemptId, String actor) implements Command {
        public RetryStep {
            Objects.requireNonNull(tokenId, "tokenId must not be null");
            Objects.requireNonNull(attemptId, "attemptId must not be null");
        }
    }

    /**
     * A step that declares {@code horus:onFailure="compensate"} failed for good in the shell (#67, PRE-073): its job
     * is {@code FAILED} because the failure was not retryable, the last attempt was used, or the answer was a
     * business error. The compensation of the step's scope starts instead of an incident.
     *
     * @param tokenId the token waiting on the failed step
     * @param attemptId the token's current attempt id, its job id
     * @param cause the failure's code
     */
    record Compensate(String tokenId, String attemptId, String cause) implements Command {
        public Compensate {
            Objects.requireNonNull(tokenId, "tokenId must not be null");
            Objects.requireNonNull(attemptId, "attemptId must not be null");
        }
    }

    /**
     * The shell walked a compensating scope's stack to the bottom (#67): the scope is {@code COMPENSATED} and the
     * instance {@code FAILED}. The only command a {@code COMPENSATING} instance accepts.
     *
     * @param scopeTokenId the compensated scope
     */
    record EndCompensation(String scopeTokenId) implements Command {
        public EndCompensation {
            Objects.requireNonNull(scopeTokenId, "scopeTokenId must not be null");
        }
    }
}
