package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;

/** Precomputes facets of a process once per definition version, at activation. */
public interface CompileStep {

    /**
     * @param process the process
     * @param builder the builder to add facets to
     */
    void compile(Process process, CompiledProcess.Builder builder);
}
