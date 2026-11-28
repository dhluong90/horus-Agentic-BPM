Modeller corpus for RLE-078. Each `.dmn` is one decision table in the style a widely used DMN modeller writes,
with extension attributes of its own namespace, extension elements, information requirements and a diagram:
`modeller-a` (DMN 1.2, `UNIQUE`, ids that start with an underscore), `modeller-b` (DMN 1.4, `COLLECT`, ids that start
with an underscore) and `modeller-c` (DMN 1.3, `FIRST`, date and boolean inputs, two outputs). The `.expected.json`
next to each lists inputs, matched rule ids and outputs. Rule ids that start with an underscore are mapped on import
(RLE-224), so the expected ids are the mapped ones. The expected values are worked by hand from the DMN 1.5 decision
table rules; replace them with a modeller's own evaluation when one is at hand.
