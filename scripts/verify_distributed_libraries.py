# SPDX-License-Identifier: Apache-2.0
"""Check the exact current SBOM library bytes in an isolated caller distribution."""
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile

from verify_advanced_pdf import require
from verify_production import check_bundle


def distributed_libraries(consumer, root):
    check_bundle(consumer)
    components = json.loads((root / "build/reports/sbom.cdx.json").read_text(encoding="utf-8"))["components"]
    own = [item for item in components if item.get("group") == "com.mirakyux.export4j"]
    require(own, "current SBOM contains no Export4j libraries")
    expected = {}
    for item in own:
        name = f'{item["name"]}-{item["version"]}.jar'
        hashes = [entry["content"] for entry in item["hashes"] if entry["alg"] == "SHA-256"]
        require(name not in expected and len(hashes) == 1 and re.fullmatch(r"[0-9a-f]{64}", hashes[0]),
                "current SBOM has duplicate libraries or invalid SHA-256")
        expected[name] = hashes[0]
    archives = list((consumer / "distributions").glob("*.zip"))
    require(len(archives) == 1, "isolated caller distribution is missing or ambiguous")
    with ZipFile(archives[0]) as archive:
        names = [name for name in archive.namelist() if "/lib/export4j-" in "/" + name and name.endswith(".jar")]
        require(len(names) == len({Path(name).name for name in names}), "duplicate distributed library")
        actual = {Path(name).name: hashlib.sha256(archive.read(name)).hexdigest() for name in names}
    require(actual == expected, "distributed caller JARs differ from current SBOM")
    return len(actual)
