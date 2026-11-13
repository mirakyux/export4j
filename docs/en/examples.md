# Examples

Run Gradle tasks from the repository root. Windows uses `.\gradlew.bat`; Linux/macOS use `bash ./gradlew`. JDK 21 is required, and the first build downloads pinned dependencies. Examples explicitly load repository fonts; see [font records (Chinese)](../../assets/fonts/README.md) for sources/licenses.

## Common tasks

```powershell
.\gradlew.bat :examples:authoring :examples:xmlTemplates :examples:production
```

Select tasks relevant to your scenario rather than running every example. Output paths below are relative to the repository root.

| Task | Output | Demonstrates |
| --- | --- | --- |
| `:examples:authoring` | `examples/build/authoring/` | Java authoring, text layout, semantic DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML report templates and data binding |
| `:examples:manuWeave` | `examples/build/manuweave/` | All 286 ManuWeave academic profiles, selectable by Profile ID, with PDFs and actual-format/difference reports |
| `:examples:manuWeaveMath` | `examples/build/manuweave-math/` | Eight categories of PDF/SVG vector formulas and native editable Word math |
| `:examples:xmlTemplateBundle` | `examples/build/xml-template-bundle/` | Edit/save/reopen XML/resources in ordinary ZIPs and bind different data |
| `:examples:recoveryClosure` | `examples/build/recovery-closure/` | Combined navigation/footnotes/endnotes/sections/layers/tables/images recovery, actual editing, and lossy repeated rounds |
| `:examples:xmlModelSaving` | `examples/build/xml-model-saving/` | Save semantic models as XML, reopen/edit/export, three no-edit controls, explicit loss reports |
| `:examples:xmlRegionSaving` | `examples/build/xml-region-saving/` | Shared regions, variants/layers, save/reopen/edit controls |
| `:examples:xmlCrossReferenceSaving` | `examples/build/xml-cross-reference-saving/` | Three-round reference saving, cross-section numbering, target rewriting, Web losses |
| `:examples:xmlSemanticSaving` | `examples/build/xml-semantic-saving/` | Full footnote/endnote/caption/text-link binding, three-round controls, actual caption editing, Web losses |
| `:examples:pageOverlays` | `examples/build/page-overlays/` | XML background/foreground, visible occlusion, explicit conversion losses |
| `:examples:xmlPageRegions` | `examples/build/xml-page-regions/` | XML header/footer themes and final page numbers including contents |
| `:examples:xmlSections` | `examples/build/xml-sections/` | Image/table regions, section paper/page numbers, strict native DOCX round trips |
| `:examples:webExports` | `examples/build/web-exports/` | HTML/Markdown, embedded images, per-feature error/warn/ignore |
| `:examples:documentConversion` | `examples/build/document-conversion/` | Import/edit, actual loss preflight, four-format output |
| `:examples:pdfObjectEditing` | `examples/build/pdf-object-editing/` | Passive fields/objects, relationship remapping during selection/merge |
| `:examples:pdfContentEditing` | `examples/build/pdf-content-editing/` | Actual text replacement and region redaction into a new image-only PDF |
| `:examples:pdfSemanticImport` | `examples/build/pdf-semantic-import/` | Recover headings, explicit tables, images, then edit/convert |
| `:examples:production` | `examples/build/production/` | Pagination, columns, wrapping, tables, multiple formats |
| `:examples:staticForms` | `examples/build/static-forms/` | Dense static forms and vector controls |
| `:examples:adrReport` | `examples/build/adr-report/java.pdf` | Three-page individual case report from Java semantics |
| `:examples:adrReportXml` | `examples/build/adr-report/xml.pdf` | XML-template case report with the same data/layout |
| `:examples:commonDocuments` | `examples/build/common-documents/` | Contract, résumé, manual, quotation, minutes, weekly report; PDF/XML/JSON plus images/licenses |
| `:examples:streamingTable` | `examples/build/streaming-table/` | Forward-only long-table output |
| `:examples:variableTables` | `examples/build/variable-tables/` | Semantic rows at actual column widths, span owners, atomic nested cells |
| `:examples:variableSplitTables` | `examples/build/variable-split-tables/` | Inline cells continue across variable-width columns at complete lines, preserving final fields/images/links |
| `:examples:verifyPdfSignatures` | `examples/build/pdf-signature-verification/` | Explicit offline CMS verification, distinguishing unsigned append from signed-byte tampering |

## ManuWeave academic templates

```powershell
# Generate all 286 profiles:
.\gradlew.bat :examples:manuWeave
# List templates or generate selected profiles:
.\gradlew.bat :examples:manuWeave --args="--list"
.\gradlew.bat :examples:manuWeave --args="acm-sigconf ieee-conference zh-master"
```

The [entry point](../../examples/src/main/java/com/mirakyux/export4j/examples/ManuWeaveExample.java) reads the pinned [template catalog (Chinese)](../../examples/src/main/resources/templates/manuweave/README.md). Shared fictional academic content demonstrates each profile's paper, margins, headings, abstracts, body, columns, figures, tables, and references. All Profile IDs correspond to the pinned [ManuWeave README](https://github.com/jiassia/manuweave/blob/e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b/README.md); runtime never accesses the upstream repository.

Each profile writes `<id>.pdf`, `<id>-report.xml`, and `<id>-source.txt`. Reports record the source, actual fonts/layout parameters, defaults, substitutions, and unrepresentable items. Examples use authorized pinned repository fonts rather than bundling every upstream font. These PDFs demonstrate Export4j layout without promising journal-submission compliance, execution of LaTeX classes, or lossless Word-template reproduction. See [template notes (Chinese)](../../examples/src/main/resources/templates/manuweave/README.md) for limits, licenses, and independent commands.

Templates use real serif/sans-serif bold/italic variants, keep headings with adjacent content, move figures/tables with captions, apply 2/2 widow/orphan control, and balance final columns. Profiles with explicit source requirements include review line numbers, odd/even regions, and mirrored margins. References use limited formats selected from source facts, without external CSL or publisher-class execution.

Use `--font-map <Properties XML>` to provide authorized original fonts; paths and real-variant requirements are in the [template notes (Chinese)](../../examples/src/main/resources/templates/manuweave/README.md). Independent structure and dual-rendering results are in [acceptance records (Chinese)](../verification/manuweave-templates.md).

## Mathematical formulas

```powershell
.\gradlew.bat :examples:manuWeaveMath
```

The [math example](../../examples/src/main/java/com/mirakyux/export4j/examples/ManuWeaveMathExample.java) generates fractions/radicals, scripts, integrals, sums, matrices, piecewise expressions, Greek/trigonometry, and limits/products. `math.pdf`/`math.svg` use explicit Computer Modern math fonts as vector paths and retain original TeX through ActualText. Formulas lay out atomically at natural dimensions, without shrinking or rasterizing to fit columns.

`math.docx` contains eight standard native OMML equations. `math-edited.docx` changes the first denominator from `2a` to `3a`, preserving others. `math-standard-round-1.docx` through `math-standard-round-3.docx` verify three rounds without project-private metadata. Native Word math APIs require neither a math library nor Office; see [Word math](docx-math-api.md) for supported types.

JLaTeXMath 1.0.7 is limited to `examples`, with no TeX installation. See [dependencies](dependencies.md) for licensing, linking exception, font terms, and source distribution. Only explicitly listed TeX commands/matrix environments are allowed; macros, external links, unknown commands, and excess budgets are rejected. Equivalent content is authored separately for the two outputs; there is no automatic TeX↔OMML converter.

```powershell
python scripts/verify_manuweave_math.py
# With local Word, edit/save/reopen in a separate background instance:
powershell -NoProfile -File verification/verify_docx_math_word.ps1 -InputPath examples/build/manuweave-math/math.docx -OutputDirectory build/verification/manuweave-math-word
.\gradlew.bat :examples:manuWeaveMath --args="--verify-word-saved build/verification/manuweave-math-word/word-saved.docx"
python scripts/verify_manuweave_math.py --word-directory build/verification/manuweave-math-word
```

Verification uses the isolated Python environment and pinned dependencies. Ordinary style/theme losses after Word saves are reported individually; math types/settings are checked separately and strictly. Application automation and manual UI acceptance are distinguished in the [records (Chinese)](../verification/manuweave-templates.md).

## Individual case report: Java and XML

```powershell
.\gradlew.bat :examples:adrReport :examples:adrReportXml
```

Both approaches produce the same layout: A4, patient/history information, an 18-column medication table, two-level merged headers, reaction history, laboratory findings, reporter information, notes, and dynamic page numbers. Pagination follows content length, widths, spacing, and padding without explicit page breaks. Default fictional data makes three pages, retaining notes on page three. Text/data changes affect the page count.

- [Java source](../../examples/src/main/java/com/mirakyux/export4j/examples/AdrReportExample.java) directly builds semantic tables, paragraphs, and footers without XML.
- The [template entry](../../examples/src/main/java/com/mirakyux/export4j/examples/AdrReportXmlExample.java) compiles an [XML template](../../examples/src/main/resources/templates/adverse-reaction.xml), binds data, and preserves footers.
- [Shared data/fonts](../../examples/src/main/java/com/mirakyux/export4j/examples/AdrReportData.java) supplies fictional cases and explicit fonts. Modify patients, drugs, reaction history, or investigations in `data()` to compare results.

These examples demonstrate dense-form authoring/layout only. Cases, drugs, organizations, and dates are fictional and unsuitable for actual submissions. No original PDF, designer service, or Office is needed. Fonts differ from the original form, so similar structure/content height does not promise pixel identity.

Independent structure and dual-render checks are configured. Prepare the [verification environment](development.md), then run:

```powershell
python scripts/verify_adr_report.py
```

Older `:examples:adrForm` remains a capability probe for merged cells, static controls, and long-cell pagination under `examples/build/adr-form/`, separate from these naturally paginated business examples.

## Common document templates

```powershell
.\gradlew.bat :examples:commonDocuments
# Generate only one document, for example the résumé:
.\gradlew.bat :examples:commonDocuments --args="resume"
```

The [entry point](../../examples/src/main/java/com/mirakyux/export4j/examples/CommonDocumentsExample.java) binds Java data to XML and outputs PDF plus XML, JSON, and referenced `images/`/`licenses/`. Each example shares a basename under `examples/build/common-documents/`. Counts below use default data; edited content reflows naturally.

| Document | Template/data | Default pages | Demonstrates |
| --- | --- | --- | --- |
| Service contract | [XML](../../examples/src/main/resources/templates/common/service-contract.xml) · [JSON](../../examples/src/main/resources/templates/common/service-contract.json) | 7 | Per-page logo/company/version headers, 12 chapters, delivery/acceptance matrix, milestones, fees/installments, SLA, data processing, changes, annexes, two-column signatures |
| Résumé | [XML](../../examples/src/main/resources/templates/common/resume.xml) · [JSON](../../examples/src/main/resources/templates/common/resume.json) | 2 | Photo/name row, achievements, primary/secondary columns, skills, work experience, three problem/solution/result cards, education/certification |
| User manual | [XML](../../examples/src/main/resources/templates/common/user-manual.xml) · [JSON](../../examples/src/main/resources/templates/common/user-manual.json) | 8 | Nine chapters/appendix, installation/workflows, permissions, parameters, 18 troubleshooting records, maintenance/recovery, conditional-style checklist |
| Quotation | [XML](../../examples/src/main/resources/templates/common/quotation.xml) · [JSON](../../examples/src/main/resources/templates/common/quotation.json) | 4 | 22 grouped services, two-level headers, subtotals/tax, optional discounts, phases, SLA, commercial boundaries, confirmation |
| Meeting minutes | [XML](../../examples/src/main/resources/templates/common/meeting-minutes.xml) · [JSON](../../examples/src/main/resources/templates/common/meeting-minutes.json) | 3 | Participant roles, five detailed topics, three alternatives, four decisions, 14 assigned actions, risks, open issues, attachments |
| Weekly report | [XML](../../examples/src/main/resources/templates/common/weekly-report.xml) · [JSON](../../examples/src/main/resources/templates/common/weekly-report.json) | 4 | Health/trends, milestones, four nested workstream cards, budget/resources, risks/dependencies, issues/changes, management decisions |

Java data is in [PersonalDocumentData](../../examples/src/main/java/com/mirakyux/export4j/examples/PersonalDocumentData.java) and [OfficeDocumentData](../../examples/src/main/java/com/mirakyux/export4j/examples/OfficeDocumentData.java); JSON is designer test data. Contract fees/installments are prefilled, independently checked sample values. Quotation details, grouped subtotals, tax, and discounts are calculated with Java `BigDecimal` before binding. XML does not compute business amounts; designer changes to quantity, unit price, or tax rates require corresponding totals to be updated. Synchronize JSON after Java data changes so both entry points share content.

Templates demonstrate multilevel headers, horizontal/vertical spans, repeated headers, rich roles/colors, two-column block cells/nested tables, numbered steps, conditional content/styles, fixed regions, and final numbers. The default set totals 28 pages, naturally paginated. Local groups such as résumé education/projects and manual troubleshooting records stay together. Edit long clauses, add details, or toggle test-data booleans to observe continuation and conditional styling.

The six templates are also available through the designer's **New → From example**, sharing these resources. Both paths explicitly use Liberation Sans and LXGW WenKai TC. Text/layout are original; biographies, organizations, products, dates, and amounts are fictional. The résumé photo is Joon Tae Kim's Pexels work; the contract logo is a project-drawn fictional Aoki mark. Images and [licenses (Chinese)](../../examples/src/main/resources/templates/common/assets/README.md) are bundled locally, with no network access during generation/preview. The contract only demonstrates layout/binding.

The résumé references `resume_portrait` at 64 × 80 pt. The contract uses `contract_logo` at 25 × 25 pt in every header. `${contract.version}` and `${contract.number}` appear alongside it and update on every page with test data. Designer examples pre-register images; full ZIPs include images/licenses. XML-only loading needs matching images in the resource panel. Java compilation also registers explicitly:

```java
var template = XmlTemplate.builder()
        .image("resume_portrait", ImageResource.load(Files.readAllBytes(
                Path.of("examples/build/common-documents/images/resume_portrait.png"))))
        .build()
        .compile(Files.readString(Path.of("examples/build/common-documents/resume.xml")));
```

Document types/general structures were informed by [Microsoft Word template categories](https://word.cloud.microsoft/create/en/templates/), [Square contracts](https://squareup.com/us/en/contracts), [Smartsheet quotations](https://www.smartsheet.com/price-quote-templates), [meeting minutes](https://www.smartsheet.com/content/project-management-meeting-minutes-templates), [status reports](https://www.smartsheet.com/content/project-status-templates), and [Folge manual structure](https://folge.me/user-manual-template). The repository copies none of their clauses, template files, or brand designs. Photo provenance is separate above.

Run `python scripts/verify_common_documents.py` to check complete/unique content, record order, repeated multilevel headers, counts/numbers, fonts, nested-cell bounds, amounts, image pixels/positions/repetitions, resource licenses, and every page's dual rendering. Add `--samples service-contract resume` to check only those two image/text templates.

## Visual designer and business scenarios

`:export4j-designer:run` starts the optional HTML UI. `:export4j-designer:scenarios` generates five XML/ZIP/JSON scenarios without a UI, including source edits and save/reopen checks. Common document templates use `:examples:commonDocuments` separately. Default `export4j-designer/build/scenarios` must not already exist; repeated runs can choose fresh `-PdesignerOutputDirectory=build/<new-directory>`.

| Scenario | Main behavior | Pages in this batch |
| --- | --- | --- |
| Inspection report | Variables, conditions, loops, images, source-data editing | 1 |
| Dense static checklist | 14-row table, static checks, review space | 1 |
| Paginated inventory | 120 rows, repeated headers, unique ordered body | 6 |
| Contents/cross-references | Automatic contents, heading navigation, captions, final references | 3 |
| Adverse-reaction form | 18-column merged headers, binding, natural pagination | 3 |

Each outputs six formats. `report.json` records actual encoded size, duration, observed heap, and loss counts, with 11 failure controls. Run independent `python scripts/verify_designer.py`, using `--source` for nondefault inputs. See [designer](designer.md) for fonts/budgets and [phase 108 (Chinese)](../verification/phase-108-designer-and-business-scenarios.md) for costs/all-page evidence.

## Native DOCX and HTML/CSS layout

These integrated samples live in the independent Maven consumer and consume local coordinates. First run `build sbom publishLocalBundle`, then choose a new `<validation-directory>` each time:

```powershell
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<validation-directory>" build docxSemanticComplete htmlCssLayout resolvedLibraries --refresh-dependencies --no-daemon --console=plain
```

Outputs are `docx-semantic-complete/` and `html-css-layout/` under `verification/consumer/build/<validation-directory>/output/`. Generation does not establish Word/browser compatibility; independent/application steps are in [DOCX acceptance (Chinese)](../verification/phase-103-native-docx-semantic-editing.md) and [CSS acceptance (Chinese)](../verification/phase-104-html-css-layout.md).

## Choose APIs and verification

- For complete Java calls, start with [example sources](../../examples/src/main/java/com/mirakyux/export4j/examples) and their guides. Tasks follow the actual [Gradle configuration](../../examples/build.gradle.kts).
- For input scope, rejection, or losses, consult the [documentation index](README.md) and corresponding APIs. One sample does not establish full-format support.
- To reproduce historical results, consult [acceptance records (Chinese)](../verification) for versions, commands, and boundaries. Generated outputs/raw logs stay in build directories.
