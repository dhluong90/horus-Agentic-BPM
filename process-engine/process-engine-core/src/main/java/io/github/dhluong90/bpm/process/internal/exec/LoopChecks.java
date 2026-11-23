package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.internal.expr.Condition;
import io.github.dhluong90.bpm.process.internal.expr.ConditionException;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MultiInstanceLoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StandardLoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;
import io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.OnFailure;
import java.util.List;
import java.util.regex.Pattern;

/** What a definition must hold for its loops to run (#496): checked at import, so a bad loop never starts. */
final class LoopChecks {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}(\\.[A-Za-z][A-Za-z0-9_]{0,63}){0,3}$");

    private LoopChecks() {
    }

    static void check(FlowNode node, List<DefinitionProblem> problems) {
        LoopCharacteristics loop = Loops.of(node);
        if (loop == null) {
            return;
        }
        if (node instanceof Transaction || node instanceof AdHocSubProcess) {
            problems.add(DefinitionProblem.of("LOOP_ON_UNSUPPORTED_ACTIVITY", "A loop or multi-instance marker is"
                    + " supported on a task, a call activity and an embedded sub-process, not on a saga block or an"
                    + " ad-hoc sub-process", node.id()));
        }
        if (node.extensions().first(CompensateHandlerRef.class).isPresent()
                || node.extensions().first(OnFailure.class).isPresent()) {
            problems.add(DefinitionProblem.of("LOOP_COMPENSATION_UNSUPPORTED", "A step that repeats cannot declare"
                    + " horus:compensate or horus:onFailure: its undo is per scope, and each repetition is a scope",
                    node.id()));
        }
        switch (loop) {
            case StandardLoopCharacteristics standard -> {
                if (standard.loopMaximum() == null || standard.loopMaximum() < 1) {
                    problems.add(DefinitionProblem.of("LOOP_UNBOUNDED", "A loop has a loopMaximum of 1 or more, so it"
                            + " always ends", node.id()));
                }
                expression(standard.loopCondition(), node, problems);
            }
            case MultiInstanceLoopCharacteristics mi -> {
                boolean collection = mi.loopDataInputRef() != null;
                boolean cardinality = mi.loopCardinality() != null;
                if (collection == cardinality) {
                    problems.add(DefinitionProblem.of("MULTI_INSTANCE_SOURCE", "A multi-instance step has either"
                            + " horus:collection or a loopCardinality", node.id()));
                }
                if (collection && !PATH.matcher(mi.loopDataInputRef()).matches()
                        || mi.loopDataInputRef() != null && mi.inputDataItem() == null) {
                    problems.add(DefinitionProblem.of("MULTI_INSTANCE_INVALID", "horus:collection names a variable and"
                            + " goes with horus:elementVariable", node.id()));
                }
                if (mi.inputDataItem() != null && (mi.inputDataItem().name() == null
                        || !NAME.matcher(mi.inputDataItem().name()).matches())) {
                    problems.add(DefinitionProblem.of("MULTI_INSTANCE_INVALID", "horus:elementVariable is an"
                            + " identifier", node.id()));
                }
                if (mi.loopDataOutputRef() != null && !NAME.matcher(mi.loopDataOutputRef()).matches()) {
                    problems.add(DefinitionProblem.of("MULTI_INSTANCE_INVALID", "horus:outputCollection is an"
                            + " identifier", node.id()));
                }
                if (mi.outputDataItem() != null && (mi.outputDataItem().name() == null
                        || !PATH.matcher(mi.outputDataItem().name()).matches())) {
                    problems.add(DefinitionProblem.of("MULTI_INSTANCE_INVALID", "horus:outputElement names a variable",
                            node.id()));
                }
                if (mi.loopDataOutputRef() != null && mi.outputDataItem() == null
                        && node.extensions().first(io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable.class)
                                .isEmpty()) {
                    problems.add(DefinitionProblem.of("MULTI_INSTANCE_INVALID", "horus:outputCollection needs"
                            + " horus:outputElement, or a horus:resultVariable on the step to take it from",
                            node.id()));
                }
                expression(mi.loopCardinality(), node, problems);
                expression(mi.completionCondition(), node, problems);
            }
        }
        if (!(node instanceof Activity)) {
            problems.add(DefinitionProblem.of("LOOP_ON_UNSUPPORTED_ACTIVITY", "Loop characteristics are on an activity",
                    node.id()));
        }
    }

    private static void expression(Expression expression, FlowNode node, List<DefinitionProblem> problems) {
        if (expression == null || expression.text() == null) {
            return;
        }
        try {
            Condition.parse(expression.text());
        } catch (ConditionException e) {
            problems.add(DefinitionProblem.of("CONDITION_INVALID", e.getMessage(), node.id()));
        }
    }
}
