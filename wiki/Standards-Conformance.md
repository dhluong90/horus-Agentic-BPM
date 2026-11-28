# Standards conformance numbers

HORUS targets full BPMN 2.0 and DMN. A target is not a claim: nothing is called conformant before its suites pass. Every day a workflow measures the engines against two public suites and publishes the result, so the numbers on this page's job summary are the only basis for any statement about conformance.

## What is measured

| Suite | Engine | What a pass means |
|---|---|---|
| DMN TCK, `compliance-level-2` and `compliance-level-3` | Rule Engine | One test case passes when the model imports, is published with a second approver, and evaluates the case inputs to the expected value of every result node |
| BPMN MIWG reference models (22 files, series A, B and C) | Process Engine | A model is read, exported and compared with the source in three separate checks |

The three MIWG checks:

- **Semantic.** Elements, attributes, references and text of the BPMN model are the same in the source and in the export. Prefixes, the order of siblings, white space, attributes left at their schema default and vendor extensions are ignored.
- **Diagram.** Every diagram shape, edge and label has the same element reference, bounds and waypoints.
- **Schema.** The export validates against the BPMN 2.0.2 schema.

A model counts as passing only when it is read and passes all three. The comparator is written in this project; no third-party comparison tool is used.

The executable scenarios of the project (`ConformanceSuiteTest`) are reported on their own line. They are files written in this repository, not MIWG models, so they never add to the MIWG numbers.

## Where to read them

Open the **Conformance** workflow in the Actions tab and choose the latest run. The job summary shows, for each suite:

- the pass rate and the count behind it (cases for the DMN TCK, models for MIWG);
- the change since the recorded baseline;
- the most common reasons cases fail, and for MIWG the first difference of each model.

The `conformance-results` artifact holds the rows behind the summary: `dmn-tck-results.json` (one row per test case), `tck_results.csv` (the same rows in the format of the DMN TCK results site) and `bpmn-miwg-results.json`. The artifact is kept 30 days. It lists case names and results only; no suite file is in it.

## Reading a number

- **Models read** is how many models the engine accepts at all. A case in a model that is refused fails with the reason `IMPORT`, and the reason names the element or rule that was refused. That is the quickest way to see which feature stands between a model and a pass.
- **A pass rate of 0 percent is a measurement**, not a missing run. Before the stage that adds an element, every case that needs it fails by design.
- A case that expects a FEEL error result, and a test case of type `decisionService`, are counted as failures with the reason `UNSUPPORTED` until the engine supports them.
- Policy exclusions (external Java and PMML functions) are listed by test id with a reason in `tools/conformance/dmn-tck-exclusions.txt`. An excluded case leaves the denominator and is shown as such. The list is empty today.

## The ratchet

The run does not fail on a low rate. It fails when a number falls **below the recorded baseline** in `tools/conformance/baseline.json`: a pass rate, the models read, or any one of the MIWG counts. The baseline only moves up. A pull request that improves a number updates the baseline file in the same change, and the reviewer reads that diff. The same check runs on pull requests that touch the Rule Engine core, the BPMN module or the harness.

## The suites, their versions and licences

Both suites are fetched at build time as test data from a pinned commit, and the SHA-256 of the archive is checked before anything is unpacked (`tools/conformance/pins.env`). They are never committed to this repository and never shipped in an artifact, an image or a jar.

| Suite | Source | Licence |
|---|---|---|
| DMN TCK | https://github.com/dmn-tck/tck | The repository carries an Apache 2.0 licence file and its README offers the test cases under a Creative Commons Share-Alike-With-Attribution licence. The stricter reading applies |
| BPMN MIWG test suite | https://github.com/bpmn-miwg/bpmn-miwg-test-suite | Creative Commons Attribution 3.0 Unported |

Test cases and models are the work of their contributors. To move a pin, change the commit and the checksum together in one pull request and refresh the baseline in it.

## Run it yourself

```bash
tools/conformance/fetch.sh all
T=$PWD/target/conformance
tools/mvn-retry.sh -B -ntp -Dcockpit.bundle.skip=true \
  -pl rule-engine/rule-engine-core,process-engine/process-engine-bpmn -am test \
  -Dtest=DmnTckConformanceRun,MiwgRoundTripRun,ConformanceSuiteTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dconformance.tck.dir="$T/suites/dmn-tck" -Dconformance.miwg.dir="$T/suites/bpmn-miwg" \
  -Dconformance.exclusions="$PWD/tools/conformance/dmn-tck-exclusions.txt" -Dconformance.out="$T"
python3 tools/conformance/ratchet.py check "$T"
```

The last command prints the same summary as the workflow and exits 1 when a number is below the baseline.
