package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.CompileStep;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.expr.Condition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;

/** Parses every sequence-flow condition at activation, so a syntax error fails the import, not an instance. */
final class ConditionCompileStep implements CompileStep {

    @Override
    public void compile(Process process, CompiledProcess.Builder builder) {
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof SequenceFlow flow && flow.conditionExpression() != null
                    && flow.conditionExpression().text() != null) {
                Condition.parse(flow.conditionExpression().text(), flow.conditionExpression().language());
            }
        }
    }
}
