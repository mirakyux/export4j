# Versions and releases

`0.1.0-rc.2` is published to Maven Central. There is no stable release yet. Default development coordinates remain `com.mirakyux.export4j:export4j-*:0.1.0-SNAPSHOT`. Local builds, signing, Central publication, and remote consumption are verified separately.

See the [0.1.0-rc.2 notes](releases/0.1.0-rc.2.md) for changes and known limits. The workflow attaches `docs/releases/<version>.md` to the corresponding GitHub Release. Central and workflow results determine publication state.

## Published candidate record

The `0.1.0-rc.2` [release workflow](https://github.com/mirakyux/export4j/actions/runs/42262570964) completed successfully, including `release` and `github-release`. The [GitHub Release](https://github.com/mirakyux/export4j/releases/tag/v0.1.0-rc.2) was published on November 7, 2026 (Beijing time), with its tag pointing to build commit `88d2bcd81011b1b16c9461f75fb22df247dd422f`. Maven Central provides all 16 libraries and the BOM at this version. That run records building, signing, upload, and isolated remote consumption. An RC does not imply stable APIs or a production SLA.

## Manual GitHub Actions publication

[Release to Maven Central](../../.github/workflows/release.yml) accepts only `workflow_dispatch`. In **Actions → Release to Maven Central → Run workflow**, select the branch and enter a new unpublished version, such as `0.1.0-rc.3`. Running it builds the selected branch's current commit and automatically publishes to Central, then creates a GitHub Release after remote verification. Ordinary pushes, PRs, and tags do not trigger publication. The workflow must exist on the default branch for the manual entry to appear; see [GitHub documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

Before first use, configure **Settings → Secrets and variables → Actions**:

| Type | Name | Value |
| --- | --- | --- |
| Secret | `EXPORT4J_SIGNING_KEY` | Complete ASCII-armored PGP private key, including BEGIN/END lines and actual newlines |
| Secret | `EXPORT4J_SIGNING_PASSWORD` | Key password; omit only for an unprotected key |
| Secret | `CENTRAL_TOKEN_USERNAME` | Username returned by Central Portal's Generate User Token |
| Secret | `CENTRAL_TOKEN_PASSWORD` | Password returned by the same Central user token |
| Variable or Secret | `EXPORT4J_SIGNING_FINGERPRINT` | Complete uppercase public-key fingerprint; Variable takes precedence if both exist |

Central uses a Portal user token, not a website password or GitHub token. See the [official authentication API](https://central.sonatype.org/publish/publish-portal-api/#authentication-authorization).

GitHub Releases use the workflow-provided `GITHUB_TOKEN`, requiring no separate PAT/Secret. Build/Central jobs retain repository read-only permissions. Only the later separate job receives `contents: write`, reads public artifacts retained by the same run, and does not execute project source.

Export a private key on your own machine into a controlled file outside the repository, then enter its complete contents as a Secret. Do not paste it into chat, commits, or logs. Replace these placeholders:

```text
gpg --armor --output <private-key-outside-repository.asc> --export-secret-keys <full-fingerprint>
```

Publish the public key to a Central-supported keyserver beforehand. The workflow checks private/public fingerprints and keyserver retrieval so that a missing key is discovered before upload.

The workflow:

1. Validates an explicit non-SNAPSHOT version, rejecting temporary verification versions, any existing same-version Central module, and existing `v<version>` tags. Records the commit and build inputs.
2. Builds, tests, checks APIs, generates examples, and signs in memory using Java 21, Node.js 24, and the Wrapper, without exporting CI private-key files.
3. Audits this build's SBOM and independently verifies 82 artifacts for 16 libraries and the BOM, signatures, licenses, versions, and tamper rejection; creates an upload ZIP.
4. Resolves those signed artifacts in a fresh consumer, compares all 16 JAR SHA-256 hashes, checks production structures/dual rendering, and verifies capability sample CMS signatures and tamper rejection.
5. Uploads once through the official API in `AUTOMATIC` mode and waits for `PUBLISHED`. This step publicly publishes the version.
6. Downloads exact bytes, signatures, and checksums from Central, verifies signatures/SBOM, and compiles, generates, and inspects output with a fresh Central-only consumer.
7. Uses a separate job to recheck commit, version, package SHA-256, and remote results, then creates `v<version>` and a GitHub Release at the exact build commit. Attaches the signed Maven bundle, public key, SBOM, and reports, and generates commit notes. Suffixed RC/beta versions are prereleases; stable versions become Latest.

The same build inputs are checked before and after upload, with one publication at a time. Private keys enter only signing steps; Central tokens enter only upload/query steps. Artifacts contain public ZIPs, keys, SBOMs, deployment IDs, reports, and sample contact sheets, never private keys or tokens.

Central publication has a 30-minute deadline, followed by up to 10 minutes for public repository distribution. Downloads alone retry temporary absence, network failures, rate limits, and server errors within a shared deadline. After upload failure/timeout, first inspect Portal using the retained deployment ID: a failed request does not prove nonreceipt. The workflow does not automatically reupload or delete deployments. If publication completed but later verification failed, retain evidence and use a new version for fixes; do not overwrite the published version.

A GitHub Release failure does not roll back Central. Check the run first. To retry only GitHub steps, use **Re-run failed jobs**, without rerunning Central upload. Existing tags must point exactly at the build commit. The workflow does not overwrite Releases or incorrect tags. Inspect and resolve any draft left by an interrupted asset upload before retrying.

Automation saves rendering contact sheets, but publishers still perform relevant visual/reader acceptance. The workflow does not include manual Word edit/save or built-in veraPDF; PDF/A compliance needs separate pre-release evidence.

## Local verification

~~~powershell
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities
powershell -File scripts/verify_local_consumer.ps1
python scripts/verify_production.py
python scripts/verify_capabilities.py
~~~

`publishLocalBundle` publishes 16 libraries and the BOM into `build/repository`, without remote services or the user's Maven repository. Libraries include sources, Javadoc, POMs, Gradle metadata, and licenses. SNAPSHOT artifacts are timestamped; verification resolves actual filenames through `maven-metadata.xml`.

`sbom` writes `build/reports/sbom.cdx.json`, a CycloneDX 1.6 runtime inventory with SHA-256 for 16 project libraries and 13 third-party Java artifacts, recording Apache-2.0, BSD-2-Clause, BSD-3-Clause, and MIT by coordinate. New coordinates fail the license allowlist until reviewed. Optional OpenType native classifiers are not transitive in production POMs; applications choose explicitly. See `third-party/native-artifacts.csv` for native payloads/licenses. Fonts, JDK, testing, and build tools are separate inventories.

The independent `verification/consumer` project uses Maven coordinates and the BOM only, restricting project-group resolution to the isolated local repository, then compiles and generates PDF/DOCX/SVG/PNG/HTML/Markdown. The script uses an independent Gradle home and refreshes SNAPSHOTs. This proves local consumption, not Central, signatures, or remote CI.

Root `check` includes `apiCheck`, using baselines extracted from published Central `0.1.0-rc.2` JARs. See [API compatibility (Chinese)](../api-compatibility.md) for comparison rules, per-library origins, and behavioral boundaries. Historical commit `cc132e7` passed remote Java 21 Windows/Linux/macOS builds and temporary-key signed distribution; see [phase 105 (Chinese)](../verification/phase-105-publication-and-compatibility.md#远端-ci-验收). Local phase-106 security/compatibility gates cannot inherit that historical CI result. Later RC2 publication/remote consumption use the actual workflow record above. Local repository checks do not replace upload or remote consumption.

Actual Windows Word/PDF editing, saving, reopening, and final-JAR reading are in [phase 107 (Chinese)](../verification/phase-107-windows-reader-compatibility.md). That historical build/consumer batch was not re-signed; phase-106 signature hashes belong only to that batch, and later RC2 signing/remote results are separate. Application acceptance introduces no Office/browser/printing runtime dependency and does not replace other platforms or releases.

Scripts use fresh consumer build directories to avoid outputs held open from prior runs. See [capability acceptance (Chinese)](../verification/phase-5-capabilities.md) for templates, controlled export, CFF, PDF security/archive/signature checks. Omitting external verifier arguments provides no archive-compliance or signature-verification conclusion.

## Versions and compatibility

Use [Semantic Versioning](https://semver.org/). While public APIs are unstable, use `0.x` and explicitly list incompatible changes in each release.

After `1.0.0`:

- Major versions change public APIs or declared contracts incompatibly.
- Minor versions add backward-compatible capabilities.
- Patch versions provide backward-compatible fixes.

Prereleases use `-alpha.N`, `-beta.N`, or `-rc.N`. Development versions must not appear as published stable versions. Never overwrite published artifacts.

Compatibility includes Java signatures, units, coordinates, closing behavior, errors, diagnostic codes, and declared format support. Layout fixes may change visuals and must document changes/baselines.

Project modules in a publication share one BOM-aligned version. Third-party versions are independent.

## Signing and reviewable bundles

`-Pexport4jVersion=<explicit-version>` overrides the project/BOM version; `-Pexport4jRepositoryDirectory=<local-directory>` selects an isolated repository. Defaults remain development SNAPSHOT and `build/repository`. The directory option does not configure a remote target. Consumers accept the same parameters and still restrict the project group locally by default.

`-Pexport4jSign=true` enables Gradle PGP signing with in-memory keys from `EXPORT4J_SIGNING_KEY` and optional `EXPORT4J_SIGNING_PASSWORD` in the external process environment. Never write keys, passwords, or tokens to Gradle properties, scripts, commits, or logs. Unsigned builds do not require keys; enabled signing without a key fails explicitly.

```powershell
.\gradlew.bat -Pexport4jVersion=<explicit-non-SNAPSHOT-version> -Pexport4jSign=true -Pexport4jRepositoryDirectory=<local-staging-directory> publishSignedBundle
python scripts/prepare_maven_bundle.py --repository <local-staging-directory> --version <same-version> --public-key <public-key-file> --output build/verification/distribution
```

Replace placeholders before running; Linux/macOS use `./gradlew`. Signed `publishSignedBundle` first runs `check` (tests and candidate API gate), `publishLocalBundle`, and `sbom`, staging only 16 libraries and the BOM locally without Central. Complete POMs include project, SCM, maintainers, and license. Binaries, sources, Javadoc, POMs, and Gradle metadata all need valid signatures. The preparation script independently uses GnuPG to verify signatures and tamper rejection, compares actual JARs/SBOM, licenses, and coordinates, and produces a standard-layout ZIP plus MD5/SHA-1/SHA-256/SHA-512 checksums. Install GnuPG and prepare a verifiable public key first.

`scripts/verify_signed_distribution.py` rehearses the entire flow with temporary keys and a `0.0.0-verification...` version, then compiles and generates output through a separately copied consumer. Every resolved project JAR must have the same SHA-256 as the signed repository; matching coordinates/versions alone are insufficient. An input manifest binds production code, build/verification scripts, consumer sources, and resources, failing if they change during signing/consumption. This test identity is not a release identity. The script requires a fresh ignored directory; private keys exist only in temporary keyrings and signing environments and are cleaned afterward. Rehearsal bundles are reviewable and are not automatically uploaded.

Record preparation, temporary/formal keys, ZIP creation, upload, and real remote consumption separately. Build CI rehearsals never upload; only the manual release workflow uses formal credentials to publish.

## Pre-release checks

1. Fix the release commit and confirm scope, worktree, and version.
2. Run configured builds/tests with the Wrapper in a clean environment.
3. Perform relevant structural, independent-tool, and visual checks; record environment/results.
4. Review API differences, incompatible behavior, and migration guidance.
5. Verify dependencies, resource licenses, NOTICEs, and SBOM. Run `python scripts/audit_dependencies.py --sbom <batch-SBOM-path> --output <audit-report-path>` against this exact SBOM, recording advisories/applicability; query failure is not a clean result.
6. Inspect binaries, sources, Javadoc, POM/BOM content, and scopes.
7. Resolve artifacts and run examples in a clean caller project.
8. Check README, release notes, security scope, and private reporting channels.
9. Confirm target, credentials, signatures, and checksums; publish only with authorization.

Maven Central is the current remote channel; namespace and credentials require actual configuration. Never place credentials/private keys in repositories, logs, or documentation.

## Release notes

Include version, date, main behavior changes, incompatibilities, dependency upgrades, known limits, and verification summary. Dates/support follow actual records.

Unfinished capabilities belong to future plans, not current features.

## Post-release verification

Resolve artifacts again from the target repository, check versions/dependencies/signatures/checksums, and run a minimal example.

Build success, upload success, and caller usability are separate results. Preserve failure evidence and follow target rules without overwriting an existing release.
