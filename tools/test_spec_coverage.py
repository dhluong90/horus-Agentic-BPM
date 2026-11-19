#!/usr/bin/env python3
"""Tests of the spec-to-test gate, tools/spec-coverage.py (TR-QA-01).

Each test is named by the requirement it proves, as the gate itself requires.
Run: python3 tools/test_spec_coverage.py   (or -v for each name)
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("spec_coverage", Path(__file__).with_name("spec-coverage.py"))
gate = importlib.util.module_from_spec(_SPEC)
sys.modules["spec_coverage"] = gate
_SPEC.loader.exec_module(gate)

SPEC_ONE = "# Spec\n\n### RLE-001 First\n- **Milestone:** M1\n"


class Repo:
    """A throwaway repository with one or more modules."""

    def __init__(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def write(self, relative: str, text: str) -> "Repo":
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(textwrap.dedent(text), encoding="utf-8")
        return self

    def check(self, config: str = "") -> "gate.Result":
        path = None
        if config:
            self.write("tools/spec-coverage.ini", config)
            path = self.root / "tools" / "spec-coverage.ini"
        return gate.check(self.root, path)

    def close(self) -> None:
        self._tmp.cleanup()


class SpecCoverageGateTest(unittest.TestCase):

    def setUp(self) -> None:
        self.repo = Repo()

    def tearDown(self) -> None:
        self.repo.close()

    # helpers ---------------------------------------------------------------

    def java(self, body: str, module: str = "m", spec: str = SPEC_ONE) -> "gate.Result":
        self.repo.write(f"{module}/docs/SPEC.md", spec)
        self.repo.write(f"{module}/src/test/java/T.java", "class T {\n" + textwrap.dedent(body) + "\n}\n")
        return self.repo.check()

    def ts(self, body: str, file: str = "m/web/a.test.ts", spec: str = SPEC_ONE) -> "gate.Result":
        self.repo.write("m/docs/SPEC.md", spec)
        self.repo.write(file, body)
        return self.repo.check()

    def assertCovered(self, result, spec_id="RLE-001"):
        self.assertTrue(result.passed, result.errors)

    def assertNotCovered(self, result, spec_id="RLE-001"):
        self.assertFalse(result.passed)
        self.assertTrue(any(f"{spec_id} has no running test" in e for e in result.errors), result.errors)

    # basic behaviour ---------------------------------------------------------

    def TR_QA_01_uncovered_statement_fails_and_names_the_id(self):
        result = self.java("@Test void RLE_002_other() {}")
        self.assertNotCovered(result)

    def TR_QA_01_test_named_by_the_id_covers_it(self):
        self.assertCovered(self.java("@Test void RLE_001_first_works() {}"))

    def TR_QA_01_longer_number_does_not_cover_a_shorter_id(self):
        spec = "### RLE-010 Tenth\n- **Milestone:** M1\n"
        result = self.java("@Test void RLE_0100_not_rle_010() {}", spec=spec)
        self.assertNotCovered(result, "RLE-010")
        self.assertIn("RLE_0100_not_rle_010", result.test_names["m"])

    def TR_QA_01_pending_statement_passes(self):
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1  # later\n")
        self.assertCovered(self.java("@Test void unrelated() {}"))

    def TR_QA_01_pending_statement_with_a_test_warns(self):
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1\n")
        result = self.java("@Test void RLE_001_now_tested() {}")
        self.assertTrue(result.passed)
        self.assertTrue(any("RLE-001 is on the allow-list but has a test" in w for w in result.warnings))

    def TR_QA_01_pending_milestone_drift_fails(self):
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M3\n")
        result = self.java("")
        self.assertTrue(any("listed for M3 but" in e for e in result.errors), result.errors)

    def TR_QA_01_unknown_pending_id_fails(self):
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1\nRLE-099 M1\n")
        result = self.java("")
        self.assertTrue(any("RLE-099 is not a statement" in e for e in result.errors), result.errors)

    def TR_QA_01_malformed_pending_line_fails(self):
        self.repo.write("m/docs/spec-pending.txt", "RLE-001\n")
        result = self.java("@Test void RLE_001_x() {}")
        self.assertTrue(any("cannot read 'RLE-001'" in e for e in result.errors), result.errors)

    def TR_QA_01_duplicate_pending_line_fails(self):
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1\nRLE-001 M1\n")
        result = self.java("")
        self.assertTrue(any("RLE-001 is listed twice" in e for e in result.errors), result.errors)

    def TR_QA_01_uncovered_requirement_row_fails(self):
        self.repo.write("m/docs/TEST-PLAN.md", """\
            ## Requirement-level tests
            | Requirement | Tests |
            |---|---|
            | `TR-EMB-01` | none yet |
            ## Known gaps
            | `TR-ZZZ-99` | not a requirement row |
            """)
        result = self.java("@Test void RLE_001_x() {}")
        self.assertNotCovered(result, "TR-EMB-01")
        self.assertFalse(any("TR-ZZZ-99" in e for e in result.errors))

    def TR_QA_01_no_spec_found_fails(self):
        result = self.repo.check()
        self.assertTrue(any("no */docs/SPEC.md" in e for e in result.errors))

    # B1: only running tests count -------------------------------------------

    def TR_QA_01_line_comment_does_not_cover(self):
        self.assertNotCovered(self.java("// @Test void RLE_001_commented_out() {}"))

    def TR_QA_01_block_comment_does_not_cover(self):
        self.assertNotCovered(self.java("/*\n @Test void RLE_001_block_commented() {}\n*/"))

    def TR_QA_01_string_literal_does_not_cover(self):
        self.assertNotCovered(self.java('String s = "@Test void RLE_001_in_a_string() {";'))

    def TR_QA_01_text_block_does_not_cover(self):
        self.assertNotCovered(self.java('String s = """\n  @Test void RLE_001_in_a_text_block() {}\n  """;'))

    def TR_QA_01_disabled_test_does_not_cover(self):
        self.assertNotCovered(self.java('@Disabled("broken") @Test void RLE_001_disabled() {}'))

    def TR_QA_01_conditionally_disabled_test_does_not_cover(self):
        self.assertNotCovered(self.java("@Test @DisabledOnOs(OS.WINDOWS) void RLE_001_disabled_on_os() {}"))

    def TR_QA_01_test_in_disabled_class_does_not_cover(self):
        self.assertNotCovered(self.java("@Disabled @Nested class Inner {\n  @Test void RLE_001_in_disabled_class() {}\n}"))

    def TR_QA_01_helper_without_test_annotation_does_not_cover(self):
        self.assertNotCovered(self.java("private void RLE_001_helper_not_a_test() {}"))

    def TR_QA_01_call_of_a_helper_does_not_cover(self):
        self.assertNotCovered(self.java("@Test void unrelated() { RLE_001_helper(); }"))

    def TR_QA_01_ts_skip_does_not_cover(self):
        self.assertNotCovered(self.ts("it.skip('RLE_001_skipped', () => {})\n"))

    def TR_QA_01_ts_fixme_does_not_cover(self):
        self.assertNotCovered(self.ts("test.fixme('RLE_001_fixme', async () => {})\n"))

    def TR_QA_01_ts_todo_does_not_cover(self):
        self.assertNotCovered(self.ts("it.todo('RLE_001_todo')\n"))

    def TR_QA_01_ts_x_prefixed_does_not_cover(self):
        self.assertNotCovered(self.ts("xit('RLE_001_x', () => {})\nxdescribe('RLE_001_y', () => { it('a', () => {}) })\n"))

    def TR_QA_01_ts_empty_describe_does_not_cover(self):
        self.assertNotCovered(self.ts("describe('RLE_001_empty_suite', () => {})\n"))

    def TR_QA_01_ts_describe_with_only_skipped_tests_does_not_cover(self):
        self.assertNotCovered(self.ts("describe('RLE_001_suite', () => { it.skip('a', () => {}) })\n"))

    def TR_QA_01_ts_test_inside_skipped_describe_does_not_cover(self):
        self.assertNotCovered(self.ts("describe.skip('suite', () => { it('RLE_001_inner', () => {}) })\n"))

    def TR_QA_01_ts_commented_test_does_not_cover(self):
        self.assertNotCovered(self.ts("// it('RLE_001_commented', () => {})\n/* test('RLE_001_block', () => {}) */\n"))

    def TR_QA_01_ts_title_in_a_string_does_not_cover(self):
        self.assertNotCovered(self.ts("const s = \"it('RLE_001_in_string', () => {})\"\n"))

    # S1: current milestone --------------------------------------------------

    def TR_QA_01_pending_entry_at_current_milestone_fails(self):
        self.repo.write("m/docs/SPEC.md", SPEC_ONE)
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1\n")
        result = self.repo.check("[gate]\ncurrent-milestone = M1\n")
        self.assertTrue(any("pending for M1, but the current milestone is M1" in e for e in result.errors), result.errors)

    def TR_QA_01_pending_entry_after_current_milestone_passes(self):
        self.repo.write("m/docs/SPEC.md", SPEC_ONE)
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1\n")
        self.assertTrue(self.repo.check("[gate]\ncurrent-milestone = M0\n").passed)

    def TR_QA_01_malformed_current_milestone_fails(self):
        self.repo.write("m/docs/SPEC.md", SPEC_ONE)
        self.repo.write("m/docs/spec-pending.txt", "RLE-001 M1\n")
        result = self.repo.check("[gate]\ncurrent-milestone = one\n")
        self.assertTrue(any("is not M<number>" in e for e in result.errors))

    # S2: statement headings ---------------------------------------------------

    def TR_QA_01_two_and_four_hash_headings_are_statements(self):
        spec = "## RLE-016 Two\n- **Milestone:** M1\n\n#### RLE-017 Four\n- **Milestone:** M1\n"
        result = self.java("", spec=spec)
        self.assertNotCovered(result, "RLE-016")
        self.assertNotCovered(result, "RLE-017")

    def TR_QA_01_four_digit_id_is_a_statement(self):
        spec = "### RLE-1000 Four digits\n- **Milestone:** M1\n"
        self.assertNotCovered(self.java("@Test void RLE_100_x() {}", spec=spec), "RLE-1000")
        self.repo.write("m/src/test/java/U.java", "class U { @Test void RLE_1000_x() {} }")
        self.assertTrue(self.repo.check().passed)

    def TR_QA_01_duplicate_statement_heading_fails(self):
        spec = SPEC_ONE + "\n### RLE-001 Again\n- **Milestone:** M9\n"
        result = self.java("@Test void RLE_001_x() {}", spec=spec)
        self.assertTrue(any("RLE-001 is defined twice" in e for e in result.errors), result.errors)

    def TR_QA_01_statement_without_milestone_fails(self):
        result = self.java("@Test void RLE_001_x() {}", spec="### RLE-001 No milestone\n- **Given:** x\n")
        self.assertTrue(any("RLE-001 has no '- **Milestone:** M<n>' line" in e for e in result.errors), result.errors)

    def TR_QA_01_malformed_id_heading_fails(self):
        result = self.java("", spec=SPEC_ONE + "\n### RLE-01 Two digits\n- **Milestone:** M1\n")
        self.assertTrue(any("starts like a statement id" in e for e in result.errors), result.errors)

    def TR_QA_01_ids_in_fenced_code_are_ignored(self):
        spec = SPEC_ONE + "\n```\n### RLE-002 example only\n```\n"
        result = self.java("@Test void RLE_001_x() {}", spec=spec)
        self.assertTrue(result.passed, result.errors)

    def TR_QA_01_test_plan_table_must_match_spec(self):
        self.repo.write("m/docs/TEST-PLAN.md", "## Specification coverage\n| Spec id | Test |\n|---|---|\n| RLE-002 | x |\n")
        result = self.java("@Test void RLE_001_x() {}")
        self.assertTrue(any("missing from the table: RLE-001; not in SPEC.md: RLE-002" in e for e in result.errors),
                        result.errors)

    # S3: per-module test roots -----------------------------------------------

    def TR_QA_01_requirement_is_not_covered_by_another_module(self):
        plan = "## Requirement-level tests\n| Requirement | Tests |\n|---|---|\n| `TR-SEC-01` | x |\n"
        self.repo.write("a/docs/SPEC.md", "# none\n").write("a/src/test/java/A.java",
                                                           "class A { @Test void TR_SEC_01_in_a() {} }")
        self.repo.write("b/docs/SPEC.md", "# none\n").write("b/docs/TEST-PLAN.md", plan)
        result = self.repo.check()
        self.assertNotCovered(result, "TR-SEC-01")

    def TR_QA_01_configured_extra_root_covers_the_module(self):
        plan = "## Requirement-level tests\n| Requirement | Tests |\n|---|---|\n| `TR-EMB-01` | x |\n"
        self.repo.write("b/docs/SPEC.md", "# none\n").write("b/docs/TEST-PLAN.md", plan)
        self.repo.write("examples/host/src/test/java/H.java", "class H { @Test void TR_EMB_01_host() {} }")
        self.assertTrue(self.repo.check("[test-roots]\nb = examples/host\n").passed)

    # S4: TypeScript forms ------------------------------------------------------

    def TR_QA_01_ts_it_each_title_covers(self):
        self.assertCovered(self.ts("it.each([1, 2])('RLE_001_each %s', (n) => {})\n"))

    def TR_QA_01_ts_test_each_template_title_covers(self):
        self.assertCovered(self.ts("test.each`\n  a    | b\n  ${1} | ${2}\n`('RLE_001_table $a', ({ a }) => {})\n"))

    def TR_QA_01_ts_cts_and_mts_files_are_read(self):
        self.assertCovered(self.ts("it('RLE_001_mts', () => {})\n", file="m/web/a.spec.mts"))
        self.repo.write("m/web/a.spec.mts", "")
        self.assertCovered(self.ts("it('RLE_001_cts', () => {})\n", file="m/web/a.test.cts"))

    def TR_QA_01_ts_vitest_nested_describe_and_it_cover(self):
        body = ("describe('RLE_001_suite', () => {\n  describe('inner', () => {\n"
                "    it('renders', () => { expect(1).toBe(1) })\n  })\n})\n")
        self.assertCovered(self.ts(body))

    def TR_QA_01_ts_playwright_describe_and_test_cover(self):
        body = ("import { test, expect } from '@playwright/test'\n"
                "test.describe('suite', () => {\n  test('RLE_001_signs_in', async ({ page }) => {\n"
                "    await test.step('RLE_002_not_a_test', async () => {})\n  })\n})\n")
        self.assertCovered(self.ts(body, file="m/e2e/sign-in.spec.ts"))

    def TR_QA_01_ts_playwright_parallel_describe_title_covers(self):
        self.assertCovered(self.ts("test.describe.parallel('RLE_001_pw', () => { test('x', async () => {}) })\n"))

    def TR_QA_01_ts_playwright_custom_test_match_is_read(self):
        self.repo.write("m/playwright.config.ts", "export default defineConfig({ testDir: 'e2e', "
                                                  "testMatch: ['**/*.e2e.ts', /\\.flow\\.ts$/] })\n")
        self.assertCovered(self.ts("test('RLE_001_e2e', async () => {})\n", file="m/e2e/sign-in.e2e.ts"))
        self.repo.write("m/e2e/sign-in.e2e.ts", "")
        self.assertCovered(self.ts("test('RLE_001_flow', async () => {})\n", file="m/e2e/pay.flow.ts"))

    def TR_QA_01_ts_file_outside_test_patterns_is_ignored(self):
        self.assertNotCovered(self.ts("test('RLE_001_not_a_test_file', () => {})\n", file="m/web/helper.ts"))

    # S5: jqwik and dynamic tests ------------------------------------------------

    def TR_QA_01_jqwik_boolean_property_covers(self):
        self.assertCovered(self.java("@Property boolean RLE_001_property(@ForAll int x) { return true; }"))

    def TR_QA_01_test_factory_covers(self):
        self.assertCovered(self.java("@TestFactory Stream<DynamicTest> RLE_001_dynamic() { return null; }"))

    def TR_QA_01_parameterized_test_with_array_arguments_covers(self):
        self.assertCovered(self.java('@ParameterizedTest @ValueSource(ints = {1, 2}) void RLE_001_values(int n) {}'))

    def TR_QA_01_archunit_rule_field_covers(self):
        self.assertCovered(self.java("@ArchTest static final ArchRule RLE_001_rule = noClasses().should();"))

    # N2 and N3 -----------------------------------------------------------------

    def TR_QA_01_partial_requirements_are_reported_separately(self):
        plan = ("## Requirement-level tests\n| Requirement | Tests | Level | Coverage |\n|---|---|---|---|\n"
                "| `TR-EMB-01` | x | Unit | Full |\n| `TR-EMB-04` | x | Unit | **Partial.** later |\n")
        self.repo.write("m/docs/TEST-PLAN.md", plan)
        result = self.java("@Test void RLE_001_x() {}\n@Test void TR_EMB_01_x() {}\n@Test void TR_EMB_04_x() {}")
        self.assertTrue(result.passed, result.errors)
        self.assertEqual(result.modules["m"].with_tests, 3)
        self.assertEqual(result.modules["m"].partial, ["TR-EMB-04"])

    def TR_QA_01_three_digit_requirement_is_not_read_as_two_digit(self):
        plan = "## Requirement-level tests\n| Requirement | Tests |\n|---|---|\n| `TR-EMB-012` | x |\n"
        self.repo.write("m/docs/TEST-PLAN.md", plan)
        result = self.java("@Test void RLE_001_x() {}\n@Test void TR_EMB_01_x() {}")
        self.assertNotCovered(result, "TR-EMB-012")


def load_tests(loader, tests, pattern):
    loader.testMethodPrefix = "TR_"
    return loader.loadTestsFromTestCase(SpecCoverageGateTest)


if __name__ == "__main__":
    unittest.main()
