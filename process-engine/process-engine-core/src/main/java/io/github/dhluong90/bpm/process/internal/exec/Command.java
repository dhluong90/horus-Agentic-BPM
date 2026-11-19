package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Inputs of the kernel. Each is applied under the instance row lock by the shell.
 */
public sealed interface Command permits Command.Start, Command.CompleteTask, Command.JobDue, Command.AsyncResult, Command.CorrelateMessage, Command.DeliverSignal, Command.VariablesChanged, Command.Cancel, Command.ChildCompleted {

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
}
