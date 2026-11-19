package io.github.dhluong90.bpm.process.bpmn.xml;

/** Namespace URIs of BPMN 2.0.2, its diagram interchange and the HORUS extension. */
public final class BpmnNamespaces {

    /** BPMN model. */
    public static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    /** BPMN diagram interchange. */
    public static final String BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";
    /** Diagram common. */
    public static final String DC = "http://www.omg.org/spec/DD/20100524/DC";
    /** Diagram interchange. */
    public static final String DI = "http://www.omg.org/spec/DD/20100524/DI";
    /** XML Schema instance. */
    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    /** HORUS extension attributes and elements. */
    public static final String HORUS = "urn:horus-bpm:bpmn:extensions:v1";
    /** The HORUS expression language id. */
    public static final String EXPR_V1 = "urn:horus-bpm:expr:v1";
    /** The FEEL expression language id. */
    public static final String FEEL = "https://www.omg.org/spec/DMN/20191111/FEEL/";

    private BpmnNamespaces() {
    }
}
