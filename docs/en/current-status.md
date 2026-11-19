# Versions and current status

This page separates the published candidate, development source, and historical verification. Use the [documentation index](README.md) for API guides and the [capability matrix](capabilities-api.md) for exact boundaries.

## Choose a version

| Version | Where to obtain it | Before using it |
| --- | --- | --- |
| `0.1.0-rc.3` | Maven Central, 16 libraries and the BOM | A published release candidate; no stable release yet. See the [RC3 notes](releases/0.1.0-rc.3.md) |
| `develop` / `0.1.0-SNAPSHOT` | Build from source; the default local repository is `build/repository/` | Currently 18 libraries and the BOM. Added modules are absent from published RC3; see [local build and consumption](releasing.md#local-verification) |
| `0.0.0-verification-*` | The isolated local repository named in a verification record | Identifies one tested artifact batch and its hashes; it is not a release installable from Central |

Java 21 is required. A BOM aligns modules from the same batch; do not combine development modules with the RC3 BOM. The optional local `export4j-designer` application and `examples` project are excluded from library publication and the BOM.

## Added in development

These features are in the development source and are outside the RC3 publication scope.

| Need | Development entry | Conditions and evidence |
| --- | --- | --- |
| Configure fonts/styles once and export PDF/DOCX | [`export4j-starter` / `Export4j`](easy-export-api.md) | Fonts/resources stay explicit; the XML shortcut accepts body templates only. [Verification (Chinese)](../verification/easy-export.md) |
| Pass DTOs, records, or JavaBeans directly | [`DocumentTemplate.render(Object)` / `Export4j.xml(String, Object)`](xml-template-api.md#java-objects) | Map methods remain available; record components/public getters and nested Lists/Maps share budgets. Preprocess nulls, dates, and other unsupported values. [Verification (Chinese)](../verification/easy-export.md#2026-11-14-dto-record-绑定补齐) |
| Declare per-block fonts, colors, parsing, and inherited defaults | [Source content blocks](content-blocks-api.md) | Java/XML select text, Markdown, or HTML explicitly, with formula/table switches. [Verification (Chinese)](../verification/content-blocks.md) |
| Recognize formulas and compile format-specific representations | [`export4j-math`](math-api.md) and [mixed content](rich-content-api.md) | Bounded TeX/Markdown, vector PDF and native Word mathematics; no OCR or full LaTeX execution. [Verification (Chinese)](../verification/rich-content.md) |
| Reuse fonts across exports and release idle entries | [Font caching](font-cache-api.md) | Bounded caches and parser leases, separate from PDFBox's system-font disk cache. [Lifecycle verification (Chinese)](../verification/font-cache.md) |

The [browser playground](documentation-site.md#browser-template-playground) edits XML/JSON with an approximate preview. Java object binding runs in the JVM. Use the [local designer](designer.md) for Java-engine previews, resource bundles, and exports.

## Limits to check before integration

- Aggregate formula-text fonts above 64 MiB still have an error-classification defect. An explicitly configured `TEXT` candidate can swallow this budget failure; the default empty fallback list still fails strictly. See [known limits (Chinese)](../verification/easy-export.md#已知限制).
- The starter has no arbitrary named font-role registration entry. Use the underlying `DocumentEngine.fontRole(...)` when required; do not depend on internal `family_N` names.
- PDF, DOCX, and HTML support is defined per entry point. DOCX and PDF pagination can differ. Business migrations still require verification with actual templates, fonts, images, and capacity settings.

See the [capability gaps](capability-gaps.md) for pending platform coverage, stable API/version work, and broader input compatibility. The [execution plan (Chinese)](../remaining-capabilities-plan.md) defines their order.

## Read verification records

The object-binding batch recorded on 2026-11-14 passed 2,714 Java tests, four isolated-consumer routes, and independent checks of 35 PDFs and 30 DOCX files. See [that batch's record (Chinese)](../verification/easy-export.md#2026-11-14-dto-record-绑定补齐). These figures describe its source and local artifacts, not later commits or a remote publication.

The [verification index](verification/README.md) connects topics to evidence. Stage records retain their original dates, inputs, tools, and limits. [ADRs (Chinese)](../adr/README.md) explain decisions; acceptance of a design does not prove complete output verification. Central publication, website deployment, and search-engine indexing are separate results.
