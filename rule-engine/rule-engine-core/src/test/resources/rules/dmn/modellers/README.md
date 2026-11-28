Modeller corpus for RLE-078. Each `.dmn` is one decision table in the style a widely used DMN tool writes
(KIE/Drools DMN 1.2, Trisotech DMN 1.4, Camunda Modeler 5 DMN 1.3), with vendor attributes, extension elements,
information requirements and a diagram. The `.expected.json` next to it lists inputs, matched rule ids and outputs.
The expected values are worked by hand from the DMN 1.5 decision table rules (the tools themselves are not run
in CI); replace them with a tool's own evaluation when one is at hand.
