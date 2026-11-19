package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code tDataAssociation}.
 */
public sealed interface DataAssociation extends BaseElement
        permits DataInputAssociation, DataOutputAssociation {

    /** @return source item ids */
    List<String> sourceRefs();

    /** @return target item id */
    String targetRef();
}
