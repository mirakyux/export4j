#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prove real compiled API removals fail, while compatible record extensions retain old descriptors."""
import hashlib
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from create_published_api_baseline import LIBRARIES, checked_sha256, create_baseline
from verify_api_compatibility import compare, jdk_tool, run, snapshot_jar


class ApiCompatibilityTest(unittest.TestCase):
    def compile_api(self, root: Path, source: str, vendor_source: str | None = None) -> dict:
        root.mkdir()
        java = root / "Contract.java"
        java.write_text(source, encoding="utf-8")
        sources = [str(java)]
        if vendor_source:
            vendor = root / "Dependency.java"
            vendor.write_text(vendor_source, encoding="utf-8")
            sources.append(str(vendor))
        run([jdk_tool("javac"), "--release", "21", "-d", str(root), *sources])
        jar = root / "fixture.jar"
        run([jdk_tool("jar"), "--create", "--file", str(jar), "-C", str(root), "Contract.class"])
        return {"schema": 1, "reference": "fixture", "modules": {"fixture": snapshot_jar(jar)}}

    def test_removal_constant_generic_and_abstract_contracts_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = self.compile_api(root / "before", "public abstract class Contract { public static final int LIMIT=4; public abstract void act(); protected void hook() {} public java.util.List<String> names(){return null;} }")
            after = self.compile_api(root / "after", "public abstract class Contract { public static final int LIMIT=5; public abstract void act(); public abstract void required(); private void hook() {} public java.util.List<Integer> names(){return null;} }")
            report = compare(before, after)
            self.assertFalse(report["compatible"])
            descriptions = str(report["breaking"])
            for affected in ("LIMIT", "hook", "names", "required"):
                self.assertIn(affected, descriptions)

    def test_record_component_addition_keeps_old_constructor_and_accessors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = self.compile_api(root / "before", "public record Contract(int count) {}")
            after = self.compile_api(root / "after", "public record Contract(int count, boolean enabled) { public Contract(int count) { this(count, false); } }")
            report = compare(before, after)
            self.assertTrue(report["compatible"], report)
            self.assertTrue(report["additions"])

    def test_third_party_type_leak_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = self.compile_api(root / "before", "public class Contract { }")
            after = self.compile_api(root / "after", "public class Contract { public org.vendor.Dependency value(){return null;} }",
                                     "package org.vendor; public class Dependency {}")
            report = compare(before, after)
            self.assertFalse(report["compatible"])
            self.assertEqual(report["foreignApiTypes"][0]["type"], "org.vendor.Dependency")

    def test_published_jar_requires_valid_matching_upstream_checksum(self):
        checksum = b"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\n"
        self.assertEqual(checked_sha256(b"abc", checksum.upper(), "fixture"), checksum.decode().strip())
        with self.assertRaisesRegex(ValueError, "mismatch"):
            checked_sha256(b"tampered", checksum, "fixture")
        for invalid in (b"", b"not a checksum", checksum + b"unexpected contents"):
            with self.subTest(checksum=invalid), self.assertRaisesRegex(ValueError, "Missing or invalid"):
                checked_sha256(b"abc", invalid, "fixture")

    def test_recorded_baseline_or_provenance_is_never_overwritten(self):
        for recorded in ("baseline.json", "provenance.json"):
            with self.subTest(recorded=recorded), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                existing = root / recorded
                existing.write_text("recorded release", encoding="utf-8")
                with patch("create_published_api_baseline.fetch", side_effect=AssertionError("unexpected download")):
                    with self.assertRaisesRegex(ValueError, "Never overwrite"):
                        create_baseline("0.1.0-rc.2", root / "downloads", root / "baseline.json", root / "provenance.json")
                self.assertEqual(existing.read_text(encoding="utf-8"), "recorded release")
                self.assertFalse((root / "downloads").exists())

    def test_recorded_published_baseline_binds_provenance_and_protects_rc2_additions(self):
        directory = Path(__file__).resolve().parents[1] / "verification/api"
        baseline_path = directory / "baseline-0.1.0-rc.2.json"
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        provenance = json.loads((directory / "baseline-0.1.0-rc.2.provenance.json").read_text(encoding="utf-8"))
        self.assertEqual(provenance["baseline"]["file"], baseline_path.name)
        self.assertEqual(provenance["baseline"]["sha256"], hashlib.sha256(baseline_path.read_bytes()).hexdigest())
        self.assertEqual(set(baseline["modules"]), set(LIBRARIES))
        self.assertEqual(set(provenance["artifacts"]), set(LIBRARIES))
        for module, artifact in provenance["artifacts"].items():
            self.assertEqual(artifact["coordinate"], f"com.mirakyux.export4j:{module}:0.1.0-rc.2")
            self.assertEqual(artifact["accessibleTypes"], len(baseline["modules"][module]))
            self.assertRegex(artifact["sha256"], r"^[0-9a-f]{64}$")
        previous = json.loads((directory / "baseline-0.1.0-rc.1.json").read_text(encoding="utf-8"))
        additions = [(module, name) for module, classes in baseline["modules"].items()
                     for name in sorted(classes.keys() - previous["modules"].get(module, {}).keys())]
        self.assertTrue(additions, "rc.2 must protect declarations absent from the historical rc.1 baseline")
        candidate = {**baseline, "reference": "fixture removal", "modules": {module: dict(classes)
                     for module, classes in baseline["modules"].items()}}
        module, name = additions[0]
        del candidate["modules"][module][name]
        report = compare(baseline, candidate)
        self.assertFalse(report["compatible"])
        self.assertEqual(report["breaking"][0]["api"], module + ":" + name)


if __name__ == "__main__":
    unittest.main()
