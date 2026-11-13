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
    expected_coordinates = {}
    for item in own:
        identity = f'{item["group"]}:{item["name"]}:{item["version"]}'
        hashes = [entry["content"] for entry in item["hashes"] if entry["alg"] == "SHA-256"]
        require(identity not in expected_coordinates and len(hashes) == 1 and re.fullmatch(r"[0-9a-f]{64}", hashes[0]),
                "current SBOM has duplicate libraries or invalid SHA-256")
        expected_coordinates[identity] = hashes[0]
    records = [line.split("\t") for line in (consumer / "resolved-library-files.txt").read_text(encoding="utf-8").splitlines()]
    own_records = [(identity, Path(filename)) for identity, filename in records
                   if identity.split(":", 1)[0] == "com.mirakyux.export4j"]
    require(len(own_records) == len(expected_coordinates)
            and {identity for identity, path in own_records} == set(expected_coordinates),
            "resolved caller libraries differ from current SBOM")
    expected = {}
    for identity, path in own_records:
        require(path.name not in expected and hashlib.sha256(path.read_bytes()).hexdigest() == expected_coordinates[identity],
                "resolved caller JARs differ from current SBOM")
        # POM-only SNAPSHOT resolution may use timestamped filenames. The GAV and
        # actual bytes identify the library; keep its resolved filename in the ZIP.
        expected[path.name] = expected_coordinates[identity]
    archives = list((consumer / "distributions").glob("*.zip"))
    require(len(archives) == 1, "isolated caller distribution is missing or ambiguous")
    with ZipFile(archives[0]) as archive:
        names = [name for name in archive.namelist() if "/lib/export4j-" in "/" + name and name.endswith(".jar")]
        require(len(names) == len({Path(name).name for name in names}), "duplicate distributed library")
        actual = {Path(name).name: hashlib.sha256(archive.read(name)).hexdigest() for name in names}
    require(actual == expected, "distributed caller JARs differ from current SBOM")
    return len(actual)
