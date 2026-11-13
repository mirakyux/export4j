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
import verify_maven_consumer as consumer_verification
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
    def fixture(self, root, count=16, version="0.1.0", resolved_version=None):
        filename_version = resolved_version or version
        payloads = {f"export4j-fixture-{index}-{filename_version}.jar": f"library-{index}".encode() for index in range(count)}
        components = [{"group": "com.mirakyux.export4j", "name": name.removesuffix(f"-{filename_version}.jar"),
                       "version": version, "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(payload).hexdigest()}]}
                      for name, payload in payloads.items()]
        sbom = root / "build/reports/sbom.cdx.json"
        sbom.parent.mkdir(parents=True)
        sbom.write_text(json.dumps({"components": components}), encoding="utf-8")
        consumer = root / "consumer"
        (consumer / "distributions").mkdir(parents=True)
        cache = root / "cache"
        cache.mkdir()
        records = []
        for component, (name, payload) in zip(components, payloads.items(), strict=True):
            resolved = cache / name
            resolved.write_bytes(payload)
            records.append(f'{component["group"]}:{component["name"]}:{version}\t{resolved}')
        (consumer / "resolved-library-files.txt").write_text("\n".join(records) + "\n", encoding="utf-8")
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

    def test_canonical_and_timestamped_snapshot_names_keep_current_bytes(self):
        for resolved in (None, "0.1.0-20261112.132841-93"):
            with self.subTest(resolved=resolved), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                consumer, _, _ = self.fixture(root, version="0.1.0-SNAPSHOT", resolved_version=resolved)
                with patch.object(libraries, "check_bundle"):
                    self.assertEqual(16, libraries.distributed_libraries(consumer, root))

    def test_modified_runtime_library_is_rejected_even_when_bundle_is_current(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            consumer, _, _ = self.fixture(root)
            next((root / "cache").glob("*.jar")).write_bytes(b"stale runtime")
            with patch.object(libraries, "check_bundle"), self.assertRaisesRegex(AssertionError, "resolved caller JARs differ"):
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


class CoordinateConsumerEvidenceTest(unittest.TestCase):
    def fixture(self, root, timestamped=False, change=None):
        source = root / "verification/consumer"
        (source / "src").mkdir(parents=True)
        for name in ("settings.gradle.kts", "build.gradle.kts", "gradle.lockfile"):
            (source / name).write_text("fixture", encoding="utf-8")
        (root / "assets").mkdir()
        (root / "gradle").mkdir()
        (root / "gradle/native-platform.gradle.kts").write_text("fixture", encoding="utf-8")
        repository = root / "repository"
        cache = root / "cache"
        cache.mkdir()
        version, snapshot = "0.1.0-SNAPSHOT", "0.1.0-20261112.132841-93"
        records, hashes = [], []
        for index in range(16):
            artifact = f"export4j-fixture-{index}"
            identity = f"com.mirakyux.export4j:{artifact}:{version}"
            directory = repository / "com/mirakyux/export4j" / artifact / version
            directory.mkdir(parents=True)
            payload = f"current staged library {index}".encode()
            (directory / f"{artifact}-{snapshot}.jar").write_bytes(payload)
            (directory / "maven-metadata.xml").write_text(
                "<metadata><versioning><snapshotVersions><snapshotVersion><extension>jar</extension>"
                f"<value>{snapshot}</value></snapshotVersion></snapshotVersions></versioning></metadata>", encoding="utf-8")
            filename = f"{artifact}-{snapshot if timestamped else version}.jar"
            if index == 0 and change == "classifier":
                filename = f"{artifact}-{snapshot}-sources.jar"
            path = cache / filename
            path.write_bytes(b"stale runtime" if index == 0 and change == "runtime" else payload)
            records.append(identity + "\t" + str(path))
            hashes.append(identity + "\t" + filename + "\t" + hashlib.sha256(payload).hexdigest())
        libraries_report = [row.split("\t")[0] for row in records]
        modules = [row.split(":")[1] for row in libraries_report] + ["export4j-bom"]
        (root / "settings.gradle.kts").write_text("include(" + ",".join('"' + name + '"' for name in modules) + ")", encoding="utf-8")
        if change == "missing":
            records.pop()
        if change == "duplicate":
            hashes.append(hashes[0])
        if change == "extra":
            libraries_report.append(f"com.mirakyux.export4j:export4j-extra:{version}")
        output = root / "output"

        def execute(*args):
            build = output / "consumer-project/verification/consumer/build"
            build.mkdir()
            for name, rows in {"resolved-libraries.txt": libraries_report,
                               "resolved-components.txt": libraries_report + [f"com.mirakyux.export4j:export4j-bom:{version}"],
                               "resolved-library-sha256.txt": hashes,
                               "resolved-library-files.txt": records}.items():
                (build / name).write_text("\n".join(rows) + "\n", encoding="utf-8")

        with patch.object(consumer_verification, "execute", side_effect=execute):
            return consumer_verification.consume_once(root, output, repository, version, "natives-windows", ["unused-gradle"])

    def test_canonical_and_timestamped_snapshots_compare_actual_resolved_bytes(self):
        for timestamped in (False, True):
            with self.subTest(timestamped=timestamped), tempfile.TemporaryDirectory() as temporary:
                self.assertEqual(16, self.fixture(Path(temporary), timestamped)["consumerJarHashesVerified"])

    def test_wrong_classifier_stale_runtime_and_incomplete_or_extra_evidence_fail(self):
        for change in ("classifier", "runtime", "missing", "duplicate", "extra"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary, self.assertRaises(ValueError):
                self.fixture(Path(temporary), True, change)


if __name__ == "__main__":
    unittest.main()
