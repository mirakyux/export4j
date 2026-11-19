# Verification index

Choose evidence by capability, then check the record's version, inputs, tools, actual actions, and limits. [Versions and current status](../current-status.md) separates published and development features. Historical figures do not mean the current source was tested again.

The detailed records below are in Chinese. Their original paths, dates, and results remain intact.

## Export, templates, and fonts

| What to check | Records (Chinese) |
| --- | --- |
| Unified PDF/DOCX entry, themes, DTO/record binding, known limits | [Configured export](../../verification/easy-export.md) |
| Per-block content/defaults and Java/XML equivalence | [Source content blocks](../../verification/content-blocks.md) |
| Mixed content, cell formulas, PDF/DOCX representations, real articles | [Rich content compilation](../../verification/rich-content.md) |
| TeX/Markdown recognition, vectors, native mathematics | [Math module](../../verification/math.md) · [Inline vectors](../../verification/inline-vectors.md) · [Academic templates](../../verification/manuweave-templates.md) |
| Cache bounds, exclusive parser leases, idle release | [Font caches](../../verification/font-cache.md) |
| Native OpenType and static CFF | [Native fonts](../../verification/native-opentype.md) |
| Convenience APIs for files, resources, fonts, pages, and conversion | [API ergonomics](../../verification/api-ergonomics.md) |
| Page-at-a-time output from complete models | [Paged export](../../verification/paged-document-export.md) |
| Body columns and semantic grids | [Body columns](../../verification/semantic-body-columns.md) · [Editable grids](../../verification/editable-grid-layout.md) |
| Table completeness and output quality | [Real-article integration](../../literature-pdf-integration.md) · [Form feasibility probe](../../verification/adr-form-probe.md) |

## Designer

| What to check | Records (Chinese) |
| --- | --- |
| Business samples, editing controls, continuous canvas, table styles | [Stage 108](../../verification/phase-108-designer-and-business-scenarios.md) · [Stage 109](../../verification/phase-109-designer-editing-controls.md) · [Stage 110](../../verification/phase-110-designer-canvas-and-interactions.md) · [Stage 111](../../verification/phase-111-designer-table-styles.md) |
| Grids, variables/arrays, container editing | [Grid interaction](../../verification/designer-grid-ux.md) · [Bindings](../../verification/designer-bindings-ux.md) · [Containers](../../verification/designer-container-ux.md) |
| Editing workflows and nested content | [Workflow closure](../../verification/designer-workflow-closure.md) · [Tables, containers, columns](../../verification/designer-workflow-v2.md) |
| Template properties and branching | [Inspector](../../verification/designer-template-inspector.md) · [Conditions](../../verification/designer-multi-condition.md) |

## Publication, compatibility, and historical stages

| What to check | Records |
| --- | --- |
| Published RC3 build, Central artifacts, remote consumers | [RC3 notes](../releases/0.1.0-rc.3.md) · [Publication records](../releasing.md#published-candidate-record) |
| Platform/signature verification and API gates | [Stage 105 (Chinese)](../../verification/phase-105-publication-and-compatibility.md) · [API compatibility (Chinese)](../../api-compatibility.md) |
| Fixed conversion corpus, rejected input, dependency advisories | [Stage 106 (Chinese)](../../verification/phase-106-security-and-compatibility-corpus.md) |
| Windows reader opening, editing, saving, read-back | [Stage 107 (Chinese)](../../verification/phase-107-windows-reader-compatibility.md) |
| Detailed historical evidence for other capabilities | [Complete record catalog (Chinese)](../../verification); the website generates topic and numbered-stage lists |

Topic pages collect follow-up work on one capability; stage files preserve the original batch. Neither a higher stage number nor a recent file modification proves complete format or platform coverage.

## Evidence boundaries

- Record builds, unit tests, structure checks, independent rendering, visual review, reader actions, signatures, and remote consumption separately.
- An accepted [ADR (Chinese)](../../adr/README.md) describes a design decision. The [capability matrix](../capabilities-api.md) and [gaps](../capability-gaps.md) define support status.
- Shared records contain reproducible commands, repository-relative evidence paths, and limits. Keep generated artifacts, raw logs, private inputs, and credentials in ignored locations or outside the repository.
- Link new records from their API guide and add their topic here. The website generates the complete catalog rather than maintaining another manual stage list.
