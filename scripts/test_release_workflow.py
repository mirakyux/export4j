#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute the release workflow's actual inline Python against isolated public-evidence fixtures."""
from contextlib import chdir, redirect_stdout
from copy import deepcopy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0-rc.3"
COMMIT = "a" * 40
RUN = "123456"
REPOSITORY = "owner/export4j"


def inline_python(marker):
    workflow = (ROOT / ".github/workflows/github-release.yml").read_text(encoding="utf-8")
    blocks = [textwrap.dedent(match[2]) for match in re.finditer(r"(?m)^( +)python3 - <<'PY'\n([\s\S]*?)^\1PY$", workflow)]
    selected = [block for block in blocks if "# " + marker + "\n" in block]
    if len(selected) != 1:
        raise AssertionError("Expected one actual embedded workflow program: " + marker)
    return compile(selected[0], marker, "exec")


def execute(marker, workspace, runner=None):
    environment = {"RELEASE_VERSION": VERSION, "SOURCE_RUN_ID": RUN, "RELEASE_COMMIT": COMMIT,
                   "GH_REPO": REPOSITORY, "GITHUB_OUTPUT": str(workspace / "outputs"),
                   "RUNNER_TEMP": str(workspace), "RELEASE_ROOT": str(workspace / "release-artifacts")}
    with chdir(workspace), patch.dict(os.environ, environment), redirect_stdout(io.StringIO()):
        if runner is None:
            exec(inline_python(marker), {})
        else:
            with patch("subprocess.run", side_effect=runner):
                exec(inline_python(marker), {})


def batch(workspace, layout="build/release/central", library_count=16):
    root = workspace / "release-artifacts" / layout
    (root / "distribution").mkdir(parents=True)
    (root / "remote").mkdir()
    (root / "remote-consumer").mkdir()
    own, artifacts = [], {}
    for index in range(library_count):
        name = f"export4j-fixture-{index}"
        prefix = f"com/mirakyux/export4j/{name}/{VERSION}/{name}-{VERSION}"
        for suffix in (".jar", ".pom", ".module", "-sources.jar", "-javadoc.jar"):
            artifacts[prefix + suffix] = (name + suffix).encode()
        own.append({"group": "com.mirakyux.export4j", "name": name, "version": VERSION,
                    "hashes": [{"alg": "SHA-256", "content": hashlib.sha256(artifacts[prefix + ".jar"]).hexdigest()}]})
    prefix = f"com/mirakyux/export4j/export4j-bom/{VERSION}/export4j-bom-{VERSION}"
    artifacts.update({prefix + ".pom": b"bom-pom", prefix + ".module": b"bom-module"})
    with ZipFile(root / "distribution/maven-central-bundle.zip", "w", ZIP_DEFLATED) as archive:
        for name, content in artifacts.items():
            archive.writestr(name, content)
            archive.writestr(name + ".asc", b"signature verified by original successful release job")
            archive.writestr(name + ".sha256", hashlib.sha256(content).hexdigest())
    sbom = json.dumps({"components": own}).encode()
    (root / "sbom.cdx.json").write_bytes(sbom)
    (root / "public-key.asc").write_text("original public key", encoding="utf-8")
    write(root, "build-inputs.json", {"version": VERSION, "headRevision": COMMIT})
    artifact_count = library_count * 5 + 2
    report = {"version": VERSION, "modules": library_count + 1, "signedArtifacts": artifact_count, "signatureTamperRejected": True,
              "bomConstraintsVerified": library_count, "sbomLibrariesVerified": library_count, "sbomSha256": hashlib.sha256(sbom).hexdigest(),
              "bundleSha256": hashlib.sha256((root / "distribution/maven-central-bundle.zip").read_bytes()).hexdigest()}
    write(root, "distribution/distribution-report.json", report)
    write(root, "remote/publication-report.json", dict(report, uploaded=True, origin="https://repo.maven.apache.org/maven2/",
          remoteArtifactsMatched=artifact_count, remoteSignaturesVerified=artifact_count, remoteConsumerVerified=False))
    consumer = {"remoteConsumerVerified": True, "isolatedCoordinateConsumerVerified": True, "consumerJarHashesVerified": library_count,
                "consumerProjectArtifacts": library_count, "consumerProjectComponents": library_count + 1,
                "consumerBomCoordinate": "com.mirakyux.export4j:export4j-bom:" + VERSION}
    write(root, "remote-consumer/consumer-report.json", dict(consumer, pomOnlyConsumerVerified=True, pomOnlyConsumer=consumer))
    return root


def write(root, name, value):
    (root / name).write_text(json.dumps(value), encoding="utf-8")


class ReleaseWorkflowTest(unittest.TestCase):
    def test_new_module_batch_preserves_historical_recovery(self):
        for library_count in (16, 17, 18):
            with self.subTest(library_count=library_count), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                batch(workspace, library_count=library_count)
                execute("release-batch-validation", workspace)
                self.assertIn(f"All {library_count} library JARs", (workspace / "release-notes.md").read_text(encoding="utf-8"))

    def test_old_and_new_artifact_layouts_keep_original_source(self):
        for layout in ("release/central", "build/release/central"):
            with self.subTest(layout=layout), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                root = batch(workspace, layout)
                execute("release-batch-validation", workspace)
                self.assertIn("release_root=" + str(root.resolve()), (workspace / "outputs").read_text(encoding="utf-8"))
                self.assertIn(COMMIT, (workspace / "release-notes.md").read_text(encoding="utf-8"))

    def test_release_note_links_resolve_against_original_source_notes(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            root = batch(workspace)
            source = ("[规范](../releasing.md)\n"
                      "[文献](../literature-pdf-integration.md#本次真实文献)\n"
                      "[根说明](../../README.md#usage)\n"
                      "[锚点](#主要变化)\n"
                      "[查询](../text-api.md?plain=1#text)\n"
                      "[预算](../fluent-api.md \"引擎预算\")\n"
                      "[资源](<../file with spaces.md#part>)\n"
                      "[绝对](https://example.com/guide?q=1#part)\n"
                      "[邮件](mailto:docs@example.com)\n"
                      "[网络](//example.com/guide)\n")
            (root / "release-notes.md").write_text(source, encoding="utf-8")
            execute("release-batch-validation", workspace)
            result = (workspace / "release-notes.md").read_text(encoding="utf-8")
            base = f"https://github.com/{REPOSITORY}/blob/{COMMIT}/"
            expected = (f"[规范]({base}docs/releasing.md)\n"
                        f"[文献]({base}docs/literature-pdf-integration.md#本次真实文献)\n"
                        f"[根说明]({base}README.md#usage)\n"
                        f"[锚点]({base}docs/releases/{VERSION}.md#主要变化)\n"
                        f"[查询]({base}docs/text-api.md?plain=1#text)\n"
                        f"[预算]({base}docs/fluent-api.md \"引擎预算\")\n"
                        f"[资源](<{base}docs/file with spaces.md#part>)\n"
                        "[绝对](https://example.com/guide?q=1#part)\n"
                        "[邮件](mailto:docs@example.com)\n"
                        "[网络](//example.com/guide)\n")
            self.assertTrue(result.endswith(expected))

    def test_missing_ambiguous_changed_source_bundle_sbom_and_consumers_are_rejected(self):
        for change in ("missing", "ambiguous", "commit", "bundle", "version", "sbom", "pom-only", "remote-signatures", "consumer-version"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                root = batch(workspace)
                if change == "missing":
                    (root / "build-inputs.json").unlink()
                elif change == "ambiguous":
                    (workspace / "release-artifacts/other").mkdir()
                    write(workspace / "release-artifacts/other", "build-inputs.json", {})
                elif change == "commit":
                    write(root, "build-inputs.json", {"version": VERSION, "headRevision": "b" * 40})
                elif change == "bundle":
                    with (root / "distribution/maven-central-bundle.zip").open("ab") as stream:
                        stream.write(b"changed")
                elif change == "sbom":
                    (root / "sbom.cdx.json").write_text("{}", encoding="utf-8")
                else:
                    name = "remote-consumer/consumer-report.json" if change in {"pom-only", "consumer-version"} else "remote/publication-report.json"
                    report = json.loads((root / name).read_text(encoding="utf-8"))
                    if change == "version":
                        report["version"] = "0.1.0-rc.2"
                    elif change == "pom-only":
                        report["pomOnlyConsumer"]["remoteConsumerVerified"] = False
                    elif change == "consumer-version":
                        report["consumerBomCoordinate"] = "com.mirakyux.export4j:export4j-bom:0.1.0-rc.2"
                    else:
                        report["remoteSignaturesVerified"] = 81
                    write(root, name, report)
                with self.assertRaises(ValueError):
                    execute("release-batch-validation", workspace)

    def test_source_requires_original_workflow_success_and_unique_current_artifact(self):
        for change in (None, "static-name", "event", "workflow", "title", "repository", "job-failed", "job-commit", "duplicate", "expired", "artifact-commit"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                title = f"Release {VERSION} to Maven Central and GitHub"
                run = {"id": int(RUN), "head_sha": COMMIT, "event": "workflow_dispatch", "name": title, "display_title": title,
                       "path": ".github/workflows/release.yml", "repository": {"full_name": REPOSITORY},
                       "head_repository": {"full_name": REPOSITORY}}
                jobs = [{"name": "release", "status": "completed", "conclusion": "success", "head_sha": COMMIT}]
                artifacts = [{"name": "maven-central-release-" + VERSION, "id": 789, "expired": False,
                              "workflow_run": {"id": int(RUN), "head_sha": COMMIT}}]
                if change == "event": run["event"] = "pull_request"
                if change == "static-name": run["name"] = "Release to Maven Central"
                if change == "workflow": run["path"] = ".github/workflows/build.yml"
                if change == "title": run["display_title"] = "Release 0.1.0-rc.2 to Maven Central and GitHub"
                if change == "repository": run["head_repository"]["full_name"] = "other/repository"
                if change == "job-failed": jobs[0]["conclusion"] = "failure"
                if change == "job-commit": jobs[0]["head_sha"] = "b" * 40
                if change == "duplicate": artifacts.append(deepcopy(artifacts[0]))
                if change == "expired": artifacts[0]["expired"] = True
                if change == "artifact-commit": artifacts[0]["workflow_run"]["head_sha"] = "b" * 40
                def runner(command, **kwargs):
                    payload = [{"jobs": jobs}] if "/jobs?" in command[-1] else [{"artifacts": artifacts}] if "/artifacts?" in command[-1] else run
                    return SimpleNamespace(stdout=json.dumps(payload))
                if change in {None, "static-name"}:
                    execute("release-source-validation", workspace, runner)
                    self.assertIn("release_commit=" + COMMIT, (workspace / "outputs").read_text(encoding="utf-8"))
                else:
                    with self.assertRaises(ValueError):
                        execute("release-source-validation", workspace, runner)

    def test_existing_release_and_wrong_tag_never_create_or_overwrite(self):
        for existing_release in (True, False):
            with self.subTest(existing_release=existing_release), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                calls = []
                def runner(command, **kwargs):
                    calls.append(command)
                    if "/releases/tags/" in command[-1]:
                        return SimpleNamespace(returncode=0 if existing_release else 1, stdout="{}", stderr="HTTP 404")
                    return SimpleNamespace(stdout=json.dumps([{"ref": "refs/tags/v" + VERSION,
                                           "object": {"type": "commit", "sha": "b" * 40}}]))
                with self.assertRaises(ValueError):
                    execute("release-publication", workspace, runner)
                self.assertFalse(any("POST" in command or "create" in command for command in calls))

    def test_publication_uses_verified_original_tag_without_historical_target(self):
        for tag_exists in (True, False):
            with self.subTest(tag_exists=tag_exists), tempfile.TemporaryDirectory() as temporary:
                workspace = Path(temporary)
                calls = []
                def runner(command, **kwargs):
                    calls.append(command)
                    if "/releases/tags/" in command[-1]:
                        return SimpleNamespace(returncode=1, stdout="", stderr="HTTP 404")
                    if "/git/matching-refs/" in command[-1]:
                        refs = [{"ref": "refs/tags/v" + VERSION, "object": {"type": "commit", "sha": COMMIT}}]
                        return SimpleNamespace(stdout=json.dumps(refs if tag_exists else []))
                    return SimpleNamespace(returncode=0, stdout="", stderr="")
                execute("release-publication", workspace, runner)
                publication = [command for command in calls if command[:3] == ["gh", "release", "create"]]
                self.assertEqual(1, len(publication))
                self.assertEqual("v" + VERSION, publication[0][3])
                self.assertNotIn("--target", publication[0])
                self.assertIn("--verify-tag", publication[0])
                self.assertIn("--prerelease", publication[0])
                writes = [command for command in calls if "POST" in command]
                self.assertEqual(0 if tag_exists else 1, len(writes))
                if writes:
                    self.assertIn("sha=" + COMMIT, writes[0])

    def test_unavailable_release_api_never_creates_a_tag(self):
        with tempfile.TemporaryDirectory() as temporary:
            calls = []
            def runner(command, **kwargs):
                calls.append(command)
                return SimpleNamespace(returncode=1, stdout="", stderr="HTTP 403: access denied")
            with self.assertRaises(RuntimeError):
                execute("release-publication", Path(temporary), runner)
            self.assertEqual(1, len(calls))

    def test_recovery_checks_out_no_project_code_and_normal_release_delegates(self):
        workflow = (ROOT / ".github/workflows/github-release.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("workflow_call:", workflow)
        self.assertIn("actions: read", workflow)
        self.assertNotIn("actions/checkout", workflow)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("maven_central.py", workflow)
        caller = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("uses: ./.github/workflows/github-release.yml", caller)


if __name__ == "__main__":
    unittest.main()
