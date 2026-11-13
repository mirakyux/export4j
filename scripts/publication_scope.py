#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Discover the exact published module scope from the current Gradle settings."""
from pathlib import Path
import hashlib
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def published_modules(root: Path) -> set[str]:
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    includes = re.findall(r'\binclude\((.*?)\)', settings, re.DOTALL)
    modules = {name for block in includes for name in re.findall(r'"(export4j-[a-z0-9-]+)"', block)} - {"export4j-designer"}
    if "export4j-bom" not in modules or len(modules) < 2:
        raise ValueError("Expected published library modules and the BOM in Gradle settings")
    return modules


def published_libraries(root: Path) -> set[str]:
    return published_modules(root) - {"export4j-bom"}


def publication_artifact_count(root: Path) -> int:
    return len(published_libraries(root)) * 5 + 2


def verify_math_source_bundle(binary: Path, root: Path) -> None:
    """Check corresponding upstream sources/build inputs accompany the optional math binary."""
    metadata = ET.parse(root / "gradle/verification-metadata.xml")
    namespace = {"v": "https://schema.gradle.org/dependency-verification"}
    source_name = "jlatexmath-1.0.7-sources.jar"
    hashes = metadata.findall('.//v:component[@group="org.scilab.forge"][@name="jlatexmath"]'
                               '[@version="1.0.7"]/v:artifact[@name="' + source_name + '"]/v:sha256', namespace)
    if len(hashes) != 1:
        raise ValueError("Upstream mathematical source must have one independently reviewed SHA-256")
    with ZipFile(binary) as archive:
        names = archive.namelist()
        if any(name.startswith("org/scilab/") and name.endswith(".class") for name in names):
            raise ValueError("Math module must not shade or replace upstream runtime classes")
        source = archive.read("META-INF/third-party/source/" + source_name)
        if hashlib.sha256(source).hexdigest() != hashes[0].get("value"):
            raise ValueError("Bundled mathematical source differs from its reviewed upstream archive")
        for pom in (root / "third-party/jlatexmath-source-build").glob("*.pom"):
            if archive.read("META-INF/third-party/source/build/" + pom.name) != pom.read_bytes():
                raise ValueError("Bundled upstream build POM differs from the reviewed source input")
