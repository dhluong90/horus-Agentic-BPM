package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.CompileStep;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.expr.Script;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ScriptTask;

/** Parses every script at activation, so a bad script fails the import, not an instance. */
final class ScriptCompileStep implements CompileStep {

    @Override
    public void compile(Process process, CompiledProcess.Builder builder) {
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof ScriptTask task && task.script() != null) {
                Script.parse(task.script(), task.scriptFormat());
            }
        }
    }
}
