package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess;
import java.util.Map;

/**
 * Ad-hoc sub-process (#500, BPMN 2.0.2 section 10.3.5). Entering it opens a {@code SCOPE} token and nothing runs yet:
 * its activities have no sequence flow, and the host starts them one at a time with {@code startAdHocActivity}. With
 * {@code ordering} Parallel any number may run at once; with Sequential one at a time. Each time an activity ends the
 * {@code completionCondition} is asked; when it holds, the activities still running are cancelled
 * ({@code cancelRemainingInstances}, true by default) and the token leaves by the sub-process's outgoing flow. Without a
 * completion condition the sub-process ends when nothing runs and every activity has completed once. The kernel does
 * that work in {@code complete}; this behaviour only opens the scope.
 */
final class AdHocSubProcessBehavior implements FlowNodeBehavior<AdHocSubProcess> {

    @Override
    public Class<AdHocSubProcess> nodeType() {
        return AdHocSubProcess.class;
    }

    @Override
    public void enter(AdHocSubProcess node, Token token, ExecutionContext ctx) {
        Token scope = ctx.createToken(TokenKind.SCOPE, node.id(), token.scopeTokenId());
        ctx.update(token.withStatus(TokenStatus.COMPLETED));
        ctx.history("SCOPE_ENTERED", token, Map.of("scopeTokenId", scope.id(), "adHoc", true));
    }
}
