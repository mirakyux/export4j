# Export4j

A Java Document Layout and Generation Engine  
Java 文档排版与生成引擎

**English** | [简体中文](README.zh-CN.md)

Export4j is an open-source Java engine for generating reports and structured documents. Write documents with a fluent Java API or offline XML templates, then use a shared layout engine to produce PDF, SVG and PNG. Separate adapters map the semantic model to a DOCX subset, self-contained HTML and CommonMark/GFM Markdown.

**Early development:** no stable artifacts have been published, and APIs may change. Supported PDF reports and dense static forms are ready for scoped trials; see the [readiness assessment](docs/readiness-assessment.md) and [capability matrix](docs/capabilities-api.md) for the evidence and limits.

- Java 21 LTS · Gradle Wrapper · Apache-2.0
- Explicit fonts and resources; no browser required for PDF generation
- Semantic model, layout and output backends remain separate

## Contents

- [Features](#features)
- [Getting started](#getting-started)
- [Java example](#java-example)
- [XML templates](#xml-templates)
- [HTML and Markdown export](#html-and-markdown-export)
- [Formats and limitations](#formats-and-limitations)
- [Roadmap](#roadmap)
- [Architecture and modules](#architecture-and-modules)
- [Documentation](#documentation)
- [Build and verification](#build-and-verification)
- [Contributing](#contributing)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## Features

| Area | Available capabilities |
| --- | --- |
| Document authoring | Fluent builders, headings, paragraphs, rich text, lists, reusable themes and named styles |
| Text and fonts | Explicit font groups and real variants, missing-glyph policies, OpenType shaping through the JDK, bidirectional text, justification and an optional strict CJK line-break subset |
| Layout | Automatic pagination, sections, headers and footers, page backgrounds, columns, rectangular text wrapping and page-flow components |
| Tables | Repeated headers, supported automatic column widths, row/column spans, explicit row splitting, multi-paragraph cells and two-level atomic nested tables |
| Images and graphics | Inline PNG/JPEG, vector paths, shapes, watermarks and a restricted offline SVG input subset |
| Navigation and forms | Contents, bookmarks, links, comments, page/reference fields, continued footnotes, static form components and four basic editable PDF field types |
| Templates | Offline XML with variables, conditions, loops, dynamic rows, themes, static page settings, image/table headers/footers, sections, flat includes and fields; strict lightweight Markdown |
| Output | PDF, self-contained SVG, PNG pages/ZIP, a semantic DOCX subset, HTML and Markdown |
| PDF processing | Bounded static-page inspection, text extraction, page selection and merge; AES-256 output, a PDF/A-2b subset and an external detached CMS signing callback |
| Large tables and execution | Single-pass text/rich-text table sources, synchronous page output, resource budgets, cancellation, timeouts, structured diagnostics and atomic path writes |

Each feature has explicit limits. A component table, semantic table and streaming table serve different contracts; see the [authoring guide](docs/authoring-api.md), [fluent API](docs/fluent-api.md) and [streaming-table guide](docs/streaming-table-api.md).

## Getting started

Requirements: **JDK 21**. The first build needs access to the Gradle distribution and Maven Central. A global Gradle installation is unnecessary.

```shell
git clone https://github.com/mirakyux/export4j.git
cd export4j
```

Windows PowerShell:

```powershell
.\gradlew.bat build
.\gradlew.bat :examples:authoring :examples:xmlTemplates :examples:production
```

Linux/macOS:

```sh
bash ./gradlew build
bash ./gradlew :examples:authoring :examples:xmlTemplates :examples:production
```

| Example | Output directory | Demonstrates |
| --- | --- | --- |
| `:examples:authoring` | `examples/build/authoring/` | Java authoring, typography and semantic DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML report templates and data binding |
| `:examples:xmlPageRegions` | `examples/build/xml-page-regions/` | Themed XML headers/footers and final page numbers, including contents |
| `:examples:xmlSections` | `examples/build/xml-sections/` | Image/table page bands, section paper/numbering and strict native DOCX round trip |
| `:examples:webExports` | `examples/build/web-exports/` | HTML/Markdown, embedded images and per-feature error/warn/ignore policies |
| `:examples:production` | `examples/build/production/` | Pagination, columns, wrapping, tables and multiple formats |
| `:examples:staticForms` | `examples/build/static-forms/` | Dense static forms and vector controls |
| `:examples:streamingTable` | `examples/build/streaming-table/` | Single-pass large-table output |

The examples use bundled, explicitly loaded fonts; no font download is required at runtime. The core libraries do not bundle the example fonts. See [font sources and licenses](assets/fonts/README.md).

There is no published stable Maven dependency to install yet. Build from source, or use the [isolated Maven bundle](docs/releasing.md) to evaluate the current snapshot with its BOM.

## Java example

This complete class uses the public authoring API. Run it from the repository root with the components and PDF-backend runtime dependencies on the classpath. The font paths refer to repository fixtures; applications provide their own licensed font resources.

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;

public class QuickStart {
    public static void main(String[] args) throws IOException {
        var fonts = FontGroup.strict(List.of(
                loadFont("LiberationSans-Regular.ttf"),
                loadFont("LXGWWenKaiTC-Regular.ttf")));
        var engine = DocumentEngine.builder(fonts).build();
        var document = Document.builder()
                .heading("Quarterly report / 季度报告", 1)
                .paragraph("Create a report with explicit fonts and automatic pagination.")
                .bullet("Text, tables and document structure remain explicit.")
                .table(List.of("Metric", "Value"),
                        List.of(List.of("Revenue", "120"), List.of("Cost", "80")))
                .build();

        var output = Path.of("build/readme-report.pdf");
        Files.createDirectories(output.getParent());
        engine.write(document, new PdfBackend(), output);
    }

    private static FontFace loadFont(String name) throws IOException {
        try (var input = Files.newInputStream(Path.of("assets/fonts", name))) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
```

The generated file is `build/readme-report.pdf`. `DocumentEngine` handles layout; `PdfBackend` renders the result. Font selection stays explicit, and unsupported glyphs fail under the strict policy.

For an executable Gradle example and more layouts, see [AuthoringExample](examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java) and the [authoring guide](docs/authoring-api.md).

## XML templates

Keep report structure in XML and supply data, fonts, images and output configuration through Java:

```xml
<document version="1" title="${title}">
  <page size="A4" margin="36"/>
  <heading level="1">${title}</heading>
  <each items="items" as="item">
    <p>${item.name}: ${item.result}</p>
  </each>
</document>
```

Templates compile to the existing document model. Resources are explicitly registered, all branches are checked at compile time, and the compiler does not fetch files or network resources. Unknown syntax and unsafe XML are rejected.

XML supports image/table headers and footers with independent themes and final page fields. Use `template.renderWithPageRegions(data)` for a uniform document, or `template.renderSections(data)` for per-section paper, numbering and inherited or overridden bands. Both work with `engine.export(document)` and native DOCX header/footer export. See [page regions](docs/xml-page-regions-api.md), [sections](docs/xml-sections-api.md) and [DOCX page regions](docs/docx-page-regions-api.md).

See the [XML guide](docs/xml-template-api.md), [page settings](docs/xml-page-settings-api.md), [report template](examples/src/main/resources/templates/report.xml) and [complete Java call](examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java).

## HTML and Markdown export

Add `com.mirakyux.export4j:export4j-adapter-web` with the project BOM. These adapters use the semantic model directly and need no browser to generate files:

```java
new HtmlAdapter().export(document).to(Path.of("build/report.html"));
var policy = ExportPolicy.builder()
        .defaultAction(UnsupportedAction.WARN)
        .on(ExportFeature.CUSTOM_BLOCK, UnsupportedAction.ERROR)
        .build();
var result = MarkdownAdapter.builder().policy(policy).build()
        .export(document).to(Path.of("build/report.md"));
```

Import these adapter types from `com.mirakyux.export4j.adapter.web`; `Path` is `java.nio.file.Path`. The default action is `ERROR`. Choose `WARN` for documented fallbacks with structured diagnostics, or `IGNORE` for the same fallback without a warning; override individual features as needed. Invalid input, limits, cancellation and I/O still fail. See the [complete support/fallback matrix](docs/web-export-api.md).

HTML preserves semantic styles, embedded images and merged/nested tables. Markdown exports CommonMark/GFM content, with explicit policies for unsupported geometry and table structure. Reader layout determines wrapping and pagination; some readers disable embedded data images or generated inline HTML. This output is separate from the strict Markdown template input grammar. Run `:examples:webExports` for both targets.

## Formats and limitations

| Format | Current scope |
| --- | --- |
| PDF | Primary report output, supported navigation/forms/security features, and a separate restricted static-page adapter |
| SVG / PNG | Static output from the shared display list; nonempty interactive data is rejected |
| DOCX | Explicit semantic subset, including supported text, tables, images, numbering, fields and default section headers/footers; unsupported content is rejected or reported according to policy |
| HTML | Semantic HTML5 with inline CSS, embedded images, merged/nested tables and explicit loss policies |
| Markdown output | CommonMark/GFM content; unsupported geometry/structure follows error/warn/ignore policies |
| XML / Markdown input | Offline authoring inputs with defined grammars and budgets; XML is not HTML/CSS |

- General Word documents are not guaranteed to round-trip without loss or paginate identically to PDF.
- Complex-text coverage, text extraction, search and selection depend on the tested scripts and readers. Full OpenType, Unicode segmentation and PDF/UA compliance are not claimed.
- PDF/A output needs independent compliance validation. Signing requires a caller-provided external signer; no private key is bundled.
- Fixed-size fields and atomic content must fit their reserved area. The engine does not silently shrink or clip unsupported content.
- Streaming layout is bounded, but the PDFBox page tree still grows. This is not a constant-memory guarantee for the entire PDF or a long-term leak-free claim.
- Resource limits are not a hostile-input sandbox. Callers remain responsible for access policy and untrusted-input isolation.

## Roadmap

The following areas remain open; they are not current features:

- Complete font-role/color cascading, additional page-region variants and XML background/foreground declarations.
- Continuous rich-text components and broader recursive table-cell pagination.
- Broader complex-script and reader interoperability, including search and text selection.
- A defined HTML/CSS input subset and a visual template designer.
- Stable public API and Maven Central distribution, with repeatable cross-platform release validation.

Priorities, partial implementations and acceptance criteria are maintained in the [capability gaps](docs/capability-gaps.md), [remaining plan](docs/remaining-capabilities-plan.md) and [roadmap](docs/roadmap.md). OCR is outside the current scope.

## Architecture and modules

```text
Document Model → Layout Engine → Layout IR → Display List → PDF / SVG / Raster
DOCX / OOXML  ↔ DOCX Adapter   ↔ Document Model
Document Model → Web Adapter → HTML / Markdown
```

The layout engine owns line breaking and pagination. Backends consume the final drawing instructions; the DOCX adapter works directly with document semantics.

| Modules | Responsibility |
| --- | --- |
| `export4j-model` | Semantic document model, styles and execution contracts |
| `export4j-font-core`, `export4j-text-core` | Font resources, metrics, shaping and text layout |
| `export4j-graphics-api`, `export4j-graphics-core` | Graphics contracts, display list and common drawing |
| `export4j-layout-model`, `export4j-layout-core` | Layout IR, measurement, regions and pagination |
| `export4j-components` | Fluent authoring, composed components and `DocumentEngine` |
| `export4j-backend-pdf`, `export4j-backend-svg`, `export4j-backend-raster` | Output backends |
| `export4j-adapter-docx`, `export4j-adapter-template`, `export4j-adapter-pdf`, `export4j-adapter-web` | Format and template adapters |
| `export4j-bom`, `examples` | Version alignment and executable examples |

The Java root package and Gradle group are `com.mirakyux.export4j`. See the [architecture](docs/architecture.md) and [ADRs](docs/adr/README.md) for dependency boundaries and design decisions.

## Documentation

Most detailed guides are currently written in Chinese.

| Topic | Guides |
| --- | --- |
| Start here | [Authoring](docs/authoring-api.md) · [Fluent API](docs/fluent-api.md) · [Themes](docs/themes-api.md) |
| Capabilities | [Capability matrix](docs/capabilities-api.md) · [Readiness](docs/readiness-assessment.md) |
| Text and layout | [Fonts/text](docs/text-api.md) · [Layout](docs/layout-api.md) · [Continuous columns](docs/continuous-columns-api.md) |
| Tables | [Semantic tables](docs/table-api.md) · [Block cells](docs/block-cells-api.md) · [Streaming tables](docs/streaming-table-api.md) |
| Forms and navigation | [Static forms](docs/static-forms-api.md) · [Editable forms](docs/form-styles-api.md) · [Fields](docs/inline-fields-api.md) |
| Templates | [XML](docs/xml-template-api.md) · [Markdown](docs/markdown-template-api.md) · [XML themes](docs/xml-themes-api.md) |
| Adapters | [HTML/Markdown export](docs/web-export-api.md) · [DOCX page regions](docs/docx-page-regions-api.md) · [DOCX fields](docs/docx-fields-api.md) · [Static PDF](docs/pdf-adapter-api.md) |
| Engineering | [Development](docs/development.md) · [Verification records](docs/verification) · [Release process](docs/releasing.md) |

## Build and verification

`build` aggregates module builds, JUnit tests, Javadoc and source JARs. Tool versions and resolved dependencies are fixed in the version catalog, Wrapper configuration, dependency locks and verification metadata.

```powershell
.\gradlew.bat build sbom publishLocalBundle
powershell -NoProfile -File scripts/verify_local_consumer.ps1
```

`publishLocalBundle` writes the libraries and BOM to `build/repository`. The isolated consumer resolves these Maven artifacts and actually generates documents. This does not publish to Maven Central or modify the user's Maven repository.

Independent format and visual checks use Python 3.11+, the pinned [verification requirements](scripts/requirements-verification.txt) in an isolated environment, and Poppler. Some checks additionally need veraPDF, OpenSSL or Word. Select checks for the affected features from the [verification matrix](.agents/skills/develop-export4j/references/verification.md); do not update visual baselines just to obtain a passing result.

The GitHub Actions workflow is manually triggered. Its configured checks cover only part of the full acceptance process; no hosted CI result is claimed here.

## Contributing

Bug reports, reproducible samples, tests, documentation and focused feature contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md), the [development rules](docs/development.md) and [AGENTS.md](AGENTS.md) before making changes. Shared project skills live in [.agents](.agents/README.md).

Use [GitHub Issues](https://github.com/mirakyux/export4j/issues) for bugs and proposals. Report security issues privately through [GitHub private vulnerability reporting](https://github.com/mirakyux/export4j/security/advisories/new); see [SECURITY.md](SECURITY.md).

## Acknowledgments

Export4j builds on open-source infrastructure and explicitly supplied font resources:

| Project / resource | Use |
| --- | --- |
| [Apache PDFBox / FontBox](https://pdfbox.apache.org/) | PDF backend, restricted PDF processing and font parsing |
| [Apache Commons Logging](https://commons.apache.org/proper/commons-logging/) | Transitive PDFBox runtime dependency |
| [OpenJDK](https://openjdk.org/) | Java runtime, Java2D/OpenType, ZIP and XML infrastructure |
| [Gradle](https://gradle.org/), [JUnit](https://junit.org/) | Build and Java tests |
| [pypdf](https://github.com/py-pdf/pypdf), [pypdfium2](https://github.com/pypdfium2-team/pypdfium2), [Pillow](https://python-pillow.org/), [python-docx](https://github.com/python-openxml/python-docx), [cryptography](https://cryptography.io/), [Poppler](https://poppler.freedesktop.org/) | Independent format, rendering and verification tools |
| [Liberation Fonts](https://github.com/liberationfonts/liberation-fonts), [LXGW WenKai TC](https://github.com/lxgw/LxgwWenkaiTC), [ZCOOL XiaoWei](https://github.com/googlefonts/zcool-xiaowei) | Explicit example/test fonts |
| [Noto Fonts](https://github.com/notofonts/noto-fonts), [Source Sans 3](https://github.com/adobe-fonts/source-sans) | Complex-script and CFF example/test fonts |

See the [dependency record](docs/dependencies.md), [font provenance](assets/fonts/README.md), [NOTICE](NOTICE) and [original licenses](third-party/licenses) for versions, hashes and distribution obligations.

## License

Project-authored code and documentation are licensed under [Apache License 2.0](LICENSE). Third-party dependencies, fonts and other resources retain their original licenses; bundling them does not relicense them under Apache-2.0.
