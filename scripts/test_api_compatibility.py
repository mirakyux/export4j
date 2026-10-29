#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prove real compiled API removals fail, while compatible record extensions retain old descriptors."""
import tempfile
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
