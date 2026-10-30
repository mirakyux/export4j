#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Query OSV for the exact third-party Maven versions in the built runtime SBOM."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import urllib.request


def osv_query(queries: list[dict]) -> dict:
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch",
                                     json.dumps({"queries": queries}).encode("utf-8"),
                                     {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=45) as response:
        raw = response.read(4 * 1024 * 1024 + 1)
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError("OSV response exceeds the audit budget")
    return json.loads(raw)


def audit(sbom: dict, query=osv_query) -> dict:
    if not isinstance(sbom, dict) or sbom.get("bomFormat") != "CycloneDX" or not isinstance(sbom.get("components"), list) or not sbom["components"]:
        raise ValueError("A nonempty CycloneDX runtime SBOM is required")
    coordinates = set()
    for component in sbom["components"]:
        if not isinstance(component, dict):
            raise ValueError("Every runtime component must be an object")
        fields = [component.get(name) for name in ("group", "name", "version")]
        if not all(isinstance(value, str) and value and ":" not in value for value in fields):
            raise ValueError("Every runtime component must have exact Maven coordinates")
        if component.get("purl") != f"pkg:maven/{fields[0]}/{fields[1]}@{fields[2]}":
            raise ValueError("Runtime SBOM coordinates and package URL differ")
        if fields[0] != "com.mirakyux.export4j":
            coordinates.add(tuple(fields))
    if not coordinates:
        raise ValueError("Runtime SBOM has no third-party dependencies to audit")
    results = []
    ordered = sorted(coordinates)
    for start in range(0, len(ordered), 100):
        batch = ordered[start:start + 100]
        response = query([{"package": {"name": group + ":" + name, "ecosystem": "Maven"}, "version": version}
                          for group, name, version in batch])
        if not isinstance(response, dict) or set(response) != {"results"} or not isinstance(response["results"], list) or len(response["results"]) != len(batch):
            raise ValueError("OSV returned an incomplete or unexpected batch response")
        for coordinate, row in zip(batch, response["results"], strict=True):
            if not isinstance(row, dict) or set(row) - {"vulns"}:
                raise ValueError("OSV returned a query error or an unknown result")
            vulnerabilities = row.get("vulns", [])
            if not isinstance(vulnerabilities, list) or not all(isinstance(item, dict) and isinstance(item.get("id"), str) and item["id"] for item in vulnerabilities):
                raise ValueError("OSV returned malformed vulnerability records")
            results.append({"coordinate": ":".join(coordinate), "advisories": sorted({item["id"] for item in vulnerabilities})})
    return {"status": "failed" if any(row["advisories"] for row in results) else "passed",
            "database": "https://osv.dev", "queriedAt": datetime.now(timezone.utc).isoformat(),
            "thirdPartyComponents": len(results), "components": results,
            "scope": "known advisories for exact third-party runtime Maven versions; application defects and native payloads excluded"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, default=Path("build/reports/sbom.cdx.json"))
    parser.add_argument("--output", type=Path, default=Path("build/reports/dependency-audit.json"))
    args = parser.parse_args()
    try:
        raw = args.sbom.read_bytes()
        report = audit(json.loads(raw))
        report["sbomSha256"] = hashlib.sha256(raw).hexdigest()
    except (OSError, ValueError, TypeError) as failure:
        # Replace a stale successful report on service, TLS, parsing or input failure.
        report = {"status": "error", "errorType": type(failure).__name__,
                  "message": "Dependency audit did not complete; no clean result is available"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(json.dumps(report))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
