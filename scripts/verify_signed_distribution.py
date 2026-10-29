#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise signing and an isolated coordinate consumer with a temporary verification-only key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from prepare_maven_bundle import gpg_path, prepare, temporary_keyring


def build_inputs(root: Path):
    paths = {root / name for name in (
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "LICENSE", "NOTICE",
        "scripts/verify_signed_distribution.py", "scripts/prepare_maven_bundle.py",
        "verification/consumer/build.gradle.kts", "verification/consumer/settings.gradle.kts",
        "verification/consumer/gradle.lockfile"
    )}
    paths.update(path for directory in (root / "gradle", root / "third-party/licenses", root / "assets",
                                        root / "verification/consumer/src")
                 for path in directory.rglob("*") if path.is_file())
    for module in root.glob("export4j-*"):
        if module.is_dir():
            paths.update(path for path in (module / "src/main").rglob("*") if path.is_file())
            paths.update(path for path in (module / "build.gradle.kts", module / "gradle.lockfile") if path.is_file())
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def execute(command, root, log, environment=None, timeout=1800):
    with log.open("wb") as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Verification process failed; inspect {log.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=Path("build/verification/signed-distribution"))
    parser.add_argument("--version", required=True, help="Explicit non-SNAPSHOT verification version; never published")
    parser.add_argument("--native-classifier", required=True)
    parser.add_argument("--gradle-home", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output.resolve()
    if not output.is_relative_to(root / "build"):
        raise ValueError("Temporary signing material and reports must remain under ignored build/")
    if args.version.endswith("-SNAPSHOT") or not args.version.startswith("0.0.0-verification"):
        raise ValueError("This fixture only accepts 0.0.0-verification... versions, never a real release version")
    output.mkdir(parents=True, exist_ok=True)
    repository = output / "repository"
    if repository.exists():
        raise ValueError("Use a fresh output directory to prevent stale artifact verification")
    gradle = [str(root / "gradlew.bat")] if os.name == "nt" else ["bash", str(root / "gradlew")]
    gradle += ["--no-daemon", "--console=plain"]
    if args.gradle_home:
        gradle += ["-g", str(args.gradle_home.resolve())]
    gpg = shutil.which("gpg")
    if not gpg:
        raise RuntimeError("GnuPG is required for independent signature validation")
    source_inputs = build_inputs(root)
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                              capture_output=True, text=True, timeout=30).stdout.strip()
    manifest = output / "build-inputs.json"
    manifest.write_text(json.dumps({"headRevision": revision, "source": "working tree; not assumed identical to HEAD",
                                   "files": source_inputs}, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    with temporary_keyring(output, "private-fixture-") as home:
        common = [gpg, "--batch", "--homedir", gpg_path(home, gpg)]
        subprocess.run(common + ["--pinentry-mode", "loopback", "--passphrase=", "--quick-generate-key",
                                  "Export4j verification <verification@example.invalid>", "rsa2048", "sign", "0"],
                       check=True, capture_output=True, timeout=120)
        public_key = output / "verification-public-key.asc"
        public_key.write_bytes(subprocess.run(common + ["--armor", "--export"], check=True, capture_output=True, timeout=60).stdout)
        secret = subprocess.run(common + ["--pinentry-mode", "loopback", "--passphrase=", "--armor", "--export-secret-keys"],
                                check=True, capture_output=True, timeout=60).stdout.decode("ascii")
        environment = os.environ.copy()
        environment["EXPORT4J_SIGNING_KEY"] = secret
        environment["EXPORT4J_SIGNING_PASSWORD"] = ""
        try:
            execute(gradle + [f"-Pexport4jVersion={args.version}", f"-Pexport4jRepositoryDirectory={repository}",
                              "-Pexport4jSign=true", f"-Pexport4jNativeClassifier={args.native_classifier}", "publishSignedBundle"],
                    root, output / "signed-build.log", environment)
        finally:
            environment.pop("EXPORT4J_SIGNING_KEY", None)
            environment.pop("EXPORT4J_SIGNING_PASSWORD", None)
            secret = None
        signed_sbom = output / "sbom.cdx.json"
        shutil.copy2(root / "build/reports/sbom.cdx.json", signed_sbom)
        report = prepare(repository, args.version, public_key, signed_sbom, output, gpg)
        if build_inputs(root) != source_inputs:
            raise RuntimeError("Build inputs changed while signing; use a fresh verification run")
        report["headRevision"] = revision
        report["buildInputsSha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if home.exists():
        raise RuntimeError("Temporary private keyring was not removed")
    report["temporaryPrivateKeyringRemoved"] = True
    # Consumer sources and lock updates are private to this run, with no project dependencies.
    isolated = output / "consumer-project"
    consumer = isolated / "verification/consumer"
    consumer.mkdir(parents=True)
    for filename in ("settings.gradle.kts", "build.gradle.kts", "gradle.lockfile"):
        shutil.copy2(root / "verification/consumer" / filename, consumer / filename)
    shutil.copytree(root / "verification/consumer/src", consumer / "src")
    shutil.copytree(root / "assets", isolated / "assets")
    (isolated / "gradle").mkdir()
    shutil.copy2(root / "gradle/native-platform.gradle.kts", isolated / "gradle/native-platform.gradle.kts")
    execute(gradle + ["-p", str(consumer), f"-Pexport4jVersion={args.version}",
                      f"-Pexport4jRepositoryDirectory={repository}", f"-Pexport4jNativeClassifier={args.native_classifier}",
                      "build", "run", "resolvedLibraries", "--write-locks", "--refresh-dependencies"],
            root, output / "signed-consumer.log", environment)
    libraries = (consumer / "build/resolved-libraries.txt").read_text(encoding="utf-8").splitlines()
    actual = [line for line in libraries if line.startswith("com.mirakyux.export4j:")]
    if len(actual) != 16 or not all(line.endswith(":" + args.version) for line in actual):
        raise ValueError("Consumer did not resolve the complete 16 signed library JARs")
    components = (consumer / "build/resolved-components.txt").read_text(encoding="utf-8").splitlines()
    own_components = {line for line in components if line.startswith("com.mirakyux.export4j:")}
    bom_coordinate = "com.mirakyux.export4j:export4j-bom:" + args.version
    if own_components != set(actual) | {bom_coordinate}:
        raise ValueError("Consumer resolution graph did not select the matching BOM and 16 libraries")
    hashes = [line.split("\t") for line in (consumer / "build/resolved-library-sha256.txt").read_text(encoding="utf-8").splitlines()]
    own_hashes = [entry for entry in hashes if entry[0].startswith("com.mirakyux.export4j:")]
    if len(own_hashes) != 16 or {entry[0] for entry in own_hashes} != set(actual):
        raise ValueError("Consumer did not report exactly 16 resolved library JAR hashes")
    for coordinate, filename, sha in own_hashes:
        artifact = coordinate.split(":")[1]
        if filename != artifact + "-" + args.version + ".jar":
            raise ValueError("Consumer resolved an unexpected project artifact classifier or filename")
        staged = repository / "com/mirakyux/export4j" / artifact / args.version / filename
        if hashlib.sha256(staged.read_bytes()).hexdigest() != sha:
            raise ValueError("Consumer JAR bytes differ from the independently verified signed repository")
    report["isolatedCoordinateConsumerVerified"] = True
    report["consumerProjectArtifacts"] = len(actual)
    report["consumerJarHashesVerified"] = len(own_hashes)
    report["consumerProjectComponents"] = len(own_components)
    report["consumerBomCoordinate"] = bom_coordinate
    if build_inputs(root) != source_inputs:
        raise RuntimeError("Build or consumer inputs changed during verification; use a fresh run")
    report["nativeClassifier"] = args.native_classifier
    report["gpgVersion"] = subprocess.run([gpg, "--version"], check=True, capture_output=True,
                                          text=True, timeout=30).stdout.splitlines()[0]
    report["keyKind"] = "ephemeral verification fixture; not a release identity"
    (output / "distribution-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
