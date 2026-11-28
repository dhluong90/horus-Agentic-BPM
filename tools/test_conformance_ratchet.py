#!/usr/bin/env python3
"""Tests of tools/conformance/ratchet.py: the ratchet fails below the baseline and only then (#203, #204)."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("ratchet", Path(__file__).parent / "conformance" / "ratchet.py")
ratchet = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ratchet)


def dmn(l2_passed, l3_passed, read=0):
    level = lambda passed, cases: {"models": 10, "modelsRead": read, "cases": cases, "passed": passed, "excluded": 0}
    return {"levels": {"compliance-level-2": level(l2_passed, 100), "compliance-level-3": level(l3_passed, 200)},
            "results": [{"status": "FAIL", "reason": "IMPORT: refused (line 3)"}]}


def miwg(read, semantic, diagram, schema, all_):
    return {"summary": {"models": 22, "read": read, "semantic": semantic, "diagram": diagram, "schema": schema,
                        "all": all_},
            "results": [{"model": "A.1.0", "read": False, "semantic": False, "diagram": False, "schema": False,
                         "readError": "refused", "semanticDiffs": [], "diagramDiffs": [], "schemaError": ""}]}


BASE = {"dmnTck": {"compliance-level-2": {"models": 10, "modelsRead": 3, "cases": 100, "passed": 40},
                   "compliance-level-3": {"models": 10, "modelsRead": 3, "cases": 200, "passed": 50}},
        "bpmnMiwg": {"models": 22, "read": 10, "semantic": 5, "diagram": 5, "schema": 8, "all": 4}}


class RatchetTest(unittest.TestCase):
    def run_check(self, d, m):
        return ratchet.check(d, m, BASE, [])[1]

    def test_equal_numbers_pass(self):
        self.assertEqual(self.run_check(dmn(40, 50, 3), miwg(10, 5, 5, 8, 4)), [])

    def test_higher_numbers_pass(self):
        self.assertEqual(self.run_check(dmn(41, 60, 4), miwg(11, 6, 6, 9, 5)), [])

    def test_a_lower_dmn_level_fails_and_names_it(self):
        failures = self.run_check(dmn(39, 50, 3), miwg(10, 5, 5, 8, 4))
        self.assertEqual(len(failures), 1)
        self.assertIn("compliance-level-2", failures[0])

    def test_fewer_models_read_fails(self):
        self.assertEqual(len(self.run_check(dmn(40, 50, 2), miwg(10, 5, 5, 8, 4))), 2)

    def test_each_miwg_check_is_ratcheted_separately(self):
        failures = self.run_check(dmn(40, 50, 3), miwg(10, 5, 4, 8, 3))
        self.assertEqual(len(failures), 2)
        self.assertTrue(any("diagram" in f for f in failures))
        self.assertTrue(any("all three" in f for f in failures))

    def test_no_baseline_never_fails(self):
        self.assertEqual(ratchet.check(dmn(0, 0), miwg(0, 0, 0, 0, 0), None, [])[1], [])

    def test_zero_cases_do_not_divide(self):
        d = dmn(0, 50, 3)
        d["levels"]["compliance-level-2"]["cases"] = 0
        self.assertEqual(self.run_check(d, None), [])

    def test_update_writes_a_baseline_the_next_check_accepts(self):
        with tempfile.TemporaryDirectory() as tmp:
            results = Path(tmp)
            (results / "dmn-tck-results.json").write_text(json.dumps(dmn(7, 9, 2)))
            (results / "bpmn-miwg-results.json").write_text(json.dumps(miwg(3, 2, 2, 3, 1)))
            target = results / "baseline.json"
            self.assertEqual(ratchet.main(["update", str(results), "--baseline", str(target)]), 0)
            self.assertEqual(ratchet.main(["check", str(results), "--baseline", str(target)]), 0)
            (results / "dmn-tck-results.json").write_text(json.dumps(dmn(6, 9, 2)))
            self.assertEqual(ratchet.main(["check", str(results), "--baseline", str(target)]), 1)


if __name__ == "__main__":
    unittest.main()
