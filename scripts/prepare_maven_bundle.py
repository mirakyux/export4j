#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate a locally signed Maven repository and prepare an uploadable ZIP without uploading."""
import argparse
from contextlib import contextmanager
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


@lru_cache(maxsize=4)
def uses_posix_paths(gpg: str) -> bool:
    if os.name != "nt":
        return False
    result = subprocess.run([gpg, "--version"], check=True, capture_output=True, timeout=30)
    return any(line.startswith(b"Home: /") for line in result.stdout.splitlines())


def gpg_path(path: Path, gpg: str) -> str:
    absolute = path.resolve()
    if uses_posix_paths(gpg) and absolute.drive:
        return "/" + absolute.drive[0].lower() + absolute.as_posix()[2:]
    return str(absolute)


@contextmanager
def temporary_keyring(output: Path, prefix: str):
    # Agent socket paths have small platform limits; use the containing ignored build directory.
    base = next((path for path in (output.resolve(), *output.resolve().parents) if path.name == "build"), output)
    with tempfile.TemporaryDirectory(prefix=prefix, dir=base) as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        try:
            yield home
        finally:
            # Scope shutdown to this fixture, including Windows agent-held socket files.
            configuration = shutil.which("gpgconf")
            if configuration:
                subprocess.run([configuration, "--homedir", gpg_path(home, shutil.which("gpg")), "--kill", "all"],
                               check=True, capture_output=True, timeout=30)


def verify_signature(gpg: str, home: Path, signature: Path, artifact: Path, succeeds=True):
    process = subprocess.run([gpg, "--batch", "--homedir", gpg_path(home, gpg), "--status-fd", "1",
                              "--verify", gpg_path(signature, gpg), gpg_path(artifact, gpg)], capture_output=True, timeout=60)
    valid = process.returncode == 0 and b"[GNUPG:] VALIDSIG " in process.stdout
    if valid != succeeds:
        raise RuntimeError("Detached signature verification did not produce the expected result")


def prepare(repository: Path, version: str, public_key: Path, sbom: Path, output: Path, gpg: str):
    if version.endswith("-SNAPSHOT") or not version or any(c in version for c in "/\\"):
        raise ValueError("An explicit non-SNAPSHOT version is required")
    repository = repository.resolve()
    group = repository / "com/mirakyux/export4j"
    modules = sorted(path for path in group.iterdir() if (path / version).is_dir())
    if len(modules) != 17 or not any(path.name == "export4j-bom" for path in modules):
        raise ValueError("Expected exactly 16 libraries and the BOM")
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    artifacts = []
    library_hashes = {}
    output.mkdir(parents=True, exist_ok=True)
    with temporary_keyring(output, "public-verifier-") as home:
        subprocess.run([gpg, "--batch", "--homedir", gpg_path(home, gpg), "--import", gpg_path(public_key, gpg)],
                       check=True, capture_output=True, timeout=60)
        for module in modules:
            directory = module / version
            prefix = module.name + "-" + version
            extensions = [".pom", ".module"] if module.name == "export4j-bom" else [".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar"]
            selected = [directory / (prefix + suffix) for suffix in extensions]
            for artifact in selected:
                if not artifact.is_file():
                    raise ValueError(f"Missing Maven artifact: {artifact.name}")
                signature = artifact.with_name(artifact.name + ".asc")
                verify_signature(gpg, home, signature, artifact)
                artifacts.extend((artifact, signature))
            pom = ET.parse(directory / (prefix + ".pom")).getroot()
            for xpath in ("m:name", "m:description", "m:url", "m:scm/m:url", "m:scm/m:connection",
                          "m:developers/m:developer/m:id", "m:developers/m:developer/m:name", "m:licenses/m:license/m:url"):
                if not pom.findtext(xpath, namespaces=namespace):
                    raise ValueError(f"Incomplete publication metadata: {module.name}: {xpath}")
            if pom.findtext("m:groupId", namespaces=namespace) != "com.mirakyux.export4j" or pom.findtext("m:version", namespaces=namespace) != version:
                raise ValueError("POM coordinates differ from the staged version")
            metadata = json.loads((directory / (prefix + ".module")).read_text(encoding="utf-8"))
            if metadata["component"]["version"] != version:
                raise ValueError("Gradle metadata version differs from the staged version")
            if module.name == "export4j-bom":
                managed = pom.findall("m:dependencyManagement/m:dependencies/m:dependency", namespace)
                constraints = {(entry.findtext("m:groupId", namespaces=namespace),
                                entry.findtext("m:artifactId", namespaces=namespace),
                                entry.findtext("m:version", namespaces=namespace)) for entry in managed}
                expected = {("com.mirakyux.export4j", item.name, version) for item in modules if item.name != "export4j-bom"}
                if len(managed) != 16 or constraints != expected:
                    raise ValueError("BOM must constrain exactly the 16 staged libraries to the staged version")
            if module.name != "export4j-bom":
                for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
                    with zipfile.ZipFile(directory / (prefix + suffix)) as jar:
                        if not {"META-INF/LICENSE", "META-INF/NOTICE"}.issubset(jar.namelist()):
                            raise ValueError("Every library archive must carry LICENSE and NOTICE")
                binary = directory / (prefix + ".jar")
                library_hashes[module.name] = hashlib.sha256(binary.read_bytes()).hexdigest()
        # A valid signature must also reject modified bytes, independently of Gradle's signer.
        tampered = home / "tampered"
        tampered.write_bytes(artifacts[0].read_bytes() + b"\nmodified\n")
        verify_signature(gpg, home, artifacts[1], tampered, succeeds=False)
    components = json.loads(sbom.read_text(encoding="utf-8"))["components"]
    own = {component["name"]: component for component in components if component.get("group") == "com.mirakyux.export4j"}
    if set(own) != set(library_hashes):
        raise ValueError("SBOM library scope differs from the staged libraries")
    for name, sha in library_hashes.items():
        if own[name]["version"] != version or not any(item["alg"] == "SHA-256" and item["content"] == sha for item in own[name]["hashes"]):
            raise ValueError("SBOM version or hash differs from the actual signed JAR")
    archive = output / "maven-central-bundle.zip"
    checksums = []
    for artifact in artifacts:
        raw = artifact.read_bytes()
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            checksum = artifact.with_name(artifact.name + "." + algorithm)
            checksum.write_text(hashlib.new(algorithm, raw).hexdigest(), encoding="ascii")
            checksums.append(checksum)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for artifact in sorted(artifacts + checksums):
            info = zipfile.ZipInfo(artifact.relative_to(repository).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            bundle.writestr(info, artifact.read_bytes())
    report = {"modules": len(modules), "signedArtifacts": len(artifacts) // 2,
              "signatureTamperRejected": True, "version": version,
              "bomConstraintsVerified": 16,
              "sbomSha256": hashlib.sha256(sbom.read_bytes()).hexdigest(),
              "sbomLibrariesVerified": len(own), "bundleSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
              "uploaded": False, "remoteConsumerVerified": False}
    (output / "distribution-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, default=Path("build/reports/sbom.cdx.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--gpg", default="gpg")
    args = parser.parse_args()
    print(json.dumps(prepare(args.repository, args.version, args.public_key, args.sbom, args.output, args.gpg)))
