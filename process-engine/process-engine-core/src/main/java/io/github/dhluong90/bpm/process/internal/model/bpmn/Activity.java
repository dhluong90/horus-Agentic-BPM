package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tActivity}: tasks, subprocesses and call activities.
 */
public sealed interface Activity extends FlowNode
        permits Task, ManualTask, UserTask, ServiceTask, BusinessRuleTask, ScriptTask, SendTask, ReceiveTask, SubProcess, Transaction, AdHocSubProcess, CallActivity {

    /** @return activity attributes, data associations, resources and loop characteristics */
    ActivityAttributes activity();
}
