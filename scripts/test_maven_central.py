#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise release failure boundaries without credentials, real uploads or timing dependencies."""
from contextlib import nullcontext, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error
from zipfile import ZipFile

import maven_central as central

DEPLOYMENT = "11111111-2222-4333-8444-555555555555"
FINGERPRINT = "A" * 40


class Clock:
    def __init__(self): self.now = 0
    def __call__(self): return self.now
    def pause(self, seconds): self.now += seconds


class CentralReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)

    def test_version_rejects_development_fixture_and_shell_inputs(self):
        for value in ("0.1.0", "0.1.0-rc.2", "1.2.3-beta.1"):
            self.assertEqual(value, central.version(value))
        for value in ("0.1.0-SNAPSHOT", "0.0.0-verification", "../1.2.3", "1.2.3;pwd", "1.2.3\n", "1.2.3-", "1.2.3-rc..2"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                central.version(value)

    def test_existing_coordinates_stop_before_key_or_upload(self):
        with patch.dict(os.environ, {"EXPORT4J_SIGNING_KEY": "fixture"}), \
                patch.object(central, "fetch", return_value=b"existing POM"), \
                patch.object(central, "fingerprint") as key, patch.object(central, "api") as api:
            with self.assertRaisesRegex(ValueError, "already exists"):
                central.preflight(self.output / "fresh", "0.1.0", FINGERPRINT)
            key.assert_not_called()
            api.assert_not_called()

    def test_preflight_only_checks_our_coordinates_and_public_key(self):
        requests = []
        def fetch(url, missing=False):
            requests.append(url)
            return None if url.startswith(central.CENTRAL) else b'public key'
        with patch.dict(os.environ, {"EXPORT4J_SIGNING_KEY": "fixture"}), \
                patch.object(central, "fetch", side_effect=fetch), \
                patch.object(central, "fingerprint", return_value=FINGERPRINT), \
                patch.object(central, "temporary_keyring", return_value=nullcontext(self.output)), \
                patch.object(central.shutil, "which", return_value="gpg"), \
                patch.object(central.subprocess, "run", return_value=Mock(stdout="revision\n")), \
                patch.object(central, "build_inputs", return_value={}), redirect_stdout(io.StringIO()):
            central.preflight(self.output / "preflight", "0.1.0", FINGERPRINT)
        repository_requests = [url for url in requests if url.startswith(central.CENTRAL)]
        self.assertEqual(17, len(repository_requests))
        self.assertTrue(all(url.startswith(central.CENTRAL + 'com/mirakyux/export4j/export4j-') for url in repository_requests))

    def test_private_identity_mismatch_and_unpublished_public_key_stop(self):
        for identity, message in (("B" * 40, "does not match"), (FINGERPRINT, "keyserver")):
            with self.subTest(identity=identity), patch.dict(os.environ, {"EXPORT4J_SIGNING_KEY": "fixture"}), \
                    patch.object(central, "fetch", return_value=None), \
                    patch.object(central, "fingerprint", return_value=identity), \
                    patch.object(central, "temporary_keyring", return_value=nullcontext(self.output)), \
                    patch.object(central.shutil, "which", return_value="gpg"):
                with self.assertRaisesRegex(ValueError, message):
                    central.preflight(self.output / identity, "0.1.0", FINGERPRINT)

    def test_public_fetch_only_treats_404_as_absence(self):
        for code in (404, 403, 429, 500):
            error = urllib.error.HTTPError("https://example.invalid", code, "fixture", {}, None)
            with self.subTest(code=code), patch.object(central.urllib.request, "urlopen", side_effect=error):
                if code == 404:
                    self.assertIsNone(central.fetch("https://example.invalid", missing=True))
                else:
                    expected = central.RepositoryUnavailable if code in (429, 500) else RuntimeError
                    with self.assertRaises(expected) as raised:
                        central.fetch("https://example.invalid", missing=True)
                    self.assertIs(type(raised.exception), expected)
        with patch.object(central.urllib.request, "urlopen", side_effect=urllib.error.URLError("timeout")):
            with self.assertRaises(central.RepositoryUnavailable):
                central.fetch("https://example.invalid", missing=True)

    def test_published_reads_retry_transient_errors_and_visibility_with_one_deadline(self):
        clock = Clock()
        outcomes = [central.RepositoryUnavailable("temporary"), None, b"published artifact"]
        with patch.object(central, "fetch", side_effect=outcomes) as request:
            self.assertEqual(b"published artifact", central.published_file("https://example.invalid", 30, clock, clock.pause))
        self.assertEqual(3, request.call_count)
        self.assertEqual(20, clock.now)
        with patch.object(central, "fetch", side_effect=central.RepositoryUnavailable("temporary")) as request:
            with self.assertRaises(TimeoutError):
                central.published_file("https://example.invalid", 21, clock, clock.pause)
        self.assertEqual(1, request.call_count)
        self.assertEqual(21, clock.now)
        with patch.object(central, "fetch", side_effect=RuntimeError("HTTP 403")) as request:
            with self.assertRaisesRegex(RuntimeError, "403"):
                central.published_file("https://example.invalid", 30, clock, clock.pause)
        self.assertEqual(1, request.call_count)

    def test_publication_paths_reject_traversal_absolute_paths_and_windows_separators(self):
        valid = "com/mirakyux/export4j/export4j-model/0.1.0/export4j-model-0.1.0.jar.asc"
        self.assertEqual(self.output.joinpath(*valid.split("/")), central.publication_path(self.output, valid))
        for name in ("/" + valid, "../" + valid, valid.replace("export4j-model/", "../"),
                     valid.replace("0.1.0/", "C:/"), valid.replace("0.1.0/", "..\\escape/"),
                     valid.replace("0.1.0/", ".//")):
            with self.subTest(name=name), self.assertRaises(ValueError):
                central.publication_path(self.output, name)

    def test_remote_verification_compares_every_download_and_never_calls_upload_api(self):
        bundle = self.output / "bundle.zip"
        files = {}
        with ZipFile(bundle, "w") as archive:
            for index in range(82):
                name = f"com/mirakyux/export4j/export4j-fixture-{index}/0.1.0/fixture-{index}.jar"
                for suffix in ("", ".asc", ".sha256"):
                    files[name + suffix] = (name + suffix).encode()
                    archive.writestr(name + suffix, files[name + suffix])
        for tampered in (False, True):
            output = self.output / ("tampered" if tampered else "matching")
            output.mkdir()
            downloads = dict(files)
            if tampered:
                downloads[next(iter(downloads))] = b"altered remote bytes"
            with patch.object(central, "checked_bundle", return_value=bundle), \
                    patch.object(central, "fetch", side_effect=lambda url, missing=False: downloads[url.removeprefix(central.CENTRAL)]), \
                    patch.object(central, "prepare", return_value={"signedArtifacts": 82}) as verify, \
                    patch.object(central, "api") as api, redirect_stdout(io.StringIO()):
                if tampered:
                    with self.assertRaisesRegex(ValueError, "differs"):
                        central.verify_remote(output, "0.1.0", 60)
                    verify.assert_not_called()
                    self.assertFalse((output / "remote/publication-report.json").exists())
                else:
                    central.verify_remote(output, "0.1.0", 60)
                    verify.assert_called_once()
                    repository = output / "remote/repository"
                    self.assertEqual(246, sum(path.is_file() for path in repository.rglob("*")))
                    self.assertTrue(all((repository / name).read_bytes() == data for name, data in files.items()))
                api.assert_not_called()

    def test_api_failure_never_prints_the_token_or_response_body(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 401, "secret-token", {}, io.BytesIO(b"secret-response"))
        with patch.object(central.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(RuntimeError) as raised:
                central.api("status", "secret-token")
        self.assertNotIn("secret", str(raised.exception))
        self.assertIsNone(central.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere.invalid"))

    def test_status_transitions_and_receipt_use_the_same_deployment(self):
        clock = Clock()
        responses = [json.dumps({"deploymentId": DEPLOYMENT, "deploymentState": state}).encode()
                     for state in ("VALIDATING", "PUBLISHING", "PUBLISHED")]
        with patch.object(central, "api", side_effect=responses), redirect_stdout(io.StringIO()):
            central.wait_for_publication(DEPLOYMENT, "fixture", self.output / "receipt.json", 60, clock, clock.pause)
        self.assertEqual({"deploymentId": DEPLOYMENT, "deploymentState": "PUBLISHED"}, json.loads((self.output / "receipt.json").read_text()))

    def test_failed_unknown_or_mismatched_status_never_passes(self):
        for state, deployment in (("FAILED", DEPLOYMENT), ("NEW_STATE", DEPLOYMENT), ("PUBLISHED", "different")):
            response = json.dumps({"deploymentId": deployment, "deploymentState": state}).encode()
            clock = Clock()
            with self.subTest(state=state, deployment=deployment), patch.object(central, "api", return_value=response), redirect_stdout(io.StringIO()):
                with self.assertRaises((RuntimeError, ValueError)):
                    central.wait_for_publication(DEPLOYMENT, "fixture", self.output / "receipt.json", 60, clock, clock.pause)

    def test_timeout_retains_deployment_instead_of_reuploading(self):
        response = json.dumps({"deploymentId": DEPLOYMENT, "deploymentState": "PUBLISHING"}).encode()
        clock = Clock()
        with patch.object(central, "api", return_value=response) as request, redirect_stdout(io.StringIO()):
            with self.assertRaises(TimeoutError):
                central.wait_for_publication(DEPLOYMENT, "fixture", self.output / "receipt.json", 1, clock, clock.pause)
        self.assertEqual(1, request.call_count)
        self.assertEqual(DEPLOYMENT, json.loads((self.output / "receipt.json").read_text())["deploymentId"])

    def test_ambiguous_upload_is_not_retried(self):
        bundle = self.output / "bundle.zip"
        bundle.write_bytes(b"fixture")
        with patch.dict(os.environ, {"CENTRAL_TOKEN_USERNAME": "user", "CENTRAL_TOKEN_PASSWORD": "password"}), \
                patch.object(central, "checked_bundle", return_value=bundle), \
                patch.object(central, "api", side_effect=RuntimeError("network unavailable")) as request:
            with self.assertRaises(RuntimeError):
                central.publish(self.output, "0.1.0", 60)
        self.assertEqual(1, request.call_count)
        self.assertEqual("UPLOAD_PENDING", json.loads((self.output / "deployment.json").read_text())["deploymentState"])

    def test_upload_uses_automatic_mode_multipart_and_the_portal_token(self):
        bundle = self.output / "bundle.zip"
        bundle.write_bytes(b"fixture bundle")
        with patch.dict(os.environ, {"CENTRAL_TOKEN_USERNAME": "user", "CENTRAL_TOKEN_PASSWORD": "password", "GITHUB_STEP_SUMMARY": ""}), \
                patch.object(central, "checked_bundle", return_value=bundle), \
                patch.object(central, "api", return_value=DEPLOYMENT.encode()) as request, \
                patch.object(central, "wait_for_publication") as wait, redirect_stdout(io.StringIO()):
            central.publish(self.output, "0.1.0-rc.2", 60)
        self.assertEqual(1, request.call_count)
        path, token, body, content_type = request.call_args.args
        self.assertIn("publishingType=AUTOMATIC", path)
        self.assertIn("export4j-0.1.0-rc.2", path)
        self.assertEqual("dXNlcjpwYXNzd29yZA==", token)
        self.assertIn(b'name="bundle"; filename="maven-central-bundle.zip"', body)
        self.assertIn(b"fixture bundle", body)
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        wait.assert_called_once_with(DEPLOYMENT, token, self.output / "deployment.json", 60)

    def test_changed_inputs_commit_or_bundle_stop_before_upload(self):
        bundle = self.output / "distribution/maven-central-bundle.zip"
        bundle.parent.mkdir()
        bundle.write_bytes(b"original")
        central.save(self.output / "build-inputs.json", {"version": "0.1.0", "headRevision": "revision", "files": {"source": "hash"}})
        central.save(self.output / "distribution/distribution-report.json", {"version": "0.1.0", "signedArtifacts": 82,
                     "bundleSha256": hashlib.sha256(b"original").hexdigest()})
        for inputs, revision, data in (({"source": "changed"}, "revision", b"original"),
                                       ({"source": "hash"}, "different", b"original"),
                                       ({"source": "hash"}, "revision", b"modified")):
            bundle.write_bytes(data)
            with self.subTest(inputs=inputs, revision=revision, data=data), patch.object(central, "build_inputs", return_value=inputs), \
                    patch.object(central.subprocess, "run", return_value=Mock(stdout=revision + "\n")):
                with self.assertRaises(ValueError):
                    central.checked_bundle(self.output, "0.1.0")

    def test_export4j_bundle_rejects_artifacts_outside_its_publication_namespace(self):
        bundle = self.output / "distribution/maven-central-bundle.zip"
        bundle.parent.mkdir()
        with ZipFile(bundle, "w") as archive:
            archive.writestr("org/example/dependency/1.0/dependency-1.0.jar", b"external artifact")
        central.save(self.output / "build-inputs.json", {"version": "0.1.0", "headRevision": "revision", "files": {}})
        central.save(self.output / "distribution/distribution-report.json", {"version": "0.1.0", "signedArtifacts": 82,
            "bundleSha256": hashlib.sha256(bundle.read_bytes()).hexdigest()})
        with patch.object(central, "build_inputs", return_value={}), \
                patch.object(central.subprocess, "run", return_value=Mock(stdout="revision\n")):
            with self.assertRaisesRegex(ValueError, "Unexpected publication path"):
                central.checked_bundle(self.output, "0.1.0")


if __name__ == "__main__":
    unittest.main()
