package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.util.Map;

/** User task: a wait state; the token waits until the shell delivers {@link Command.CompleteTask}. */
final class UserTaskBehavior implements FlowNodeBehavior<UserTask> {

    @Override
    public Class<UserTask> nodeType() {
        return UserTask.class;
    }

    @Override
    public void enter(UserTask node, Token token, ExecutionContext ctx) {
        TaskAssignment assignment = node.extensions().first(TaskAssignment.class)
                .orElseThrow(() -> new NotExecutableException(node));
        String taskId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_USER, token.scopeTokenId(), token.nodeId(),
                taskId, token.locals()));
        ctx.effect(new Effect.CreateUserTask(taskId, token.id(), node.id(), node.name(), assignment.assignee(),
                assignment.fourEyes(), Map.of()));
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.task.created.v1", Map.of("taskId", taskId, "nodeId", node.id(),
                "assignee", assignment.assignee())));
    }

    @Override
    public void trigger(UserTask node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.CompleteTask complete)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a task completion");
        }
        if (!complete.taskId().equals(token.activityInstanceId())) {
            throw new ExecutionException("TASK_MISMATCH", node.id(), "Task " + complete.taskId()
                    + " is not the open task of this token");
        }
        StepSupport.storeResult(node, complete.result(), ctx);
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.history("TASK_COMPLETED", token, Map.of("taskId", complete.taskId()));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.task.completed.v1", Map.of("taskId", complete.taskId(),
                "nodeId", node.id(), "completedBy", String.valueOf(complete.actor()))));
        ctx.takeOutgoing(active, node);
    }
}
