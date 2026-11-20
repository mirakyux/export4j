# Export4j documentation

Author documents with Java or templates, or import, edit, and convert existing files. Start with the [English README](../../README.md) or [Chinese README](../../README.zh-CN.md), then use the [example index](examples.md) for tasks and output directories.

See the [FAQ](../faq.md) or [Chinese FAQ](../faq.zh-CN.md) for selection, runtime requirements, fonts, conversion limits, and licenses. Primary guides are available in English and Chinese; untranslated advanced references and historical records are labeled Chinese.

## First integration

| Goal | Reading order |
| --- | --- |
| Use the candidate on Maven Central | [Published quick start](../../README.md#getting-started) → [Authoring](authoring-api.md) |
| Evaluate development APIs with less setup | [Current status](current-status.md) → [Unified PDF/DOCX entry](easy-export-api.md) → [Development examples](examples.md#development-examples) |
| Bind business objects to XML templates | [XML templates](xml-template-api.md) → [DTO/record binding rules](xml-template-api.md#java-objects) |
| Inspect output or edit templates | [Examples](examples.md) → [Local designer](designer.md) |

Published RC3 and current `develop` have different module and API scopes. New development APIs require a matching source build or local artifact.

## Read by task

| Task | Guides |
| --- | --- |
| Configure once, export directly | [Unified PDF/DOCX entry](easy-export-api.md) |
| Write reports and forms | [Authoring](authoring-api.md) · [Source blocks and inheritance](content-blocks-api.md) · [Fluent API](fluent-api.md) · [Components](components-api.md) · [Static forms](static-forms-api.md) |
| Configure fonts and styles | [Fonts/text](text-api.md) · [Font caching](font-cache-api.md) · [Themes](themes-api.md) · [Native OpenType (Chinese)](../native-opentype-api.md) · [Font sources/licenses (Chinese)](../../assets/fonts/README.md) |
| Adjust layout and page regions | [Layout (Chinese)](../layout-api.md) · [Editable grids](grid-layout-api.md) · [Body columns and full-width figures](column-flow-api.md) · [Continuous columns (Chinese)](../continuous-columns-api.md) · [Contour floats (Chinese)](../contour-floats-api.md) · [Page variants](page-variants-api.md) · [Background/foreground (Chinese)](../page-overlays-api.md) |
| Write tables and long tables | [Semantic tables](table-api.md) · [Column spans](column-spans-api.md) · [Row spans](row-spans-api.md) · [Block cells/nested tables (Chinese)](../block-cells-api.md) · [Forward-only tables](streaming-table-api.md) · [Replay/disk-indexed PDF](streaming-replay-api.md) |
| Add navigation and fields | [Inline fields (Chinese)](../inline-fields-api.md) · [Editable forms](form-styles-api.md) · [Portable navigation (Chinese)](../portable-navigation-api.md) |
| Use the visual designer | [Designer](designer.md) |
| Use XML/Markdown templates | [XML](xml-template-api.md) · [Template themes (Chinese)](../xml-themes-api.md) · [Page regions (Chinese)](../xml-page-regions-api.md) · [Sections (Chinese)](../xml-sections-api.md) · [Strict Markdown templates](markdown-template-api.md) |
| Save templates/recover documents | [Template ZIPs](xml-template-bundle-api.md) · [Save models as XML (Chinese)](../xml-model-saving-api.md) · [Optional model recovery](model-recovery-api.md) |
| Import, edit, and convert | [Conversion](document-conversion-api.md) · [Semantic editing/loss reports (Chinese)](../semantic-editing-api.md) · [General Markdown import](markdown-import-api.md) · [HTML/CSS import](html-import-api.md) · [HTML/Markdown export](web-export-api.md) |
| Process mathematics and mixed content | [Mixed content and cross-format math](rich-content-api.md) · [TeX/Markdown formulas](math-api.md) · [Native Word math](docx-math-api.md) · [Inline vectors](inline-vectors-api.md) |
| Process DOCX | [Native semantics/editing](docx-semantic-api.md) · [Native math](docx-math-api.md) · [Original-package text editing (Chinese)](../docx-editing-api.md) · [Page regions (Chinese)](../docx-page-regions-api.md) · [Fields (Chinese)](../docx-fields-api.md) · [Style import (Chinese)](../docx-style-import-api.md) |
| Process existing PDF | [Entry points/limits](pdf-adapter-api.md) · [Pages](pdf-page-editing-api.md) · [Objects (Chinese)](../pdf-object-editing-api.md) · [Composition (Chinese)](../pdf-object-composition-api.md) · [Rendering/images (Chinese)](../pdf-raster-api.md) · [Optimization (Chinese)](../pdf-optimization-api.md) |
| Edit/recover PDF content | [Text replacement](pdf-text-editing-api.md) · [Image replacement](pdf-image-editing-api.md) · [Two redaction modes](pdf-redaction-api.md) · [Semantic recovery](pdf-semantic-import-api.md) · [Source fonts (Chinese)](../pdf-font-compatibility-api.md) · [Passive structures (Chinese)](../pdf-passive-structures-api.md) |
| PDF standards/signatures | [PDF/A, PDF/UA, PAdES](pdf-standards-api.md) · [Certification/DocMDP (Chinese)](../pdf-certification-api.md) · [Existing signature validation (Chinese)](../pdf-signature-verification-api.md) · [Form data (Chinese)](../pdf-form-data-api.md) |
| Advanced graphics | [Colors, composition, SVG resources (Chinese)](../advanced-graphics-api.md) |

Each API guide describes supported inputs, calls, ownership, loss policies, and failure boundaries. XML/strict Markdown templates differ from general document import. Loading SVG/PNG resources is not semantic import of existing documents.

## Current capabilities and plans

| Question | Source of facts |
| --- | --- |
| Which version and known limitations apply? | [Current status](current-status.md) |
| What works, and which API should I use? | [Capabilities](capabilities-api.md) and the corresponding guide |
| Which scenarios are suitable for evaluation? | [Readiness assessment (Chinese)](../readiness-assessment.md) |
| What is missing, and what counts as complete? | [Capability gaps](capability-gaps.md) |
| How does work proceed? | [Execution plan (Chinese)](../remaining-capabilities-plan.md) · [Roadmap (Chinese)](../roadmap.md) |
| What was actually verified? | [Verification by topic](verification/README.md) · [Complete record catalog (Chinese)](../verification) |

The gap table owns the current backlog. Phase records preserve their original examples, limits, and results; each feature guide links to the relevant acceptance records.

## Development and maintenance

| Topic | Entry points |
| --- | --- |
| Contribute | [Contribution guide (Chinese)](../../CONTRIBUTING.md) · [Development](development.md) |
| Understand modules, execution paths, and resource ownership | [Architecture](architecture.md) · [Execution paths](architecture.md#main-execution-paths) · [Lifecycles](architecture.md#state-and-resource-lifecycles) |
| Find extension points and design decisions | [Maintenance entry points](architecture.md#extension-and-maintenance-entry-points) · [ADRs (Chinese)](../adr/README.md) |
| Build/output verification | [Test strategy](development.md#test-strategy) · [Verification matrix (Chinese)](../../.agents/skills/develop-export4j/references/verification.md) |
| Compatibility/releases | [API compatibility (Chinese)](../api-compatibility.md) · [Releases](releasing.md) |
| Dependencies/licenses | [Dependencies](dependencies.md) · [Fonts (Chinese)](../../assets/fonts/README.md) · [NOTICE](../../NOTICE) · [Third-party licenses](../../third-party/licenses) |
| Historical background | [Project proposal (Chinese)](../project-proposal.md) and ADRs |

Usage guides describe runtime conditions and contracts; architecture/development define boundaries; acceptance records preserve tool versions, actual application actions, and limits of results.
