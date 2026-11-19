package io.github.dhluong90.bpm.process.api;

/** Format of a definition document as received; stored with the source bytes, never executed. */
public enum SourceFormat {
    /** BPMN 2.0 XML, the supported subset (process engine DESIGN section 5.5). */
    BPMN_XML,
    /** The canonical JSON profile, {@code process-definition.v1}. */
    JSON,
    /** YAML form of the canonical profile. */
    YAML
}
