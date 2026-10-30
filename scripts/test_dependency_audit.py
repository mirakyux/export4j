#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prevent incomplete OSV responses or coordinate mismatches being reported as clean."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from audit_dependencies import audit, main


class DependencyAuditTest(unittest.TestCase):
    def sbom(self):
        return {"bomFormat": "CycloneDX", "components": [
            {"group": "org.example", "name": "library", "version": "1.2.3", "purl": "pkg:maven/org.example/library@1.2.3"}]}

    def test_exact_ecosystem_and_version_are_queried_and_findings_fail(self):
        def query(queries):
            self.assertEqual([{"package": {"name": "org.example:library", "ecosystem": "Maven"}, "version": "1.2.3"}], queries)
            return {"results": [{"vulns": [{"id": "GHSA-fixture"}]}]}
        result = audit(self.sbom(), query)
        self.assertEqual("failed", result["status"])
        self.assertEqual(["GHSA-fixture"], result["components"][0]["advisories"])

    def test_only_complete_empty_results_pass(self):
        self.assertEqual("passed", audit(self.sbom(), lambda _: {"results": [{}]})["status"])
        for response in ({}, {"results": []}, {"results": [{}, {}]}, {"results": [{"error": "unavailable"}]},
                         {"results": [{"vulns": [{}]}]}, {"results": [{"vulns": None}]}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                audit(self.sbom(), lambda _: response)

    def test_empty_or_inconsistent_sbom_is_rejected_before_network(self):
        for source in ([], {}, {"bomFormat": "CycloneDX", "components": []}, {"bomFormat": "CycloneDX", "components": ["invalid"]}, self.sbom()):
            if isinstance(source, dict) and source.get("components") and isinstance(source["components"][0], dict):
                source["components"][0]["version"] = "different"
            with self.assertRaises(ValueError):
                audit(source, lambda _: self.fail("invalid SBOM must not reach the service"))

    def test_service_failure_replaces_stale_success_and_returns_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            sbom = Path(temporary) / "sbom.json"; sbom.write_text(json.dumps(self.sbom()), encoding="utf-8")
            report = Path(temporary) / "report.json"; report.write_text('{"status":"passed"}', encoding="utf-8")
            with patch("sys.argv", ["audit_dependencies.py", "--sbom", str(sbom), "--output", str(report)]), \
                    patch("audit_dependencies.audit", side_effect=TimeoutError()), redirect_stdout(io.StringIO()):
                self.assertEqual(1, main())
            self.assertEqual("error", json.loads(report.read_text(encoding="utf-8"))["status"])


if __name__ == "__main__":
    unittest.main()
