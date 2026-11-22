package io.github.dhluong90.bpm.process.internal.exec;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outputs of the kernel, applied by the shell. The kernel never performs them itself.
 */
public sealed interface Effect permits Effect.ScheduleJob, Effect.CancelJob, Effect.CreateSubscription, Effect.DeleteSubscription, Effect.CreateUserTask, Effect.CancelUserTask, Effect.InvokeService, Effect.ThrowMessage, Effect.StartChildInstance, Effect.PushCompensation, Effect.StartCompensation, Effect.RaiseIncident, Effect.RecordResult, Effect.EmitEvent {

    /**
     * Insert a job in {@code pre_timer}.
     *
     * @param jobId job id
     * @param jobKind job kind, for example TIMER
     * @param tokenId token
     * @param nodeId node
     * @param dueAt due time
     * @param payload payload
     */
    record ScheduleJob(String jobId, String jobKind, String tokenId, String nodeId, Instant dueAt, Map<String, Object> payload) implements Effect {
        public ScheduleJob {
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    /**
     * Cancel an open job.
     *
     * @param jobId job id
     */
    record CancelJob(String jobId) implements Effect {    }

    /**
     * Insert a message, signal or conditional subscription.
     *
     * @param subscriptionId id
     * @param kind MESSAGE, SIGNAL or CONDITIONAL
     * @param name event name
     * @param correlationKey correlation key or null
     * @param tokenId token
     * @param nodeId node
     */
    record CreateSubscription(String subscriptionId, String kind, String name, String correlationKey, String tokenId, String nodeId) implements Effect {    }

    /**
     * Delete a subscription.
     *
     * @param subscriptionId id
     */
    record DeleteSubscription(String subscriptionId) implements Effect {    }

    /**
     * Insert a user task.
     *
     * @param taskId task id
     * @param tokenId token
     * @param nodeId user task id
     * @param name name or null
     * @param assignee user:, role: or group: assignee
     * @param fourEyes four-eyes rule
     * @param context masked context for the task
     * @param sla the step's {@code horus:sla} and {@code horus:calendar}, or null for a task without a due time; the
     *     shell turns it into the task's due time once, with the engine clock (PRE-008)
     * @param escalation the step's {@code horus:escalation}, or null; the shell turns it into the fire times of its
     *     levels once, when the task is created (PRE-178)
     */
    record CreateUserTask(String taskId, String tokenId, String nodeId, String name, String assignee, boolean fourEyes,
            Map<String, Object> context, io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec sla,
            io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec escalation) implements Effect {
        public CreateUserTask {
            context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        }

        /** A task without a due time. */
        public CreateUserTask(String taskId, String tokenId, String nodeId, String name, String assignee,
                boolean fourEyes, Map<String, Object> context) {
            this(taskId, tokenId, nodeId, name, assignee, fourEyes, context, null, null);
        }

        /** A task with a due time and no escalation. */
        public CreateUserTask(String taskId, String tokenId, String nodeId, String name, String assignee,
                boolean fourEyes, Map<String, Object> context,
                io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec sla) {
            this(taskId, tokenId, nodeId, name, assignee, fourEyes, context, sla, null);
        }
    }

    /**
     * Cancel an open user task.
     *
     * @param taskId task id
     */
    record CancelUserTask(String taskId) implements Effect {    }

    /**
     * Call a handler, a decision or an agent; the result returns as {@link Command.AsyncResult}.
     *
     * @param tokenId token
     * @param nodeId node
     * @param attemptId attempt id, the idempotency key
     * @param kind what to call
     * @param target handler name, rule set key or agent name
     * @param inputs bounded inputs
     * @param mode in or outside the transaction
     * @param timeout the step timeout of an {@link ExecutionMode#ASYNC} call, or null for the engine default; always
     *     null for {@link ExecutionMode#SYNC_LOCAL}
     */
    record InvokeService(String tokenId, String nodeId, String attemptId, ServiceKind kind, String target, Map<String, Object> inputs, ExecutionMode mode, java.time.Duration timeout) implements Effect {
        public InvokeService {
            inputs = inputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }

        /**
         * A call with no step timeout of its own.
         *
         * @param tokenId token
         * @param nodeId node
         * @param attemptId attempt id
         * @param kind what to call
         * @param target target
         * @param inputs inputs
         * @param mode mode
         */
        public InvokeService(String tokenId, String nodeId, String attemptId, ServiceKind kind, String target,
                Map<String, Object> inputs, ExecutionMode mode) {
            this(tokenId, nodeId, attemptId, kind, target, inputs, mode, null);
        }
    }

    /**
     * Hand a message to the host's MessageAdapter through the outbox.
     *
     * @param messageName message
     * @param correlationKey key or null
     * @param payload payload
     */
    record ThrowMessage(String messageName, String correlationKey, Map<String, Object> payload) implements Effect {
        public ThrowMessage {
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    /**
     * Start a called process (#92): the child runs in the parent's tenant on the pinned version, or on the version
     * effective when the shell applies this effect.
     *
     * @param calledElement definition key
     * @param tokenId call activity token
     * @param variables input variables, by the child's variable name; nothing else is passed
     * @param version the pinned version ({@code horus:calledVersion}), or null for the effective one
     * @param attemptId the token's call attempt, the id the child's end is matched to
     */
    record StartChildInstance(String calledElement, String tokenId, Map<String, Object> variables, Integer version,
            String attemptId) implements Effect {
        public StartChildInstance {
            variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }

        /**
         * A start on the effective version with no attempt id.
         *
         * @param calledElement definition key
         * @param tokenId call activity token
         * @param variables input variables
         */
        public StartChildInstance(String calledElement, String tokenId, Map<String, Object> variables) {
            this(calledElement, tokenId, variables, null, null);
        }
    }

    /**
     * Push a compensation entry for a completed activity.
     *
     * @param scopeTokenId scope token
     * @param nodeId activity
     * @param attemptId forward attempt
     * @param handler handler or manual
     * @param inputSnapshot inputs at completion
     */
    record PushCompensation(String scopeTokenId, String nodeId, String attemptId, String handler, Map<String, Object> inputSnapshot) implements Effect {
        public PushCompensation {
            inputSnapshot = inputSnapshot == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSnapshot));
        }
    }

    /**
     * A compensation of a scope started (#67): the shell walks the scope's stack newest first, one undo at a time,
     * and ends the instance with {@link Command.EndCompensation} once the stack is walked to the bottom.
     *
     * @param scopeTokenId the scope whose completed steps are undone
     * @param nodeId the step that failed, or the {@code throwCompensation}
     * @param cause the code that started it
     */
    record StartCompensation(String scopeTokenId, String nodeId, String cause) implements Effect {
        public StartCompensation {
            Objects.requireNonNull(scopeTokenId, "scopeTokenId must not be null");
        }
    }

    /**
     * Open an incident; the token stays where it is.
     *
     * @param tokenId token
     * @param nodeId node
     * @param errorCode code
     * @param detail detail
     */
    record RaiseIncident(String tokenId, String nodeId, String errorCode, String detail) implements Effect {    }

    /**
     * A step recorded its result: an accepted answer, a local step's output, or a reviewer's answer that replaced a
     * refused one. The shell keeps it for the steps that later steps read as memory (#101); a refused answer never
     * produces this effect.
     *
     * @param tokenId token
     * @param nodeId the step
     * @param activityInstanceId the attempt id of the accepted answer, or the review task id
     * @param output the result as the step stored it
     * @param reviewed whether a reviewer's answer replaced a refused one
     */
    record RecordResult(String tokenId, String nodeId, String activityInstanceId, Map<String, Object> output,
            boolean reviewed) implements Effect {
        public RecordResult {
            output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        }
    }

    /**
     * Hand an engine event to the host publisher in the transaction.
     *
     * @param eventId stable id derived like other kernel ids (instance id and sequence); consumers deduplicate on it
     * @param type for example processes.instance.started.v1
     * @param payload payload; never instance variables
     */
    record EmitEvent(String eventId, String type, Map<String, Object> payload) implements Effect {
        public EmitEvent {
            Objects.requireNonNull(eventId, "eventId must not be null");
            payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
