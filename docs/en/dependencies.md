# Dependency and resource management

## Public documentation-site dependencies

The standalone static documentation site uses VitePress 1.6.4 and Vue 3.5.45 (MIT). Wrangler 4.160.0 (MIT OR Apache-2.0) is used only for static Cloudflare deployment. Direct/transitive versions, sources, and integrity are pinned by `site/package.json` and `site/package-lock.json`; install with `npm --prefix site ci`. These do not enter Java POMs, the BOM, runtime, or library SBOM. The site runs no server-side JavaScript and loads no scripts or fonts through CDNs. See the [documentation-site guide](documentation-site.md) for maintenance and deployment.

VitePress 1.6.4's original dependency range still uses Vite 5. This project applies a targeted override to Vite 6.4.6 for patched build/local-serving behavior; Vue plugin 5.2.4 supports Vite 6 through its peer range. This is outside VitePress's original range. Upgrades must repeat static builds, development-server checks, and page verification; successful installation alone does not establish compatibility. See [GHSA-g2v6-rqmx-r4w6](https://github.com/advisories/GHSA-g2v6-rqmx-r4w6) for the Vue SSR fix and [GHSA-587h-7wmj-h5pm](https://github.com/advisories/GHSA-587h-7wmj-h5pm) for the Vite Windows fix.

Pages use operating-system font stacks. Homepage PDF/WebP assets reuse existing examples and registered fonts; see the [showcase notes (Chinese)](../../site/pages/public/showcase/README.md) for sources, licenses, and content limits. Query dependency advisories with `npm --prefix site audit --registry=https://registry.npmjs.org`. Build-tool advisories and public-site runtime resources are assessed separately; failed queries are not passes.

Static builds generate `THIRD-PARTY-NOTICES.txt` from actual browser modules, extracted styles, and social icons, including exact package names, versions, and original LICENSE/NOTICE text. Builds fail for missing licenses or installed versions differing from the lockfile. Originals absent from npm packages are pinned in the [supplementary license directory (Chinese)](../../third-party/docs-site-licenses/README.md). The theme uses a font-free entry point and does not distribute VitePress's built-in Inter font. Build/deployment-only tools such as Wrangler are excluded from this browser notice.

## Designer browser dependencies

The optional designer's React 19.3.1/React DOM 19.3.1 and ProseMirror editing modules use MIT. See the [browser-component inventory](../../third-party/designer-browser-components.json) for transitive runtime components, exact versions, npm sources, integrity, and original-license locations. Gradle runs npm ci using `frontend/package-lock.json`; esbuild produces a standalone browser bundle and complete `THIRD-PARTY-NOTICES.txt`, both packaged in the designer JAR. These components do not enter Java library POMs, the BOM, or library SBOM.

TypeScript 7.0.2, esbuild 0.28.2, type declarations, and Playwright 1.64.1 are build/verification tools only. Versions and transitive integrity are pinned by the frontend lockfile, and these tools do not ship in the browser bundle. Frontend builds require Node.js 22+; running the built application requires no Node.js. The designer fetches no scripts or fonts from CDNs at runtime.

Query browser runtime advisories with `npm --prefix export4j-designer/frontend audit --omit=dev --registry=https://registry.npmjs.org`. This scope differs from Maven/OSV checks below; query failures cannot count as passes.

## Java and verification dependencies

Common-document examples additionally include a Pexels resume photograph by Joon Tae Kim under the Pexels License, without relicensing it as Apache-2.0. See the [image-resource record (Chinese)](../../examples/src/main/resources/templates/common/assets/README.md) for cropping, dimensions, and source. Original-author and license links remain in designer template ZIPs. The contract logo is drawn by the project. Both image types are bundled locally, without new runtime downloads or Java dependencies.

Optional `export4j-opentype` internally depends on LWJGL 3.4.3 Java artifacts `lwjgl`/`lwjgl-harfbuzz` (BSD-3-Clause). Applications explicitly select platform native classifiers; production POMs do not automatically transitively select platform binaries. Windows binaries actually include HarfBuzz 14.3.0, checked separately from binding-header versions. Included HarfBuzz, libffi, liburing, and other license originals are retained with the inventory; see the [native-artifact record](../../third-party/native-artifacts.csv). The original core JDK path does not force JNI loading.

Linux/macOS x64 and arm64 native-JAR hashes were reviewed against actual downloads and Central publication hashes and added to strict dependency verification. This proves identification of pinned artifacts, rather than successful cross-platform loading/rendering or reader tests. Library tests and isolated consumers share explicit classifier-selection logic and reject unknown platforms/architectures. Actual platform execution results are in [phase 105 (Chinese)](../verification/phase-105-publication-and-compatibility.md).

Independent SVG rendering adds resvg-py 0.5.0 (MIT) and its resvg implementation (MPL-2.0) to the Python verification environment only, outside Java runtime inventories. Font checks use pinned fontTools/uharfbuzz versions. Font sources, SHA-256, and original licenses are independently registered in the [font record (Chinese)](../../assets/fonts/README.md).

PDF trust chains, OCSP/CRL, and PAdES use Bouncy Castle 1.86. The PDF backend directly depends internally on `bcpkix-jdk18on`, transitively bringing same-version `bcprov-jdk18on` and `bcutil-jdk18on`. All three use MIT; actual JAR/POM SHA-256, original licenses, and sources are recorded in the dependency inventory. Public APIs use only project types and JDK keys/certificate bytes, without third-party types. Independent signature verification uses pyHanko 0.33.0, pinned in verification requirements for verification environments only, outside Java artifacts.

Explicit source-font mapping for external PDFs, opaque graphics-state recovery, and independent synthetic corpora are project implementations without new runtime dependencies, font files, or third-party samples. Fixed AFM metrics come from existing PDFBox 3.0.8; verification reuses registered Liberation Sans and original licenses. Callers explicitly supply mapping fonts and respect embedding permissions. See [font compatibility (Chinese)](../pdf-font-compatibility-api.md) and [phase 91 (Chinese)](../verification/phase-91-pdf-source-fonts-and-graphics-state.md).

Bounded semantic recovery of ordinary Form XObjects likewise reuses existing PDFBox 3.0.8. Independent fixtures are generated with pypdf, using exact registered font bytes and custom source widths, without new dependencies, fonts, or external samples. See [phase 92 (Chinese)](../verification/phase-92-pdf-form-recovery.md) for provenance and dual-rendering evidence.

Invisible logical anchors in ordinary PDF output reuse `org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf` from the existing PDFBox 3.0.8 JAR, matching reviewed Regular bytes in the [font record (Chinese)](../../assets/fonts/README.md). Only anchor-used subsets are embedded; this is not font substitution for visible body text. No Maven coordinates or third-party font files were added. Existing complete PDFBox LICENSE/NOTICE and Liberation Sans OFL declarations remain. Original font bytes count toward output font budgets; archival output still uses explicitly configured fonts.

PDF page editing reuses existing PDFBox/FontBox 3.0.8 and registered Liberation Sans. The PDF adapter explicitly declares FontBox to read embedding permissions and no-subsetting flags, without new runtime coordinates, fonts, or third-party samples. Java generates sample images. Source and checking scripts use Apache-2.0. See [phase 49 (Chinese)](../verification/phase-49-pdf-page-editing.md).

Offline HTML import uses jsoup 1.23.2 inside the Web adapter. It calls only String HTML parsing and limited selectors, without connection/loading APIs, and exposes no jsoup types publicly. It has no transitive runtime dependencies, with actual class major 52 compatible with Java 21. Original MIT text comes from the actual JAR. JAR/POM hashes are checked against upstream SHA-1 and Gradle SHA-256; original licenses, attribution, and runtime SBOM remain synchronized.

Phase 106 upgraded jsoup from 1.21.2 to 1.23.2. OSV marked the old version affected by [GHSA-65r4-943x-97jj](https://osv.dev/vulnerability/GHSA-65r4-943x-97jj) (CVE-2026-75140, XML namespace parsing resource costs) and [GHSA-pmhh-3w7g-xqp8](https://osv.dev/vulnerability/GHSA-pmhh-3w7g-xqp8) (raw-text cleaning with custom Safelist). Current code does not invoke either API category. Upgrading removes an affected version, without establishing the same exploitability of existing HTML APIs. New POM resolution involves `org.sonatype.oss:oss-parent:7` and `io.netty:netty-bom:4.2.17.Final`; both POMs were independently checked against upstream hashes and added to strict verification. They add no Netty runtime libraries.

## Known-vulnerability checks

```powershell
.\gradlew.bat sbom
python scripts/test_dependency_audit.py
python scripts/audit_dependencies.py
```
The audit script queries OSV for exact third-party Maven versions in actual `build/reports/sbom.cdx.json`, binding output to that SBOM's SHA-256. Advisory matches, service/TLS/timeout errors, incomplete responses, and inconsistent coordinates all produce nonzero exits. Error reports replace old success results rather than treating unavailable queries as passes. Linux CI runs online audits; all three platforms run offline regression for the gate itself.

Checks cover only Java runtime advisories known to the database when queried. They exclude project defects, native payloads, fonts, JDK, build tools, and verification tools, and do not replace input-boundary, output-content, or application-compatibility checks. The 13 upgraded runtime dependencies had no advisory matches in the 2026-10-29 query. New advisories and later artifacts require fresh checks. See [phase 106 (Chinese)](../verification/phase-106-security-and-compatibility-corpus.md) for actual fixes and reproduction.

General Markdown import uses commonmark-java 0.24.0 and same-version GFM tables/strikethrough extensions as internal Web-adapter implementation dependencies. Public signatures expose no parser types. All three use BSD-2-Clause; originals are extracted from actual JARs and retained with distribution. Source targets Java 11, with actual class major 55 compatible with Java 21. Seven Central POM/JAR artifacts were checked against upstream SHA-1; actual SHA-256 values are recorded in dependency-verification metadata and artifact inventories. No fonts or third-party samples were added.

On 2026-10-21, project-developed `export4j-adapter-web` was added; the project then had 17 modules, 15 libraries, and a BOM. Phase 43 production code depended only on the model/JDK, without new third-party Java coordinates. HTML/Markdown, synthetic samples, Java-generated blue PNG, and verification scripts use Apache-2.0, with no external assets. See [phase 43 (Chinese)](../verification/phase-43-web-exports.md). The current module additionally encapsulates registered CommonMark/jsoup and reuses project components/graphics core for explicit CSS layout. That layout extension added no third-party versions or resources.

On 2026-10-21, XML fixed headers/footers, page-region themes, synthetic reports, and verification scripts were independently implemented under Apache-2.0, reusing existing model, JDK XML/text paths, and registered Liberation Sans. No modules, external dependencies, fonts, or third-party assets were added. See [phase 41 (Chinese)](../verification/phase-41-xml-page-regions.md).

On 2026-10-16, component font scopes/fixed rich text, independent list markers, and XML role/color defaults were independently implemented. No modules, direct/transitive dependencies, fonts, or third-party resources were added. Existing JDK, shared text paths, and registered Liberation Sans were reused. Source, synthetic samples, tests, and checking scripts use Apache-2.0. See [phase 38 (Chinese)](../verification/phase-38-component-text.md), [39 (Chinese)](../verification/phase-39-list-markers.md), and [40 (Chinese)](../verification/phase-40-xml-text-defaults.md).

The 2026-10-16 fixed-page-region font-role fix was independently implemented, without new modules, direct/transitive dependencies, fonts, or third-party resources. Samples, tests, isolated consumers, and independent checking scripts use Apache-2.0 and registered Liberation Sans. See [phase 37 (Chinese)](../verification/phase-37-page-region-fonts.md).

XML static page settings on 2026-10-16 were independently implemented using existing models, JDK XML, and output backends, without new direct/transitive dependencies, fonts, or third-party resources. `page-settings.xml`, synthetic report data, examples, tests, and independent checking scripts use Apache-2.0 and registered Liberation Sans. See [phase 36 (Chinese)](../verification/phase-36-xml-page-settings.md).

This file manages candidate technologies, actual dependencies, and noncode resources. Current adopted versions are Gradle 9.4.1, PDFBox/FontBox 3.0.8, and JUnit 6.1.3. Each module's `gradle.lockfile` pins actual resolved coordinates.

## Candidate technologies

| Purpose | Candidate | Intended location | Current conclusion |
| --- | --- | --- | --- |
| PDF output and processing | [Apache PDFBox](https://pdfbox.apache.org/) | Inside PDF backend and standalone PDF adapter | Adopted 3.0.8; see [PDF API](pdf-adapter-api.md) for static-page processing limits |
| DOCX import/export | JDK ZIP/secure XML | DOCX adapter | Uses the JDK for the semantic subset, without added Maven dependencies |
| Automated testing | [JUnit](https://junit.org/) | Test code | Adopted 6.1.3; Java 21 tests passed |
| Font parsing | [Apache FontBox](https://pdfbox.apache.org/) | Inside font layer | Adopted 3.0.8, encapsulated with pdfbox-io, validating static TrueType/CFF and collection members |
| Complex shaping | JDK TextMeasurer/GlyphVector; optional LWJGL/HarfBuzz | Core static fonts and optional native OpenType module | JDK path remains; see the [native guide (Chinese)](../native-opentype-api.md) for native directions, variable/color fonts, and features |
| Inline images | JDK ImageIO/GraphicAttribute | Bounded model resource validation, text measurement, drawing decode | Explicit static PNG/JPEG only; AWT stays outside public signatures; no added Maven dependencies |
| Build tools | [Gradle](https://gradle.org/) | Build and built-in plugins | Adopted 9.4.1; Java 21 builds passed |

Project names or permissive-license names do not replace review of exact artifacts and transitive dependencies.

The 2026-10-15 merged-cell continuation work was independently implemented without new dependencies or third-party resources.

Default-style propagation on the same day reused existing models, layout, and JDK without new dependencies, fonts, or external assets. See [phase 19 (Chinese)](../verification/phase-19-style-defaults.md) for evidence.

The 2026-10-16 declarative-template work added project-developed `export4j-adapter-template`, bringing the project to 15 modules. Production depended only on the model and JDK `java.xml` StAX, without new external Maven coordinates. Templates and fictional inspection data were project-authored; sample code generated blue PNG. All use Apache-2.0 and existing registered fonts. Tests count JDK loopback HTTP requests, requiring zero after rejecting XXE/DTD/XInclude. BOM, SBOM, lockfiles, and isolated consumers are covered in [phase 21 (Chinese)](../verification/phase-21-xml-templates.md).

## Adoption process

On 2026-10-16, multiscript text/adapter work added project-developed `export4j-adapter-pdf`, bringing the project to 16 modules. It depends on the model through `api` and registered PDFBox 3.0.8 through `implementation`, without new third-party Maven coordinates. Markdown and XML fields/external links remain implemented with the JDK inside the template adapter. New unmodified Noto Sans Devanagari/Thai static fonts come from the same pinned official commit as Noto Naskh Arabic. See the [font record (Chinese)](../../assets/fonts/README.md) for provenance, SHA-256, and shared original OFL text. Example JARs include every font and original license; core libraries include no example fonts. Corpora, XML, Markdown, synthetic data, and images are project-authored without copied competitor implementations.

1. Explain purpose, consuming modules, necessity, and public-API isolation.
2. Select a pinned version and verify Java compatibility, capability coverage, and known security issues.
3. Resolve direct/transitive dependencies and record full coordinates, actual versions, sources, and dependency paths.
4. Review every license, copyright, NOTICE, patent clause, and distribution requirement.
5. Record conclusions and evidence before updating build configuration and version catalogs.
6. Recheck dependency graphs and packaged artifacts on upgrades/releases rather than reusing conclusions for old versions.

Runtime dependencies, build plugins, test tools, native libraries, and bundled resources all require review. Use dependency locking and verification metadata as needed to keep resolution traceable.

## Adopted dependency records

See [dependency coordinates and evidence](../../third-party/dependencies.csv) for the complete inventory of 24 Maven coordinates, including BOM and test dependencies. Platform native classifier artifacts are separately listed in `third-party/native-artifacts.csv`. Original licenses and attribution files are in the [license directory](../../third-party/licenses). No external Gradle plugins are currently introduced.

| Dependency | Version | Scope | License basis and handling |
| --- | --- | --- | --- |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | Runtime | Apache-2.0 plus embedded-resource licenses; retain complete LICENSE / NOTICE |
| `org.apache.pdfbox:fontbox` | 3.0.8 | Direct font-module runtime; transitive PDF backend/adapter runtime | Apache-2.0 plus embedded-resource licenses; retain complete LICENSE / NOTICE |
| `org.apache.pdfbox:pdfbox-io` | 3.0.8 | Direct font-module runtime; transitive PDF backend/adapter runtime | Apache-2.0; retain LICENSE / NOTICE |
| `commons-logging:commons-logging` | 1.4.0 | Transitive runtime | Apache-2.0; retain LICENSE / NOTICE |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | Internal PDF-backend runtime | MIT; retain original license from actual JAR |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | Transitive PDF-backend runtime | MIT; retain original license from actual JAR |
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | Transitive PDF-backend runtime | MIT; retain original license from actual JAR |
| `org.jsoup:jsoup` | 1.23.2 | Internal Web-adapter runtime | MIT; retain original license from actual JAR |
| `org.commonmark:commonmark` | 0.24.0 | Internal Web-adapter runtime | BSD-2-Clause; retain original LICENSE.txt from actual JAR |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.24.0 | Internal Web-adapter runtime | BSD-2-Clause; retain original LICENSE.txt from actual JAR |
| `org.commonmark:commonmark-ext-gfm-strikethrough` | 0.24.0 | Internal Web-adapter runtime | BSD-2-Clause; retain original LICENSE.txt from actual JAR |
| `org.junit:junit-bom` | 6.1.3 | Test-version constraints | EPL-2.0; POM and same-version JUnit license files |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | Tests | EPL-2.0; retain LICENSE.md |
| `org.junit.jupiter:junit-jupiter-api` | 6.1.3 | Transitive tests | EPL-2.0; retain LICENSE.md |
| `org.junit.jupiter:junit-jupiter-engine` | 6.1.3 | Transitive tests | EPL-2.0; retain LICENSE.md |
| `org.junit.jupiter:junit-jupiter-params` | 6.1.3 | Transitive tests | EPL-2.0; retain LICENSE.md |
| `org.junit.platform:junit-platform-commons` | 6.1.3 | Transitive tests | EPL-2.0; retain LICENSE.md |
| `org.junit.platform:junit-platform-engine` | 6.1.3 | Transitive tests | EPL-2.0; retain LICENSE.md |
| `org.junit.platform:junit-platform-launcher` | 6.1.3 | Test runtime | EPL-2.0; retain LICENSE.md |
| `org.opentest4j:opentest4j` | 1.3.0 | Transitive tests | Apache-2.0; retain LICENSE |
| `org.apiguardian:apiguardian-api` | 1.1.2 | Transitive test compilation | Apache-2.0; retain LICENSE |
| `org.jspecify:jspecify` | 1.0.0 | Transitive test compilation | Apache-2.0; original JAR lacks a license file, so retain v1.0.0 source-repository LICENSE |

Initial review was dated 2026-10-14 and checked POMs, actual JARs, and their declarations during project initialization. CSV records sources, scopes, artifact SHA-256, and license-file paths. BOM SHA-256 refers to its POM; other hashes refer to JARs.

Phase two on the same day changed existing FontBox/pdfbox-io to direct internal dependencies of the font module without new Maven coordinates. Versions and license bases remained unchanged. Intermodule `api` dependencies propagate project-owned types only; `implementation` isolates third-party parsers. Lockfiles and `gradle/verification-metadata.xml` pin resolution and SHA-256 verification.

Phase three added two project layout modules without new Maven coordinates. Layout core reuses infrastructure through font, text, and graphics modules only, without production dependencies on the PDF backend.

Basic tables continued with those nine modules and existing fonts, without new Maven coordinates, native components, fonts, or external resources. Column widths, row heights, and whole-row pagination are project implementations.

The first component version added `export4j-components`, bringing the total to ten project modules while retaining 15 Maven coordinates. Contents, page regions, and positioning are project implementations. PDFBox creates bookmarks, annotations, and AcroForm internally in the backend. Basic sample forms use unembedded ASCII Helvetica; advanced forms fully embed caller-supplied Unicode fonts. PNG/JPEG images are code-generated.

Advanced features added no Java runtime dependencies: PNG/JPEG use JDK ImageIO, while the SVG subset uses JDK XML parsing. Three Liberation Sans 2.1.5 variants were added; see the [font record (Chinese)](../../assets/fonts/README.md) for provenance, original OFL, and hashes. A clean dependency environment added verification entries for the existing JUnit BOM 5.14.3 `.module` and 6.1.3 `.pom`, checking SHA-256 against Central without changing dependency versions.

Current adoption conclusion: runtime dependencies above are distributed with original licenses/declarations. JUnit is test-only and does not enter production runtime dependencies. PDFBox/FontBox licenses include BSD-style clauses, font OFL, and other resource declarations; an Apache-2.0 label alone is insufficient. Complete originals remain with artifacts. Included font resources are not modified, sold separately, or renamed.

The production batch added SVG/Raster, DOCX, and BOM, bringing the total to 14 project modules while retaining 15 external Maven coordinates. JDK java.desktop provides OpenType/Java2D and ZIP/XML provides DOCX, without new production dependencies. New Noto Naskh Arabic static fonts came from a pinned official commit. Original SIL OFL 1.1 and SHA-256 are in the [font record (Chinese)](../../assets/fonts/README.md); example JARs include both fonts and licenses.

Public complex-text, template, and controlled-export work retained the modules/Maven coordinates above. The text module added an internal one-way dependency on the model to pass execution checkpoints. Source Sans 3.052R static CFF and original OFL were added; see the [font record (Chinese)](../../assets/fonts/README.md) for provenance, byte counts, hashes, and reserved font names. Core libraries still include no example fonts.

Each future addition or upgrade must record:

- Maven coordinates or another explicit identifier, exact version, and source.
- Consuming modules, runtime/build/test purposes, and direct/transitive relationships.
- License identifiers, original licenses or official sources, and review evidence.
- Copyright and NOTICE requirements, artifact distribution status, and obligations.
- Java compatibility and key-capability verification results.
- Review conclusions, date, and confirming maintainer.

Entries without completed reviews must not be marked as passed. At release time, compare all actually resolved dependencies with records and have a maintainer confirm obligations for the intended distribution.

## Build and CI tools

Gradle Wrapper is generated by official Gradle 9.4.1 tooling and carries Apache-2.0 declarations. The distribution ZIP's SHA-256 is recorded in Wrapper configuration; Wrapper JAR was also checked against official hashes:

~~~text
distribution: 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
wrapper jar: 55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
~~~
Gradle 9.4 updates its bundled Kotlin compiler, fixing older Kotlin DSL script-compilation issues under directory-junction and related path conditions. See [Gradle #36483](https://github.com/gradle/gradle/issues/36483) and [#35572](https://github.com/gradle/gradle/issues/35572). This affects build tooling only and adds no library runtime dependencies.

The [CI workflow](../../.github/workflows/build.yml) uses `actions/checkout`, `actions/setup-java`, and `actions/upload-artifact`, pinned by complete commit SHA. Their source projects use MIT; these tools do not enter project libraries or distribution packages. Pushes to `main`, pull requests, and manual operations trigger Java 21 Windows/Linux/macOS builds and independent signature-verification tasks. See [platform/signature verification (Chinese)](../verification/phase-105-publication-and-compatibility.md#远端-ci-验收) for completed remote runs and uncovered scope.

Independent PDF verification uses Python 3.11+, [pypdf 6.10.0](https://pypi.org/project/pypdf/6.10.0/) (BSD-3-Clause), [pypdfium2 5.13.0](https://pypi.org/project/pypdfium2/5.13.0/) (Apache-2.0/BSD-3-Clause, retaining PDFium and third-party licenses in wheels), and [Pillow 12.3.0](https://pypi.org/project/Pillow/12.3.0/) (MIT-CMU; wheels additionally declare bundled third-party libraries). Versions are pinned in `scripts/requirements-verification.txt`. External Poppler is a GPL-2.0-or-later verification tool installed by the environment/system package manager. These tools do not enter Java dependencies, JARs, or example distributions. See [advanced verification (Chinese)](../verification/phase-3-advanced.md) for local versions. Future packaged tool environments must retain licenses for every wheel/native dependency.

Independent Markdown verification uses [markdown-it-py 4.0.0](https://pypi.org/project/markdown-it-py/4.0.0/) and [mdurl 0.1.2](https://pypi.org/project/mdurl/0.1.2/), both MIT and pinned in verification requirements. Original licenses remain with wheels in isolated environments. Both support the current Python 3.11+ verification baseline and do not enter Java JARs, Maven POMs, or runtime SBOM. Packaging verification environments requires complete copyright/licenses. Independent HTML structural checks use Python's standard-library HTMLParser.

Independent DOCX verification additionally uses [python-docx 1.2.0](https://pypi.org/project/python-docx/1.2.0/) (MIT). lxml and underlying XML-library licenses remain in verification environments, outside Java artifacts. Versions are likewise pinned in verification requirements. Independent parsing does not establish Word visual compatibility.

Independent AES-256 checks use [cryptography 50.0.2](https://pypi.org/project/cryptography/50.0.2/) (Apache-2.0 OR BSD-3-Clause, retaining native-dependency declarations in wheels), pinned in verification requirements. PDF/A-2b uses the official [veraPDF 1.30.3](https://software.verapdf.org/releases/1.30/verapdf-greenfield-1.30.3-installer.zip) Greenfield CLI, installed only into ignored `build/verification/verapdf` following [installation instructions](https://docs.verapdf.org/install/), under GPL-3.0-or-later/MPL-2.0. Both are verification tools outside Java artifacts.

Detached CMS examples and independent signature verification use OpenSSL bundled with local Git (Apache-2.0; actual version is in the [verification record (Chinese)](../verification/phase-5-capabilities.md)). Test private keys and self-signed certificates are generated only in ignored `build/verification/signing`; they are neither packaged nor a trust endorsement. Callers explicitly configure sample signing callbacks. The library neither depends on OpenSSL nor manages private keys.

## Fonts, images, and sample documents

Downloadability or readability alone does not establish redistribution rights. Every new resource must record its original source, author/rights holder, version/hash, license, purpose, and distribution restrictions.

Except for the separately registered resume photograph, example images are code-generated. PDF standard fonts in the precise-drawing example are not embedded. Text examples and font/text/PDF tests use pinned fonts in `assets/fonts`; see [font provenance and hashes (Chinese)](../../assets/fonts/README.md).

The 2026-10-16 two-level block-cell/XML nested-cell implementation retained 16 modules. Synthetic reports, XML, and two-color PNG from BlockCellsExample use Apache-2.0, reusing registered fonts and verification tools without new external dependencies/resources. The template adapter depends on the DOCX adapter only in test configuration to verify standard output of the same semantic tree; production still depends only on the model/JDK.

`InlineImagesExample.resource(...)` generates transparent blue PNG and orange JPEG; original encodings are stored in DOCX under the project's Apache-2.0. No external images, fonts, or sample documents were added. See [inline-image verification (Chinese)](../verification/phase-6-inline-images.md) for geometry, media, and independent rendering.

`MultiFloatExample.image()` generates RGB checkerboard-gradient images; project components draw text boxes/rectangles under Apache-2.0. Existing explicit fonts and independent verification tools are reused without new modules or runtime dependencies. See [multiple-float verification (Chinese)](../verification/phase-7-multi-floats.md).

`ContinuousColumnsExample` generates continuous-column examples using Liberation Sans, Noto Naskh Arabic, and LXGW WenKai TC. Independent scripts read explicit font tables with Python's standard library, final PDF text positions with pypdf, and reuse the two renderers/Pillow above. No modules, runtime/verification dependencies, or external resources were added. See [continuous-column verification (Chinese)](../verification/phase-8-continuous-columns.md).

`MixedColumnsExample` generates mixed-column examples with the fonts above and code-generated image pixels under Apache-2.0. Independent scripts reuse final-PDF matrix/image checks and rendering tools, without new resources or dependencies. See [mixed-column verification (Chinese)](../verification/phase-9-mixed-columns.md).

Liberation Sans was extracted unchanged from PDFBox 3.0.8 JAR; LXGW WenKai TC and ZCOOL XiaoWei come from pinned Google Fonts commits. All three use SIL OFL 1.1, retaining their copyrights, reserved font names, and original licenses without modification, renaming, or separate sale. Example JARs distribute them; core libraries contain no example fonts. Runtime does not download resources automatically.

Liberation Sans bold, italic, and bold-italic come unchanged from the official 2.1.5 release. Their separate license file enters example JARs with the fonts. Root NOTICE points to both original Liberation license records.

Current examples and mixed-text tests use Liberation Sans and LXGW WenKai TC. ZCOOL XiaoWei remains for reproducing historical phase-two samples. Independent rendering reproduced the original font's solid outline for “回”; phase three switched fonts without changing original font bytes.

Fonts additionally require checks of embedding, subsetting, modification, and redistribution conditions. Samples use project-created or explicitly authorized sanitized content, without customer data, credentials, or personal information.

Local test resources that cannot be publicly distributed must stay outside the repository and release packages. Publicly reproducible verification should provide replacement samples with explicit licenses.

## LICENSE, NOTICE, and SBOM

ManuWeave academic examples derive catalog/layout facts from pinned commit `e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b` of [jiassia/manuweave](https://github.com/jiassia/manuweave/), covering 286 Profile IDs in that README. Rights holders are ManuWeave contributors under MIT; the [original license](../../examples/src/main/resources/templates/manuweave/MIT.txt) ships with example resources. Configuration records upstream relative paths and source-file SHA-256, without upstream executable code, tests, fonts, or official Word/LaTeX files. Export4j authored the renderer and fictional content. Four real Liberation Serif 2.1.5 variants were added; see the [font record (Chinese)](../../assets/fonts/README.md) for sources, OFL, and hashes. Callers may explicitly supply licensed original-font mappings. Examples neither search system fonts nor synthesize bold/italic. Font substitutions and unmapped formatting are reported per template; see the [template notes (Chinese)](../../examples/src/main/resources/templates/manuweave/README.md).

Math layout adds only the main `org.scilab.forge:jlatexmath:1.0.7` JAR to `examples` runtime configuration, disabling transitive font add-ons. It uses GPL-2.0-or-later with the original upstream linking exception. Built-in math fonts retain their separate terms; complete originals are in the [JLaTeXMath license directory](../../third-party/licenses/jlatexmath-1.0.7/). Upstream classes/fonts are unmodified. Example distribution `third-party/source/` includes the pinned sources JAR and [original build POM](../../third-party/jlatexmath-source-build/); Gradle verification metadata constrains versions, source archives, and binary hashes. This dependency does not enter library POMs, BOM, or library runtime SBOM. The adapter reads/writes native Word formulas using existing JDK ZIP/XML, without JLaTeXMath or Office. Office is used only for independent compatibility verification.

Root [LICENSE](../../LICENSE) uses the official full Apache text for project-authored portions. Third-party code/resources retain original declarations.

Root [NOTICE](../../NOTICE) collects original attribution from current runtime dependencies. Built JARs carry project LICENSE, NOTICE, and original third-party licenses. Example distributions additionally include unmodified dependency JARs.

Current `sbom` generates a CycloneDX 1.6 library-runtime inventory covering 16 project libraries and 13 third-party Java artifacts, with actual JAR SHA-256. Fields and font resources are registered separately under this document. Each batch must regenerate and verify the SBOM against final artifacts using production verifiers, without reusing previous hashes. Candidate inventories do not replace SBOMs.

XML template source and ordinary ZIP bundling reuse existing adapters and JDK ZIP/UTF-8 APIs without new modules or third-party dependencies. Examples retain registered Liberation Sans Regular bytes; original copyright/OFL text is saved in template ZIP `licenses/` entries. Images are generated by project examples. Independent inputs use Python standard-library ZIP and existing Pillow, neither entering Java runtime inventories.

Inline fields and note anchors reuse existing JDK physical-font shaping, layout, and drawing modules without added third-party dependencies. Project code generates images and reuses registered fonts. Independent verification uses existing Python, Poppler, and PDFium tools.

Common-authoring and stress examples `AuthoringExample`/`ReportStressExample` use the explicit fonts above. Code-generated checkerboard images use Apache-2.0. Final CID/TJ geometry checks and Windows process sampling use only registered tools/PowerShell, without new modules, third-party dependencies, or external assets.

Dense-form example `AdrFormExample` uses LXGW WenKai TC and Liberation Sans. Project code draws static checkmarks. Structural reference materials are not distributed; original PDFs, embedded fonts, and actual case data do not enter the repository/artifacts. Public examples use fictional cases only; independent checks reuse registered tools without new dependencies. See the [form probe (Chinese)](../verification/adr-form-probe.md) for scope.

General static forms and `InlineGraphic` use independently designed vector geometry, shared JDK shaping, and existing drawing paths. `StaticFormsExample` uses registered Liberation Sans and Noto Naskh Arabic; ADR retains its explicit fonts. JDK-generated new test images use Apache-2.0. No third-party dependencies or external assets were added. See [phase 16 (Chinese)](../verification/phase-16-static-forms.md).

Component semantic slots and `SemanticTable` are project implementations reusing existing Table measurement, fonts, and backends. New examples use registered fonts and JDK-generated PNG, without external assets. See [ADR-0021 (Chinese)](../adr/0021-component-semantic-insertions.md) for design and limits.

Semantic-table region continuation reuses existing column widths, text shaping, and complete-line splitting without new dependencies or external assets. Three new synthetic examples use Liberation Sans and JDK-generated icons. Fragment identities and font-region delegation are project implementations; see [ADR-0022 (Chinese)](../adr/0022-semantic-table-region-flow.md).

Semantic continuation of long merged cells reuses core table measurement and a project-developed grid allocator. `GridSemanticFlowExample` uses entirely fictional text, registered Liberation Sans regular/real bold, and code-generated blue PNG under Apache-2.0, without external images, fonts, dependencies, or competing-library code. See [ADR-0024 (Chinese)](../adr/0024-grid-semantic-cell-flow.md) for provenance and cached-header checks.

Single-pass long tables and semantic column/row spans reuse existing JDK, fonts, PDFBox/FontBox, and verification tools, without runtime dependencies. StreamingTableExample/CapacityExample sources contain synthetic records only. ColumnSpanExample/RowSpanExample PNGs are project-generated under Apache-2.0. Two-dimensional coverage, pagination, standard OOXML mappings, and tests are independently authored.

Phase 42 added no dependencies. Composite page-region examples generate logo PNGs on demand from project Java2D geometry inside XmlSectionsExample under the code's Apache-2.0. They verify offline resources, media relationships, and repeated page regions without external images or font scanning.
