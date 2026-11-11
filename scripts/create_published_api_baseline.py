#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Create a new API baseline from checksum-verified Maven Central release JARs."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen

from verify_api_compatibility import jdk_tool, run, snapshot_jar


CENTRAL = "https://repo.maven.apache.org/maven2/"
GROUP = "com.mirakyux.export4j"
# The published 0.1.0 release-candidate library scope; no BOM, examples or Designer.
LIBRARIES = (
    "export4j-adapter-docx", "export4j-adapter-pdf", "export4j-adapter-template",
    "export4j-adapter-web", "export4j-backend-pdf", "export4j-backend-raster",
    "export4j-backend-svg", "export4j-components", "export4j-font-core",
    "export4j-graphics-api", "export4j-graphics-core", "export4j-layout-core",
    "export4j-layout-model", "export4j-model", "export4j-opentype", "export4j-text-core",
)


def checked_sha256(payload: bytes, checksum: bytes, coordinate: str) -> str:
    expected = checksum.decode("ascii").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise ValueError(f"Missing or invalid Maven Central SHA-256 for {coordinate}")
    actual = hashlib.sha256(payload).hexdigest()
    if actual != expected.lower():
        raise ValueError(f"Maven Central SHA-256 mismatch for {coordinate}")
    return actual


def fetch(url: str, limit: int) -> bytes:
    request = Request(url, headers={"User-Agent": "Export4j-API-baseline"})
    with urlopen(request, timeout=30) as response:
        payload = response.read(limit + 1)
    if len(payload) > limit:
        raise ValueError(f"Maven Central response exceeds {limit} bytes: {url}")
    return payload


def create_baseline(version: str, downloads: Path, baseline: Path, provenance: Path) -> dict:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version) or version.endswith("-SNAPSHOT"):
        raise ValueError("Use an explicit published non-SNAPSHOT version")
    if baseline.resolve() == provenance.resolve():
        raise ValueError("Baseline and provenance require separate output files")
    if baseline.exists() or provenance.exists():
        raise ValueError("Never overwrite a recorded API baseline or its provenance")
    if downloads.exists():
        raise ValueError("Use a fresh download directory to avoid stale artifact inputs")
    jdk = run([jdk_tool("javap"), "-version"]).strip()
    if jdk.split(".", 1)[0] != "21":
        raise ValueError("API baseline extraction requires Java 21 javap")
    downloads.mkdir(parents=True)
    modules, artifacts = {}, {}
    for module in LIBRARIES:
        coordinate = f"{GROUP}:{module}:{version}"
        filename = f"{module}-{version}.jar"
        url = CENTRAL + f"{GROUP.replace('.', '/')}/{module}/{version}/{filename}"
        checksum_url = url + ".sha256"
        payload, checksum = fetch(url, 32 * 1024 * 1024), fetch(checksum_url, 256)
        digest = checked_sha256(payload, checksum, coordinate)
        archive = downloads / filename
        archive.write_bytes(payload)
        (downloads / (filename + ".sha256")).write_bytes(checksum)
        modules[module] = snapshot_jar(archive)
        if not modules[module]:
            raise ValueError(f"Published library contains no accessible API: {coordinate}")
        artifacts[module] = {"coordinate": coordinate, "url": url, "checksumUrl": checksum_url,
                             "sha256": digest, "bytes": len(payload), "accessibleTypes": len(modules[module])}
        print(f"Verified {coordinate}: {len(modules[module])} accessible types", flush=True)
    value = {"schema": 1, "reference": f"maven-central:{GROUP}:*:{version}", "jdk": "21", "modules": modules}
    # Match the API snapshot writer exactly, so this digest binds the recorded file bytes.
    baseline_bytes = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    scripts = Path(__file__).resolve().parent
    evidence = {
        "schema": 1, "source": "published Maven Central binary JARs", "repository": CENTRAL,
        "group": GROUP, "version": version,
        "baseline": {"file": baseline.name, "sha256": hashlib.sha256(baseline_bytes).hexdigest()},
        "extraction": {"tool": "javap", "jdk": jdk, "options": ["-protected", "-s", "-constants"],
                       "scripts": {"scripts/" + name: hashlib.sha256((scripts / name).read_bytes()).hexdigest()
                                   for name in ("create_published_api_baseline.py", "verify_api_compatibility.py")}},
        "artifacts": artifacts,
    }
    baseline.parent.mkdir(parents=True, exist_ok=True)
    with baseline.open("xb") as output:
        output.write(baseline_bytes)
    # A new provenance file is required; an earlier recorded release is never refreshed.
    provenance.parent.mkdir(parents=True, exist_ok=True)
    with provenance.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--downloads", type=Path, required=True, help="Fresh temporary directory for verified Central JARs")
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    args = parser.parse_args()
    evidence = create_baseline(args.version, args.downloads, args.baseline, args.provenance)
    print(f"Recorded {len(evidence['artifacts'])} published library JARs; baseline SHA-256 {evidence['baseline']['sha256']}")


if __name__ == "__main__":
    main()
