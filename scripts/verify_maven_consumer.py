#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run a fresh Maven-coordinate consumer and compare its JARs with one verified publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

from publication_scope import published_libraries


def execute(command, root, log, environment=None, timeout=1800):
    with log.open("wb") as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Verification process failed; inspect {log.name}")


def gradle_command(root, home=None):
    command = [str(root / "gradlew.bat")] if os.name == "nt" else ["bash", str(root / "gradlew")]
    command += ["--no-daemon", "--console=plain"]
    return command + (["-g", str(home.resolve())] if home else [])


def published(repository, group, artifact, version, extension, classifier=""):
    directory = repository / group.replace(".", "/") / artifact / version
    resolved = version
    if version.endswith("-SNAPSHOT"):
        metadata = ET.parse(directory / "maven-metadata.xml")
        matches = [entry.findtext("value") for entry in metadata.findall("versioning/snapshotVersions/snapshotVersion")
                   if entry.findtext("extension") == extension and (entry.findtext("classifier") or "") == classifier]
        if len(matches) != 1:
            raise ValueError(f"Ambiguous Maven snapshot artifact: {artifact}/{extension}/{classifier}")
        resolved = matches[0]
    suffix = "-" + classifier if classifier else ""
    return directory / f"{artifact}-{resolved}{suffix}.{extension}"


def consume_once(root, output, repository, version, native_classifier, gradle, central=False, pom_only=False):
    consumer = output / "consumer-project/verification/consumer"
    consumer.mkdir(parents=True)  # Existing runs must never supply stale classes or outputs.
    for filename in ("settings.gradle.kts", "build.gradle.kts", "gradle.lockfile"):
        shutil.copy2(root / "verification/consumer" / filename, consumer / filename)
    shutil.copytree(root / "verification/consumer/src", consumer / "src")
    isolated = consumer.parents[1]
    shutil.copytree(root / "assets", isolated / "assets")
    (isolated / "gradle").mkdir()
    shutil.copy2(root / "gradle/native-platform.gradle.kts", isolated / "gradle/native-platform.gradle.kts")
    environment = os.environ.copy()
    for name in ("EXPORT4J_SIGNING_KEY", "EXPORT4J_SIGNING_PASSWORD", "CENTRAL_TOKEN_USERNAME", "CENTRAL_TOKEN_PASSWORD"):
        environment.pop(name, None)
    command = gradle + ["-p", str(consumer), f"-Pexport4jVersion={version}",
                       f"-Pexport4jNativeClassifier={native_classifier}",
                       f"-Pexport4jPomOnly={str(pom_only).lower()}",
                       f"-Pexport4jCentralOnly={str(central).lower()}"]
    if not central:
        command += [f"-Pexport4jRepositoryDirectory={repository}"]
    execute(command + ["build", "run", "inlineVectors", "math", "fontCache", "contentBlocks", "richContent", "resolvedLibraries", "--write-locks", "--refresh-dependencies"],
            root, output / "signed-consumer.log", environment)
    build = consumer / "build"
    actual = {line for line in (build / "resolved-libraries.txt").read_text(encoding="utf-8").splitlines()
              if line.startswith("com.mirakyux.export4j:")}
    modules = {path.name for path in (repository / "com/mirakyux/export4j").iterdir()
               if (path / version).is_dir() and path.name != "export4j-bom"}
    expected = {f"com.mirakyux.export4j:{name}:{version}" for name in modules}
    if modules != published_libraries(root) or actual != expected:
        raise ValueError("Consumer did not resolve exactly the configured, verified library JARs")
    components = {line for line in (build / "resolved-components.txt").read_text(encoding="utf-8").splitlines()
                  if line.startswith("com.mirakyux.export4j:")}
    bom = "com.mirakyux.export4j:export4j-bom:" + version
    if components != actual | {bom}:
        raise ValueError("Consumer did not resolve the matching BOM and libraries")
    hashes = [line.split("\t") for line in (build / "resolved-library-sha256.txt").read_text(encoding="utf-8").splitlines()]
    own = [row for row in hashes if row[0].startswith("com.mirakyux.export4j:")]
    if len(own) != len(expected) or {row[0] for row in own} != actual:
        raise ValueError("Consumer did not report exactly the configured project JAR hashes")
    files = [line.split("\t") for line in (build / "resolved-library-files.txt").read_text(encoding="utf-8").splitlines()]
    own_files = [(identity, Path(filename)) for identity, filename in files if identity.startswith("com.mirakyux.export4j:")]
    if len(own_files) != len(expected) or {identity for identity, path in own_files} != actual:
        raise ValueError("Consumer did not report exactly the configured resolved project JAR files")
    resolved = dict(own_files)
    for coordinate, filename, sha in own:
        artifact = coordinate.split(":")[1]
        staged = published(repository, "com.mirakyux.export4j", artifact, version, "jar")
        if filename != resolved[coordinate].name or filename not in {artifact + "-" + version + ".jar", staged.name}:
            raise ValueError("Consumer resolved an unexpected artifact classifier or filename")
        if hashlib.sha256(staged.read_bytes()).hexdigest() != sha or hashlib.sha256(resolved[coordinate].read_bytes()).hexdigest() != sha:
            raise ValueError("Consumer JAR differs from the independently verified publication")
    return {"isolatedCoordinateConsumerVerified": True, "consumerProjectArtifacts": len(actual),
            "consumerJarHashesVerified": len(own), "consumerProjectComponents": len(components),
            "consumerBomCoordinate": bom, "remoteConsumerVerified": central}


def consume(root, output, repository, version, native_classifier, gradle, central=False):
    report = consume_once(root, output, repository, version, native_classifier, gradle, central)
    pom_report = consume_once(root, output / "pom-only", repository, version, native_classifier,
                              gradle, central, pom_only=True)
    report.update(pomOnlyConsumerVerified=True, pomOnlyConsumer=pom_report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--native-classifier", required=True)
    parser.add_argument("--gradle-home", type=Path)
    parser.add_argument("--central", action="store_true", help="Resolve only from Maven Central; repository is used solely for hash comparison")
    args = parser.parse_args()
    root, output = args.root.resolve(), args.output.resolve()
    if not output.is_relative_to(root / "build"):
        raise ValueError("Consumer outputs must remain under ignored build/")
    report = consume(root, output, args.repository.resolve(), args.version, args.native_classifier,
                     gradle_command(root, args.gradle_home), args.central)
    (output / "consumer-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
