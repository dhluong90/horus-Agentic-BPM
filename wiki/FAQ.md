# FAQ

**Why not Drools, Camunda or Flowable?** They are excellent and far larger. HORUS Agentic BPM is for teams that need decision tables and approval flows inside their own service with a licence and footprint they fully control, and that want AI agents as process participants with deterministic guardrails.

**Is it a server?** No. Two Spring Boot starters and a React cockpit. State lives in your PostgreSQL.

**Can business users change rules without a deployment?** Yes: draft in the cockpit, simulate, request publish, a second person approves, the version becomes effective on its date.

**Is evaluation deterministic?** Yes. Same version, inputs and clock give the same outputs, and the log records all three.

**Does it support DMN or BPMN files?** Not in 0.1. Import of DMN decision tables and a BPMN subset is on the roadmap if demand is there.

**Which databases?** PostgreSQL 14+ in 0.1. Others may follow; the storage layer is isolated behind a small SPI.

**Can I run the engines without Spring?** `*-core` artifacts have no Spring dependency; the starters are thin. Wiring without Spring is possible but not documented yet.

**Can I use it commercially?** Not under the public licence. The PolyForm Noncommercial License 1.0.0 covers personal, research, educational, evaluation and non-profit use. Any commercial use, including internal business use and embedding in products, needs a commercial licence from HORUS; see `COMMERCIAL-LICENSE.md` in the repository.

**Is it open source?** It is source-available. A noncommercial licence does not meet the Open Source Definition, so we do not call it open source. The code, the design and the issue tracker are public, and contributions are welcome under the CLA.
