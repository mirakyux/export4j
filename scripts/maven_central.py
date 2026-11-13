#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Preflight, upload and verify an explicitly selected Maven Central release."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from zipfile import ZipFile

from prepare_maven_bundle import gpg_path, prepare, temporary_keyring
from verify_signed_distribution import build_inputs
from publication_scope import publication_artifact_count, published_modules

ROOT = Path(__file__).resolve().parents[1]
CENTRAL = "https://repo.maven.apache.org/maven2/"
API = "https://central.sonatype.com/api/v1/publisher/"


class RepositoryUnavailable(RuntimeError):
    """A public read may be retried without repeating a publishing operation."""


def version(value):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", value) or "SNAPSHOT" in value.upper() or value.startswith("0.0.0-verification"):
        raise ValueError("Choose an explicit release version, excluding SNAPSHOT and verification fixtures")
    return value


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def fetch(url, missing=False):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Export4j-release"}), timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        error.close()
        if missing and error.code == 404:
            return None
        failure = RepositoryUnavailable if error.code == 429 or 500 <= error.code < 600 else RuntimeError
        raise failure(f"Public repository request failed (HTTP {error.code})") from None
    except (urllib.error.URLError, TimeoutError):
        raise RepositoryUnavailable("Public repository request failed; no successful check can be recorded") from None


def published_file(url, deadline, clock=time.monotonic, pause=time.sleep):
    while clock() < deadline:
        try:
            data = fetch(url, missing=True)
        except RepositoryUnavailable:
            data = None
        if data is not None:
            return data
        pause(min(10, max(0, deadline - clock())))
    raise TimeoutError("Published files could not be verified before the public repository deadline")


def publication_path(repository, name):
    parts = name.split("/")
    if len(parts) != 6 or parts[:3] != ["com", "mirakyux", "export4j"] or any(
            part in {".", ".."} or not re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in parts):
        raise ValueError(f"Unexpected publication path: {name}")
    return repository.joinpath(*parts)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the publishing token to a redirect target.


def api(path, token, data=b"", content_type="application/json"):
    request = urllib.request.Request(API + path, data=data, method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": content_type})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
            return response.read(1_048_576)
    except urllib.error.HTTPError as error:
        error.close()
        raise RuntimeError(f"Central API request failed (HTTP {error.code}); inspect the deployment in Central Portal") from None
    except (urllib.error.URLError, TimeoutError):
        raise RuntimeError("Central API request failed; inspect Central Portal before attempting another upload") from None


def fingerprint(gpg, home, data):
    result = subprocess.run([gpg, "--batch", "--homedir", gpg_path(home, gpg), "--with-colons",
                             "--import-options", "show-only", "--import"], input=data,
                            check=True, capture_output=True, timeout=30)
    lines = result.stdout.decode("utf-8").splitlines()
    if sum(line.startswith(("pub:", "sec:")) for line in lines) != 1:
        raise ValueError("Signing material must identify exactly one primary key")
    return next(line.split(":")[9] for line in lines if line.startswith("fpr:"))


def preflight(output, release, expected_fingerprint):
    if output.exists():
        raise ValueError("Use a fresh release directory")
    if not re.fullmatch(r"(?:[A-F0-9]{40}|[A-F0-9]{64})", expected_fingerprint):
        raise ValueError("Set EXPORT4J_SIGNING_FINGERPRINT to the full uppercase fingerprint")
    key = os.environ.get("EXPORT4J_SIGNING_KEY", "")
    if not key:
        raise ValueError("EXPORT4J_SIGNING_KEY is required")
    modules = published_modules(ROOT)
    def unused(name):
        path = f"com/mirakyux/export4j/{name}/{release}/{name}-{release}.pom"
        if fetch(CENTRAL + path, missing=True) is not None:
            raise ValueError("This version already exists on Maven Central; choose a new version")
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(unused, sorted(modules)))
    output.mkdir(parents=True)
    gpg = shutil.which("gpg")
    if not gpg:
        raise RuntimeError("GnuPG is required")
    with temporary_keyring(output, "release-identity-") as home:
        if fingerprint(gpg, home, key.encode("utf-8")) != expected_fingerprint:
            raise ValueError("Signing key does not match EXPORT4J_SIGNING_FINGERPRINT")
        public = None
        for url in ("https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x" + expected_fingerprint,
                    "https://keys.openpgp.org/vks/v1/by-fingerprint/" + expected_fingerprint):
            try:
                candidate = fetch(url, missing=True)
            except RuntimeError:
                continue
            if candidate is not None and fingerprint(gpg, home, candidate) == expected_fingerprint:
                public = candidate
                break
        if public is None:
            raise ValueError("Publish this public key to a Central-supported keyserver before releasing")
        (output / "public-key.asc").write_bytes(public)
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, capture_output=True, text=True, timeout=30).stdout.strip()
    save(output / "build-inputs.json", {"version": release, "headRevision": revision,
         "signingFingerprint": expected_fingerprint, "files": build_inputs(ROOT)})
    print("Version is unused; signing identity matches a publicly retrievable key")


def checked_bundle(output, release):
    inputs = json.loads((output / "build-inputs.json").read_text(encoding="utf-8"))
    if inputs["version"] != release or inputs["files"] != build_inputs(ROOT):
        raise ValueError("Release inputs changed after preflight")
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, capture_output=True, text=True, timeout=30).stdout.strip()
    if revision != inputs["headRevision"]:
        raise ValueError("Release commit changed after preflight")
    report = json.loads((output / "distribution/distribution-report.json").read_text(encoding="utf-8"))
    bundle = output / "distribution/maven-central-bundle.zip"
    if report["version"] != release or report["signedArtifacts"] != publication_artifact_count(ROOT) or hashlib.sha256(bundle.read_bytes()).hexdigest() != report["bundleSha256"]:
        raise ValueError("Upload bundle differs from the independently verified batch")
    with ZipFile(bundle) as archive:
        for name in archive.namelist():
            publication_path(Path("repository"), name)
    return bundle


def wait_for_publication(deployment, token, report_file, timeout, clock=time.monotonic, pause=time.sleep):
    deadline = clock() + timeout
    while clock() < deadline:
        response = json.loads(api("status?" + urllib.parse.urlencode({"id": deployment}), token))
        if response.get("deploymentId") != deployment:
            raise ValueError("Central returned a different deployment ID")
        state = response.get("deploymentState")
        if state not in {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"}:
            raise ValueError("Central returned an unknown deployment state")
        save(report_file, {"deploymentId": deployment, "deploymentState": state})
        print("Central deployment: " + state, flush=True)
        if state == "PUBLISHED":
            return
        if state == "FAILED":
            raise RuntimeError("Central validation or publication failed; inspect the retained deployment")
        pause(min(15, max(0, deadline - clock())))
    raise TimeoutError("Central publication is still pending; retain this deployment ID and inspect Portal before any rerun")


def publish(output, release, timeout):
    bundle = checked_bundle(output, release)
    username, password = os.environ.get("CENTRAL_TOKEN_USERNAME"), os.environ.get("CENTRAL_TOKEN_PASSWORD")
    if not username or not password:
        raise ValueError("Both Central Portal user-token secrets are required")
    token = base64.b64encode((username + ":" + password).encode()).decode("ascii")
    boundary = "export4j-" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="maven-central-bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + bundle.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    receipt = output / "deployment.json"
    save(receipt, {"deploymentState": "UPLOAD_PENDING", "version": release})
    query = urllib.parse.urlencode({"publishingType": "AUTOMATIC", "name": "export4j-" + release})
    # A failed upload response is ambiguous. Never automatically repeat this POST.
    deployment = str(uuid.UUID(api("upload?" + query, token, body, "multipart/form-data; boundary=" + boundary).decode().strip()))
    save(receipt, {"deploymentId": deployment, "deploymentState": "SUBMITTED", "version": release})
    print("Central deployment ID: " + deployment, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"\nCentral deployment: `{deployment}`; version: `{release}`.\n")
    wait_for_publication(deployment, token, receipt, timeout)


def verify_remote(output, release, timeout):
    bundle = checked_bundle(output, release)
    target = output / "remote"
    target.mkdir()
    repository = target / "repository"
    deadline = time.monotonic() + timeout
    with ZipFile(bundle) as archive:
        names = [name for name in archive.namelist() if name.endswith((".jar", ".pom", ".module"))]
        if len(names) != publication_artifact_count(ROOT) or len(set(names)) != len(names):
            raise ValueError("Publication artifact count differs from the configured modules")
        expected = {name + suffix: archive.read(name + suffix) for name in names for suffix in ("", ".asc", ".sha256")}
    def download(entry):
        name, original = entry
        # All directories are ours inside a fresh target; validate raw ZIP components
        # before joining them. Windows can resolve missing paths with inconsistent
        # extended-length prefixes while other download threads create directories.
        destination = publication_path(repository, name)
        data = published_file(CENTRAL + name, deadline)
        if data != original:
            raise ValueError("Remote artifact, signature or checksum differs from the uploaded batch")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(download, expected.items()))
    report = prepare(repository, release, output / "public-key.asc", output / "sbom.cdx.json", target, shutil.which("gpg"))
    report.update(uploaded=True, remoteArtifactsMatched=len(names), remoteSignaturesVerified=len(names), origin=CENTRAL)
    save(target / "publication-report.json", report)
    print(f"All {len(names)} remote artifacts, signatures and SHA-256 files match the uploaded batch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "publish", "verify"))
    parser.add_argument("--version", type=version, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/release/central")
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "build") or args.timeout <= 0:
        raise ValueError("Use a build/ output directory and a positive timeout")
    if args.command == "preflight":
        preflight(output, args.version, os.environ.get("EXPORT4J_SIGNING_FINGERPRINT", ""))
    elif args.command == "publish":
        publish(output, args.version, args.timeout)
    else:
        verify_remote(output, args.version, args.timeout)


if __name__ == "__main__":
    main()
