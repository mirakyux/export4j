# SPDX-License-Identifier: Apache-2.0
"""Reject incomplete capacity evidence and stale isolated distribution libraries."""
from contextlib import redirect_stdout
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import verify_distributed_libraries as libraries
import verify_embedded_png_recovery as png
import verify_streaming_replay_capacity as capacity
import verify_xml_cross_reference_saving as references


class CapacityEvidenceTest(unittest.TestCase):
    def case(self, mode="layout", name="layout-10"):
        passes = 2 if mode == "replay" else 1
        return {"case": name, "mode": mode, "rows": 10, "samples": 2, "remainingTemporaryFiles": 0,
                "peakTemporaryBytes": 1000,
                "fixture": {"rows": 10, "pages": 1, "passes": passes, "reads": passes * 11,
                            "closes": passes, "maxHeapBytes": 64 * 1024 * 1024}}

    def report(self, directory, cases):
        (directory / "report.json").write_text(json.dumps({"run": "run-1", "results": cases}), encoding="utf-8")

    def test_empty_missing_and_invalid_case_evidence_is_rejected_before_pdf_checks(self):
        valid = self.case()
        invalid = [[], [None], [valid, valid]]
        for key, value in (("case", "../escape"), ("case", "x/y"), ("case", "x\\y"),
                           ("mode", "unknown"), ("rows", 0), ("samples", 0),
                           ("remainingTemporaryFiles", 1), ("fixture", {})):
            case = copy.deepcopy(valid)
            case[key] = value
            invalid.append([case])
        with tempfile.TemporaryDirectory() as temporary, patch.object(capacity, "inspect_pdf") as inspect:
            directory = Path(temporary)
            for cases in invalid:
                with self.subTest(cases=cases):
                    self.report(directory, cases)
                    with self.assertRaises(AssertionError):
                        capacity.verify(directory)
            inspect.assert_not_called()

    def test_only_executed_pdf_checks_can_claim_active_temporary_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.report(directory, [self.case()])
            self.assertEqual({"layout": 1, "pdf": 0, "replay": 0, "stability": 0},
                             capacity.verify(directory)["casesVerified"])
            self.assertFalse(capacity.verify(directory)["activeTemporaryBytesVerified"])
            with self.assertRaisesRegex(AssertionError, "actual PDF case"):
                capacity.verify(directory, True)
            self.report(directory, [self.case("pdf", "pdf-10"), self.case("replay", "replay-10")])
            # Rendering is intentionally mocked: this verifies report claims, not PDF validity.
            with patch.object(capacity, "inspect_pdf", return_value={"bytes": 1000, "rowsVerified": 10}) as inspect:
                result = capacity.verify(directory, True)
                self.assertEqual(2, inspect.call_count)
                self.assertEqual(2, result["activeTemporaryByteChecks"])
                self.assertTrue(result["activeTemporaryBytesVerified"])
                self.assertEqual({"layout": 0, "pdf": 1, "replay": 1, "stability": 0}, result["casesVerified"])

    def test_finite_peak_actual_reads_and_successful_inspection_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for peak in (None, True, float("inf"), float("nan"), 800):
                with self.subTest(peak=peak):
                    case = self.case("pdf", "pdf-10"); case["peakTemporaryBytes"] = peak
                    self.report(directory, [case])
                    with patch.object(capacity, "inspect_pdf", return_value={"bytes": 1000}), self.assertRaises(AssertionError):
                        capacity.verify(directory, True)
            case = self.case("replay", "replay-10"); case["fixture"]["reads"] = 11
            self.report(directory, [case])
            with self.assertRaises(AssertionError):
                capacity.verify(directory)
            self.report(directory, [self.case("pdf", "pdf-10")])
            with patch.object(capacity, "inspect_pdf", side_effect=RuntimeError("bad PDF")), self.assertRaisesRegex(RuntimeError, "bad PDF"):
                capacity.verify(directory, True)

    def test_failure_replaces_stale_success_and_returns_nonzero(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.report(directory, [])
            target = directory / "independent-report.json"
            target.write_text('{"checks":"passed","activeTemporaryBytesVerified":true}', encoding="utf-8")
            with patch("sys.argv", ["capacity", "--capacity-directory", str(directory), "--verify-active-temporary-bytes"]), redirect_stdout(io.StringIO()):
                self.assertEqual(1, capacity.main())
            result = json.loads(target.read_text(encoding="utf-8"))
            self.assertEqual("failed", result["checks"])
            self.assertFalse(result["activeTemporaryBytesVerified"])


class DistributedLibrariesTest(unittest.TestCase):
    def fixture(self, root, count=16):
        payloads = {f"export4j-fixture-{index}-0.1.0.jar": f"library-{index}".encode() for index in range(count)}
        components = [{"group": "com.mirakyux.export4j", "name": name.removesuffix("-0.1.0.jar"),
                       "version": "0.1.0", "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(payload).hexdigest()}]}
                      for name, payload in payloads.items()]
        sbom = root / "build/reports/sbom.cdx.json"
        sbom.parent.mkdir(parents=True)
        sbom.write_text(json.dumps({"components": components}), encoding="utf-8")
        consumer = root / "consumer"
        (consumer / "distributions").mkdir(parents=True)
        self.archive(consumer, payloads)
        return consumer, sbom, payloads

    def archive(self, consumer, payloads, duplicate=False):
        with ZipFile(consumer / "distributions/caller.zip", "w") as archive:
            for name, payload in payloads.items():
                archive.writestr("caller/lib/" + name, payload)
            if duplicate:
                name = next(iter(payloads))
                archive.writestr("another/lib/" + name, payloads[name])

    def test_both_callers_use_current_sbom_and_actual_bytes_instead_of_fifteen(self):
        for count in (16, 17):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); consumer, _, _ = self.fixture(root, count)
                with patch.object(libraries, "check_bundle") as bundle, patch.object(references, "ROOT", root), patch.object(png, "ROOT", root):
                    self.assertEqual(count, references.libraries(consumer))
                    self.assertEqual(count, png.distributed_libraries(consumer))
                    self.assertEqual(2, bundle.call_count)
                    bundle.assert_called_with(consumer)

    def test_stale_missing_extra_and_duplicate_jar_names_fail(self):
        for change in ("stale", "missing", "extra", "duplicate"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); consumer, _, payloads = self.fixture(root)
                first = next(iter(payloads))
                if change == "stale": payloads[first] = b"stale library"
                if change == "missing": del payloads[first]
                if change == "extra": payloads["export4j-extra-0.1.0.jar"] = b"extra"
                self.archive(consumer, payloads, change == "duplicate")
                with patch.object(libraries, "check_bundle"), self.assertRaises(AssertionError):
                    libraries.distributed_libraries(consumer, root)

    def test_empty_duplicate_or_malformed_current_sbom_fails(self):
        for change in ("empty", "duplicate", "hash"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); consumer, sbom, _ = self.fixture(root)
                source = json.loads(sbom.read_text(encoding="utf-8"))
                if change == "empty": source["components"] = []
                if change == "duplicate": source["components"].append(source["components"][0])
                if change == "hash": source["components"][0]["hashes"][0]["content"] = "invalid"
                sbom.write_text(json.dumps(source), encoding="utf-8")
                with patch.object(libraries, "check_bundle"), self.assertRaises(AssertionError):
                    libraries.distributed_libraries(consumer, root)


if __name__ == "__main__":
    unittest.main()
