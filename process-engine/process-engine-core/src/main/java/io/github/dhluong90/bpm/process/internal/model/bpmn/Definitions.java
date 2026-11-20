package io.github.dhluong90.bpm.process.internal.model.bpmn;

import io.github.dhluong90.bpm.process.internal.model.di.BpmnDiagram;
import java.util.List;

/**
 * BPMN {@code definitions}: the document root.
 *
 * @param id id
 * @param name name
 * @param targetNamespace targetNamespace
 * @param expressionLanguage expressionLanguage
 * @param typeLanguage typeLanguage
 * @param exporter exporter
 * @param exporterVersion exporterVersion
 * @param imports imports
 * @param rootElements rootElements
 * @param diagrams diagrams
 * @param info info
 */
public record Definitions(
        String id,
        String name,
        String targetNamespace,
        String expressionLanguage,
        String typeLanguage,
        String exporter,
        String exporterVersion,
        List<Import> imports,
        List<RootElement> rootElements,
        List<BpmnDiagram> diagrams,
        ElementInfo info) implements BaseElement {

    public Definitions {
        imports = imports == null ? List.of() : List.copyOf(imports);
        rootElements = rootElements == null ? List.of() : List.copyOf(rootElements);
        diagrams = diagrams == null ? List.of() : List.copyOf(diagrams);
        info = info == null ? ElementInfo.empty() : info;
    }

    /** @return the processes among the root elements, in document order */
    public List<Process> processes() {
        return rootElements.stream().filter(Process.class::isInstance).map(Process.class::cast).toList();
    }
}
