# Export4j

A Java Document Layout and Generation Engine  
Java 文档排版与生成引擎

**English** | [简体中文](README.zh-CN.md)

Export4j is an open-source **Java PDF generation and document export library** for reports, forms and structured documents. Create PDF, DOCX, HTML, Markdown, SVG and PNG using a fluent Java API or XML templates.

Import supported PDF, DOCX, HTML and Markdown content, edit it, and convert between formats with explicit policies for incompatible features. Save reusable XML templates and reopen supported document models for further editing.

**Early development:** no stable release has been published, and APIs may change. See the [format limits](#formats-and-limitations) and [capability matrix](docs/capabilities-api.md) before integrating.

## Contents

- [Features](#features)
- [Getting started](#getting-started)
- [HTML template designer](#html-template-designer)
- [Java example](#java-example)
- [XML templates](#xml-templates)
- [HTML and Markdown export](#html-and-markdown-export)
- [Formats and limitations](#formats-and-limitations)
- [Roadmap](#roadmap)
- [Documentation](#documentation)
- [Build and verification](#build-and-verification)
- [Contributing](#contributing)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## Features

| Area | Capabilities |
| --- | --- |
| Reports and forms | Headings, rich text, lists, images, themes, headers/footers, page numbers, contents, bookmarks, links and static/editable forms |
| Pagination and tables | Sections, columns, floating content, repeated table headers, merged cells, nested tables and cross-page continuation |
| Import, editing and conversion | PDF recovery, DOCX semantic and package-preserving edits, HTML/CSS and Markdown import; loss previews and error/warn/ignore policies |
| Templates and reuse | XML variables, conditions, loops and includes; editable XML/resource ZIPs, an optional HTML template designer and document-model recovery |
| Text and graphics | Explicit fonts, complex text, vector graphics, CMYK/ICC/spot colors, transparency and gradients; optional native OpenType for variable/color fonts and vertical text |
| PDF processing | Page/object edits, composition, rendering, image extraction, optimization, text/image replacement, two redaction modes, form data and a separate XFA subset |
| PDF security and standards | AES-256, selected PDF/A profiles, PDF/UA-1 and PAdES B/T/LT/LTA, with explicit keys, trust and validation evidence |
| Large documents | Streaming tables, explicit replay for totals/references, optional disk-indexed PDF output, execution budgets, cancellation and atomic file writes |

Support applies to the documented input subsets. See the [API guides](docs/README.md) for configuration and examples.

## Getting started

Requirements: **JDK 21**. Document generation does not require Office or a browser. The first build needs access to the Gradle distribution and Maven Central; the repository includes the Gradle Wrapper.

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
| `:examples:authoring` | `examples/build/authoring/` | Java authoring and semantic DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML reports and data binding |
| `:examples:production` | `examples/build/production/` | Pagination, columns, tables and multiple formats |
| `:examples:documentConversion` | `examples/build/document-conversion/` | Import, edit, preview losses and export |
| `:examples:staticForms` | `examples/build/static-forms/` | Dense static forms |
| `:examples:streamingTable` | `examples/build/streaming-table/` | Single-pass large tables |

More runnable tasks are listed in the [example index](docs/examples.md). Examples load bundled fonts; applications supply their own licensed fonts. See [font sources and licenses](assets/fonts/README.md). The optional [native OpenType module](docs/native-opentype-api.md) also requires the target platform's native artifacts.

There is no stable Maven release to install yet. Build from source, or use the [local Maven bundle and BOM](docs/releasing.md) to evaluate the current snapshot.

## HTML template designer

Run `./gradlew :export4j-designer:run` (`.\gradlew.bat` on Windows), then open the local address shown in the terminal. This optional app has an IDE-style structure/resource explorer, editor tabs, and property inspector. It supports page settings, rich text, typed properties, and highlighted XML/JSON editing. Changes trigger a live Java-engine preview; the continuous canvas supports whole-list selection, multiple selection and drag, shared-property edits, contextual actions, and image resizing. Undo/redo, template ZIP save/reopen, and six export formats with loss previews remain available. See the [designer guide](docs/designer.md), [business scenarios](docs/verification/phase-108-designer-and-business-scenarios.md), [editing verification](docs/verification/phase-109-designer-editing-controls.md) and [canvas verification](docs/verification/phase-110-designer-canvas-and-interactions.md).

## Java example

This complete class uses the public authoring API. Run from the repository root with the components and PDF-backend runtime dependencies on the classpath. The font paths refer to repository fixtures.

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

The result is `build/readme-report.pdf`. For a Gradle task that runs without setting up a classpath manually, use `:examples:authoring`; see [AuthoringExample](examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java) and the [authoring guide](docs/authoring-api.md).

## XML templates

Describe the document in XML and supply data, fonts, images and output settings from Java:

```xml
<document version="1" title="${title}">
  <page size="A4" margin="36"/>
  <heading level="1">${title}</heading>
  <each items="items" as="item">
    <p>${item.name}: ${item.result}</p>
  </each>
</document>
```

Templates support variables, conditions, loops, tables, sections, page bands and includes. Save XML sources and resources in an ordinary ZIP, or save supported Java models as editable XML. See the [XML guide](docs/xml-template-api.md), [template bundles](docs/xml-template-bundle-api.md), [model saving](docs/xml-model-saving-api.md) and [complete Java example](examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java).

[Document-model recovery](docs/model-recovery-api.md) is optional: use a companion XML/resource bundle or embed it in a supported output. Recovery data increases file size, and external edits may invalidate or remove it. The guide explains binding checks, compatibility and conversion losses.

## HTML and Markdown export

Use `export4j-adapter-web` with the project BOM. In the following snippet, `document` is a semantic document such as the one in the Java example. Import the adapter types from `com.mirakyux.export4j.adapter.web` and `Path` from `java.nio.file`.

```java
new HtmlAdapter().export(document).to(Path.of("build/report.html"));
var policy = ExportPolicy.builder()
        .defaultAction(UnsupportedAction.WARN)
        .on(ExportFeature.CUSTOM_BLOCK, UnsupportedAction.ERROR)
        .build();
var result = MarkdownAdapter.builder().policy(policy).build()
        .export(document).to(Path.of("build/report.md"));
```

The default action is `ERROR`. `WARN` applies the documented fallback and returns diagnostics; `IGNORE` applies the same fallback without a warning. Invalid input, exceeded limits, cancellation and I/O failures still fail. See the [support and fallback matrix](docs/web-export-api.md).

HTML preserves supported styles, embedded images and merged/nested tables. Markdown targets CommonMark/GFM. Their readers determine wrapping and pagination. Run `:examples:webExports` to generate both formats; see [shared import/edit/export](docs/document-conversion-api.md) for conversion.

## Formats and limitations

| Format | Import / reading | Output / editing |
| --- | --- | --- |
| PDF | Bounded parsing and semantic recovery | Generation, page/object editing, composition, rendering, optimization and redaction |
| DOCX | Typed OOXML subset | Semantic export/editing; package-preserving text edits |
| HTML/CSS | Offline semantic or explicit CSS-layout subset | Self-contained semantic HTML5 |
| Markdown | CommonMark/GFM subset; separate strict template syntax | CommonMark/GFM content |
| SVG / PNG | Image/vector resources within their respective supported subsets | Static pages and PNG page ZIPs |

- Formats preserve different information. Unsupported features follow explicit loss policies; general Word round trips and identical DOCX/PDF pagination are not guaranteed.
- PDF recovery infers editable structure from text, coordinates and drawings. It reports uncertainty, accepts explicit table grids and does not perform OCR. Font requirements and image fallbacks are described in the [recovery guide](docs/pdf-semantic-import-api.md).
- [PDF redaction](docs/pdf-redaction-api.md) offers rasterization and selective reconstruction. The first loses searchable text and vectors; the second preserves supported content outside selected regions. Callers identify all sensitive regions.
- Complex text, search and selection depend on the documented font/script/reader coverage. PDF/A and PDF/UA require their specific output paths and final-file validation; signing requires caller-owned keys and trust evidence.
- Streaming layout and optional disk-indexed PDF output have separate resource contracts. The ordinary PDF backend retains its page tree; finite capacity tests do not imply unlimited documents or indefinite operation without leaks. See [streaming and replay](docs/streaming-replay-api.md).
- Fixed-size content must fit its reserved area. Resource budgets do not replace isolation for untrusted input.

## Roadmap

The remaining work is tracked in the [capability gaps](docs/capability-gaps.md):

- **GAP-14.2:** broader compatibility corpus and application/reader checks across platforms.
- **GAP-14.3:** stable API/release, official signing and Maven Central distribution.
- **GAP-22:** additional formats, including XLSX, PPTX, EPUB and XPS.

See the [execution plan](docs/remaining-capabilities-plan.md) for acceptance criteria. OCR is outside the current scope.

## Documentation

The [documentation index](docs/README.md) groups guides by task. Most detailed guides are currently written in Chinese.

| Task | Start here |
| --- | --- |
| Author documents | [Authoring](docs/authoring-api.md) · [Fluent API](docs/fluent-api.md) · [Themes](docs/themes-api.md) |
| Use templates | [XML](docs/xml-template-api.md) · [Template bundles](docs/xml-template-bundle-api.md) |
| Import, edit and convert | [Shared conversion](docs/document-conversion-api.md) · [DOCX semantics](docs/docx-semantic-api.md) · [HTML/CSS](docs/html-import-api.md) |
| Process PDF | [PDF adapter](docs/pdf-adapter-api.md) · [Recovery](docs/pdf-semantic-import-api.md) · [Standards](docs/pdf-standards-api.md) |
| Explore examples and internals | [Examples](docs/examples.md) · [Architecture](docs/architecture.md) · [ADRs](docs/adr/README.md) |

## Build and verification

```powershell
.\gradlew.bat build sbom publishLocalBundle
powershell -NoProfile -File scripts/verify_local_consumer.ps1
```

Use `bash ./gradlew` on Linux/macOS. The local bundle is written to `build/repository`; the consumer resolves Maven coordinates and generates documents.

[GitHub Actions](https://github.com/mirakyux/export4j/actions/workflows/build.yml) runs on pushes to `main`, pull requests and manual dispatch. It covers Java 21 builds on Windows, Linux and macOS, API checks, local artifact consumption and temporary-key signing verification. Application/reader acceptance is recorded separately. See [development](docs/development.md) and [release verification](docs/releasing.md) for the full procedure.

## Contributing

Bug reports, reproducible examples, documentation and focused changes are welcome. Read the [contribution guide](CONTRIBUTING.md) before opening a pull request.

Use [GitHub Issues](https://github.com/mirakyux/export4j/issues) for bugs and proposals. Report security issues through [private vulnerability reporting](https://github.com/mirakyux/export4j/security/advisories/new); see the [security policy](SECURITY.md).

## Acknowledgments

Export4j builds on open-source infrastructure and explicitly supplied font resources:

| Project / resource | Use |
| --- | --- |
| [Apache PDFBox / FontBox](https://pdfbox.apache.org/) | PDF backend, restricted PDF processing and font parsing |
| [Apache Commons Logging](https://commons.apache.org/proper/commons-logging/) | Transitive PDFBox runtime dependency |
| [commonmark-java](https://github.com/commonmark/commonmark-java), [jsoup](https://jsoup.org/) | Offline Markdown and HTML parsing |
| [Bouncy Castle](https://www.bouncycastle.org/) | PDF signature, certificate, revocation and timestamp primitives; internal backend dependency |
| [LWJGL](https://www.lwjgl.org/), [HarfBuzz](https://harfbuzz.github.io/) | Optional native OpenType shaping, outlines and color glyph rendering |
| [OpenJDK](https://openjdk.org/) | Java runtime, Java2D/OpenType, ZIP and XML infrastructure |
| [Gradle](https://gradle.org/), [JUnit](https://junit.org/) | Build and Java tests |
| [pypdf](https://github.com/py-pdf/pypdf), [pypdfium2](https://github.com/pypdfium2-team/pypdfium2), [Pillow](https://python-pillow.org/), [python-docx](https://github.com/python-openxml/python-docx), [cryptography](https://cryptography.io/), [Poppler](https://poppler.freedesktop.org/) | Independent format, rendering and verification tools |
| [Liberation Fonts](https://github.com/liberationfonts/liberation-fonts), [LXGW WenKai TC](https://github.com/lxgw/LxgwWenkaiTC), [ZCOOL XiaoWei](https://github.com/googlefonts/zcool-xiaowei) | Explicit example/test fonts |
| [Noto Fonts](https://github.com/notofonts/noto-fonts), [Source Sans 3](https://github.com/adobe-fonts/source-sans) | Complex-script and CFF example/test fonts |
| [Roboto Flex](https://github.com/googlefonts/roboto-flex), [color-fonts](https://github.com/googlefonts/color-fonts) | Variable and color font verification fixtures |
| [resvg-py](https://github.com/baseplate-admin/resvg-py), [resvg](https://github.com/linebender/resvg) | Independent SVG rendering in verification; excluded from Java runtime artifacts |

See the [dependency record](docs/dependencies.md), [font provenance](assets/fonts/README.md), [NOTICE](NOTICE) and [original licenses](third-party/licenses) for versions, hashes and distribution obligations.

## License

Project-authored code and documentation are licensed under [Apache License 2.0](LICENSE). Third-party dependencies, fonts and other resources retain their original licenses; bundling them does not relicense them under Apache-2.0.
