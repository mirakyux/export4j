# Export4j FAQ

**English** | [简体中文](faq.zh-CN.md)

These answers cover choosing Export4j and integrating its Java document APIs.
Detailed guides describe the supported inputs, resource contracts and format limits.

## What is Export4j used for?

Export4j is an open-source Java PDF generation and document export library for reports, forms and structured documents.
It provides a semantic document model, automatic layout and pagination, Java authoring APIs and XML templates, with PDF, DOCX, HTML, Markdown, SVG and PNG output.

Start with the [capability matrix](en/capabilities-api.md) and [runnable examples](en/examples.md) to check a particular use case.

## Does Export4j require Office, a browser or a printer?

Export4j requires JDK 21; document generation does not require Microsoft Office, a browser process, a printer driver or a print spooler.
Ordinary exports support a headless JVM with explicitly supplied fonts and resources.
The optional visual template designer uses a browser, while Office and browser applications are also used separately for compatibility verification.

See the [runtime architecture](en/architecture.md) and [designer guide](en/designer.md).

## How do I generate my first PDF in Java?

Import the `com.mirakyux.export4j:export4j-bom` BOM, then add `export4j-components` and `export4j-backend-pdf`.
The [README getting started section](../README.md#getting-started) and [complete Java example](../README.md#java-example) show the Maven/Gradle dependencies, explicit font loading and PDF output.
The published candidate is [0.1.0-rc.2](https://github.com/mirakyux/export4j/releases/tag/v0.1.0-rc.2); there is no stable release yet, and APIs may change.

For an existing runnable project, follow [AuthoringExample](../examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java).

## How does Export4j use Apache PDFBox?

Export4j uses Apache PDFBox internally in its PDF implementation modules.
Export4j supplies its own semantic model, text/layout pipeline, automatic pagination and backend-neutral drawing representation; its public APIs do not expose PDFBox types.
Choose the documented Export4j entry point for document authoring or PDF processing according to the task.

See the [module architecture](en/architecture.md), [authoring guide](en/authoring-api.md) and [PDF adapter guide](en/pdf-adapter-api.md).

## How do I generate PDFs containing Chinese or other scripts?

Supply licensed font files that cover the required characters and configure their fallback order explicitly.
Export4j does not scan system fonts; `FontGroup.strict` fails when a character is missing, while the explicit placeholder policy reports replacement diagnostics.
Complex scripts and font formats have their own documented shaping limits.

See the [font and text guide](en/text-api.md), [font sources and licenses (Chinese)](../assets/fonts/README.md) and [multiscript verification (Chinese)](text-corpus-api.md).

## Can XML templates generate both PDF and editable DOCX?

Export4j XML templates bind data into the semantic document model and support variables, conditions, loops, tables and registered includes.
The supported model can be exported through the PDF backend or the semantic DOCX adapter; each output retains only the features covered by that format's contract.
The application supplies fonts and resources, and unsupported conversions follow explicit loss policies.

See the [XML template guide](en/xml-template-api.md), [complete template example](../examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java) and [DOCX guide](en/docx-semantic-api.md).

## Can Export4j render any HTML page to PDF like a browser?

Export4j imports a documented offline HTML/CSS subset and lays out the resulting supported content with its own engine.
It does not execute JavaScript or reproduce a complete browser's styling and layout behavior.
Check supported selectors, properties, layout modes and conversion diagnostics before choosing it for HTML-to-PDF work.

See the [HTML/CSS import guide](en/html-import-api.md) and [conversion workflow](en/document-conversion-api.md).

## Does PDF-to-Word conversion preserve every detail and page break?

Export4j can recover supported PDF text and structure into an editable semantic model and export supported content to DOCX.
Recovery infers structure from PDF content and reports uncertainty; it does not perform OCR or guarantee lossless conversion of arbitrary PDFs.
DOCX readers perform their own layout, so editable Word output and Export4j PDF output need not have identical pagination.

See the [PDF recovery guide](en/pdf-semantic-import-api.md), [DOCX semantics](en/docx-semantic-api.md) and [conversion losses](en/document-conversion-api.md).

## How should I export large reports or long tables?

Use `exportPaged` when retaining the input document is acceptable but retaining all layout results is unnecessary; use block or table-row sources when input should be read incrementally.
`PdfSpoolBackend` uses a page working set and disk indexes, while the ordinary PDF backend retains document objects.
Configure finite source, layout, page, output and temporary-storage budgets for the workload; these paths do not guarantee arbitrary document sizes within a fixed heap.

See [page-at-a-time export](en/paged-document-api.md) and [streaming, replay and PDF spooling](en/streaming-replay-api.md).

## Can I use Export4j in a commercial application?

Export4j's own code and documentation use the Apache License 2.0, which permits commercial use under its terms.
Third-party libraries, fonts and other bundled resources retain their respective licenses and distribution obligations.
Applications must check the licenses of the fonts and resources they supply.

See [LICENSE](../LICENSE), [NOTICE](../NOTICE), the [dependency record](en/dependencies.md) and [font licenses (Chinese)](../assets/fonts/README.md).
