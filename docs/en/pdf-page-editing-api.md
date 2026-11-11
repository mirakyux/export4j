# Page and overlay editing for existing PDFs

`PdfAdapter.editPages` edits strictly preflighted static PDFs without reflowing existing content. Page indices are zero-based, and operations run in declaration order.

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfPageEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("source.pdf"), "");
var style = new PdfPageEditor.TextStyle(
        PdfFont.of(Files.readAllBytes(Path.of("explicit-font.ttf"))), 12, 0x224466, .7);
var policy = ProcessingPolicy.builder()
        .feature("PAGE_CROP", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = adapter.editPages(input).policy(policy)
        .rotate(0, 90)
        .text(0, "Page 1", 24, 90, style, PdfPageEditor.Layer.OVER_CONTENT)
        .crop(0, new PdfPageEditor.Rectangle(10, 10, 200, 250))
        .prepare();
prepared.to(Path.of("edited.pdf"));
```
## Coordinates and order

Coordinates are **physical points from the top-left of the current unrotated MediaBox**, before the reader applies /Rotate. X increases rightward and Y downward; text Y denotes the baseline. The adapter converts nonzero MediaBox origins and UserUnit. Overlays and crop rectangles must fit entirely within MediaBox. Text is checked against actual font widths, ascent, and descent, without automatic shrinking or wrapping.

`rotate` adds an integer multiple of 90 degrees clockwise. `scale` uniformly scales original content and Media/Crop/Bleed/Trim/ArtBox around native PDF (0,0), retaining UserUnit and reader rotation. Later operations use the new boxes. UNDER_CONTENT/OVER_CONTENT explicitly prepend or append content streams; repeated prepends stack in content-stream insertion order.

Text can serve as watermarks, page numbers, or labels; page numbers are static values explicitly supplied by the caller. `image` accepts a validated ImageResource and can place image stamps; this does not provide certified signatures.

## Losses and rejection

Cropping reports PAGE_CROP/CROP/DEGRADE with effect `OUTSIDE_CROP_HIDDEN_NOT_REMOVED`. Strict failure is the default; WARN/IGNORE require explicit DEGRADE acceptance. Hidden content remains recoverable from source content streams and must never be described as redaction. The same policy applies to ENCRYPTION_REMOVED/PREPARE; allowDecryptedCopy and owner verification are still required first.

Fonts are explicitly snapshotted, with a 16 MiB limit. Only static TrueType is accepted; embedding permissions are checked, and no-subsetting flags are retained. Simple horizontal text supports a limited script set. Combining marks, bidirectional text, and scripts requiring shaping produce TEXT_SHAPING/STAMP_TEXT, defaulting to ERROR. Only SKIP_MATCH may skip the entire overlay while retaining issues. Missing glyphs, invalid Unicode, dimensions, permissions, budgets, cancellation, and I/O continue to fail and cannot be forced through an ignore policy.

The default static API continues to reject interactive objects, signatures, tags, layers, and archival declarations. Explicit `PdfAdapter.builder().allowPassiveStructures(true)` permits page editing while retaining source structures in layer/static-tag documents that pass dedicated validation. It does not modify layer configuration or tag logic; see the [compatibility matrix (Chinese)](../pdf-passive-structures-api.md). Original document properties and content streams are retained; identical XML/PDF bytes are not part of the contract. This capability excludes original-text replacement, OCR, and actual content removal.

## Preparation, budgets, and output

prepare fixes the request snapshot and completes encoding once. PreparedPdf.preview returns PREVIEW; successful to returns ACTUAL with the same issues. Requests are confined to the current thread; prepared results can be written concurrently to different targets. Output is a bounded in-memory snapshot, not a hard RSS limit.

PdfLimits controls input, objects, pages, decoding, text, image pixels, and output. Each request allows at most 10,000 edits and no more than maxObjects. Added font/image encodings cumulatively consume maxTotalInputBytes. Images are conservatively charged for each actual use.

Path output uses a temporary file in the same directory, then atomically replaces the target after final checks, without falling back to copying. Failure preserves the old target. Borrowed streams are neither closed nor flushed; physical failures can leave partial bytes. See [PdfPageEditingExample](../../examples/src/main/java/com/mirakyux/export4j/examples/PdfPageEditingExample.java).
