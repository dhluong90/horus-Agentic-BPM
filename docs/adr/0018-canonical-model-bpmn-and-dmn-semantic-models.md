# ADR-0018: The BPMN 2.0 and DMN semantic models are canonical; our JSON is their serialisation

- Status: proposed (Decision needed by the product owner: it replaces the notation part of PD-034)
- Date: 2026-11-18
- Deciders: maintainers; product owner for acceptance
- Module: process-engine, rule-engine

## Context
ADR-0017 commits the engines to full BPMN 2.0 and DMN. Today our JSON documents (`process-definition.v1`, `rule-set.v1`) define the model, and a BPMN subset is mapped onto it (ADR-0013). Full support means the model must hold every BPMN element and event definition, collaborations, and every DMN DRG element and boxed expression, and must round-trip with other tools without loss. The hash, diff and maker-checker rules (ADR-0012) must keep working, and documents in Git must keep their hash.

## Decision
The in-memory model in `*-core` is the BPMN 2.0.2 and DMN 1.5 semantic model (sealed records per metamodel class, plus HORUS extension records for `agentTask`, guardrails, async mode, forms and SLA, plus opaque foreign extensions). Canonical JSON is its stored, hashed, diffed and approved serialisation; BPMN XML and DMN XML are first-class serialisations read and written losslessly; v1 documents are a compact profile mapped onto the model and import forever. The original source document is stored byte for byte. Hash rules: the hash is over the canonical JSON of the schema version the document is stored in, without layout and foreign extensions; it is never recomputed on a schema upgrade; an XML import produces the lowest schema version that can express the model. Details: [`standards-compliance.md`](../architecture/standards-compliance.md) section 3.

## Options considered
1. BPMN and DMN semantic models canonical, our JSON as serialisation (recommended). One model in the standard's shape; mechanical readers and writers; MIWG round trip is serialise and compare; HORUS step types are extensions. About 3 days more in M1 and 5 days in M2.
2. Our own model canonical, lossless round trip with BPMN and DMN. No change now; but the model grows toward the BPMN metamodel anyway, with a two-way mapping per element that has to be designed and tested for every feature for the life of the product.
3. BPMN or DMN XML text canonical, executed from the XML. Round trip trivial; hash over XML is unstable without XML canonicalisation; JSON authoring and Git workflows need a full mapping; rewrite of the M2 definition layer.

## Consequences
- The M1 rule set model is shaped as DMN `definitions`, `decision` and `decisionTable`; the M2 process model as flow nodes and sequence flows with ids; the v1 reader generates flow ids deterministically.
- The M3 BPMN subset reader (PE-F-41) is built on this model, so S1 extends it instead of replacing it.
- `pre_definition` and `rle_version` store `source_format`, `source_document` and `source_hash` (applied now, `TR-STD-01`).
- PD-034's choice "own JSON canonical" changes to "BPMN and DMN models canonical, our JSON their canonical serialisation"; the product benefit of PD-034 (one notation to validate, our step types native) is kept through extensions.
- If rejected: option 2 applies, and every stage in `standards-compliance.md` section 11 carries a mapping work item for each new element.
