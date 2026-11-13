# XML template editing and resource bundles

`XmlTemplateBundle`, in the existing `export4j-adapter-template` module, saves, reads, and modifies XML template source and its explicit resources. A single XML file can still be passed directly to `XmlTemplate`. Use an ordinary `.zip` when fragments, images, and fonts must travel together; common archive tools can open it.

Compilation continues through `XmlTemplate → DocumentTemplate`. Variables, conditions, loops, dynamic rows, sections, and page regions keep their existing syntax. This API edits template source rather than inferring a template from a rendered document; binding data does not overwrite placeholders. It adds no model serializer, template interpreter, or custom extension.

For a graphical interface, use the separate [HTML designer](designer.md), which edits the same XML/ZIP while keeping binding data separate. This bundle API remains responsible for source and resource I/O.

## Bundle conventions

| Entry | Content and naming |
| --- | --- |
| `template.xml` | Required main source in strict UTF-8; comments, whitespace, and CRLF can be preserved |
| `fragments/name.xml` | Corresponds to `include ref="name"`; names start with an ASCII letter or underscore, followed by letters, digits, underscores, or hyphens, with a maximum length of 64 |
| `images/name.png`, `.jpg`, `.jpeg` | Corresponds to `image ref="name"`; names contain no hyphens and have at most 256 characters; the extension must match actual PNG/JPEG content. Duplicate names with different extensions are rejected |
| `fonts/name.ttf`, `.otf`, `.ttc`, `.otc` | Original font bytes; names follow fragment rules. Saving neither parses fonts nor infers roles. The caller explicitly loads fonts, selects collection members, and configures the engine when using them |
| `licenses/name.txt` | Optional original copyright/license text in strict UTF-8; names follow fragment rules and do not participate in compilation |

Paths and extensions are case-sensitive. Absolute paths, backslashes, `..`, unknown files, and nested directories are rejected. Empty directory entries for the four resource directories, ZIP comments, and extra fields are allowed. Single-volume classic ZIP with STORED/DEFLATE is supported; encryption, multiple volumes, ZIP64, duplicate entries, CRC/directory inconsistencies, truncation, and trailing content are rejected. Reading does not extract to the filesystem or execute resource paths or network access.

Re-encoding sorts entries by name and is repeatable. Unmodified file-content bytes are preserved, but ZIP compression representation, directory entries, timestamps, comments, and extra fields are not. The bundle is not an editor that preserves original ZIP bytes.

## Create, save, open, and modify

```java
var original = XmlTemplateBundle.builder("""
        <document version="1" title="${title}">
          <page size="A4" margin="18mm"/>
          <heading level="1">${title}</heading>
          <include ref="intro" prefix="intro"/>
          <each items="items" as="item"><p>${item.name}</p></each>
        </document>
        """)
        .fragment("intro", "<fragment version='1'><p>待复核</p></fragment>")
        .build();
original.write(templatePath); // 普通 .zip，父目录须已存在

XmlTemplateBundle reopened;
try (var input = Files.newInputStream(templatePath)) {
    reopened = XmlTemplateBundle.read(input);
}
var edited = reopened.toBuilder()
        .fragment("intro", "<fragment version='1'><p>已复核</p></fragment>")
        .build();
edited.write(editedPath);

var template = edited.compile();
var document = template.render(Map.of("title", "巡检报告", "items", List.of(
        Map.of("name", "东门仓"))));
engine.export(document).backend(new PdfBackend()).to(pdfPath);
```
The example omits imports. The caller supplies paths and an `engine` configured with explicit Chinese fonts. For page regions or sections, use the existing `renderWithPageRegions`/`renderSections` methods respectively. Each output API continues to manage its format and reject/warn/ignore policies.

`toBuilder().source(xml)` replaces the main source; `fragment`/`image`/`font`/`license` replaces a named resource; `remove("images/logo.png")` removes that exact entry. The original object remains unchanged; font-array inputs and return values are copied. Editors can read `source()`, `fragments()`, `images()`, `fonts()`, and `licenses()`. This API provides no graphical editing interface.

Add resources explicitly with `.image("logo", image)`, `.font("Regular.ttf", fontBytes)`, and `.license("Font-OFL.txt", originalLicense)`. The caller must check redistribution rights and retain license text with resources. Fonts can be loaded with `TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf"))`; existing font-support and embedding-permission checks still apply. TTC/OTC use existing collection-member APIs. Successfully bundling bytes does not mean a font is usable for output.

`compile(configure)` accepts a trusted `Consumer<XmlTemplate.Builder>` to configure runtime defaults. These settings, binding data, font roles, callbacks, and engine objects are not written to ZIP. Portable formatting should use existing XML syntax; otherwise, provide the same configuration explicitly when reopening.

## Drafts, diagnostics, and budgets

`build`/`read` validate bundle structure, UTF-8, images, and budgets while allowing drafts with unfinished XML syntax or missing referenced resources. `compile` checks XML syntax, all branches, and references, retaining `TemplateException` codes, positions, and fragment names. Binding retains `DocumentTemplate.BindingException`. Syntax checks for unreferenced fragments follow existing compiler behavior.

Invalid ZIP, malicious XML, unknown syntax, damaged resources, exceeded limits, and cancellation fail directly; WARN/IGNORE cannot conceal them. No source is implicitly discarded or downgraded, so this API needs no additional loss policy. Genuinely lossy format exports continue to use the [shared loss policy (Chinese)](../semantic-editing-api.md#策略与报告).

`Limits.DEFAULT`: 64 MiB encoded size, 128 MiB total expanded size, 512 entries (including empty directories when reading), 1,000,000 UTF-16 units across XML and license text, and 40,000,000 pixels across unique images. Images retain `ImageResource` limits of 32 MiB/20,000,000 pixels per image; each font must be nonempty and at most 32 MiB. XML compilation/includes and data binding retain their separate budgets. Raising bundle limits does not raise compiler limits. These are cooperative logical budgets, not hard process-memory limits.

Builder `.limits(...)`/`.execution(...)` configure later operations; `read(bytes, limits, execution)` and corresponding stream overloads control reading. Timeout/cancellation use synchronous `ExecutionOptions`. Blocking caller streams or configuration callbacks observe cancellation at checkpoints after returning.

`write(OutputStream)` borrows the stream without closing or flushing it. Encoding finishes first, but an actual write failure can still leave partial bytes. `write(Path)` writes a temporary file in the target directory and atomically replaces the target. Failure or cancellation before publication preserves the old file and cleans the temporary file; it does not fall back to non-atomic copying. Atomicity covers one ZIP target only.

## Capability limits and verification

Saving, reopening, editing, and rebinding source are integrated into the existing template pipeline. Existing models can separately use [XmlTemplateWriter (Chinese)](../xml-model-saving-api.md) to convert a defined subset into the same XML/ZIP, applying loss policies to unsupported content. `bundle.conversion()` connects static templates that need no binding data to unified conversion. The bundle itself does not infer arbitrary external documents or embed recovery information in exports. For general model recovery and editing that preserves original documents, see [GAP-24](capability-gaps.md#recovery-and-round-trip-editing-of-self-generated-documents).

For the complete call, see [XmlTemplateBundleExample](../../examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateBundleExample.java). Independent ordinary-ZIP input and Maven-consumer evidence is in [phase 77 verification (Chinese)](../verification/phase-77-xml-template-bundle.md); the design is in [ADR-0083 (Chinese)](../adr/0083-editable-xml-template-bundles.md).
