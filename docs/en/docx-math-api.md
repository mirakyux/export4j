# Native DOCX math API

`export4j-adapter-docx` reads, builds and edits native Word OMML equations through immutable `DocxMath` trees. `writeMath` generates standard `m:oMath` / `m:oMathPara`, keeping equations editable. The model does not substitute original packages, XML fragments, pictures or TeX text for mathematical structure.

`DocxMathDocument` wraps an existing `DocxSemanticDocument`. Public core models, the layout engine and PDF/SVG backends do not depend on this adapter type. This adapter has no general TeX↔OMML, MathML or native-math-model-to-PDF entry point. The optional [math module](math-api.md) adds bounded TeX-to-OMML and Markdown formula recognition.

## Read, construct and edit

| Entry point | Result and ownership |
| --- | --- |
| `readMath(input, policy)` | Returns `MathReadResult(document, diagnostics)` with default math budgets; borrows input |
| `readMath(input, policy, mathLimits)` | Applies explicit math budgets, `DocxLimits` and model structural constraints together |
| `writeMath(document, output)` | Rebuilds a complete standard package from typed models; borrows output |
| `writeMath(document, output, mathLimits)` | Applies explicit math budgets under the same output contract |
| `document.withEquation(id, expression)` | Replaces the named equation and returns a new document; the original is unchanged, and unknown IDs fail |

This complete example creates an editable fraction, reads it strictly, then changes its denominator from `2a` to `3a`. For the published-artifact consumer combining matrices and fractions, see [NativeMathExample](../../verification/consumer/src/main/java/consumer/NativeMathExample.java).

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.docx.DocxSemanticDocument;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NativeFraction {
    public static void main(String[] args) throws IOException {
        var body = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("main", PageSettings.A4,
                        List.of(new Paragraph("formula-paragraph", "\ufffc",
                                ParagraphFormat.of(18))), 0)));
        var equation = new DocxMathDocument.Equation("fraction",
                new DocxSemanticDocument.Point("formula-paragraph", 0),
                DocxMathDocument.Placement.DISPLAY_CENTER,
                new DocxMath.Fraction(DocxMath.run("1"), DocxMath.run("2a")));
        var document = new DocxMathDocument(body, List.of(equation));
        var adapter = new DocxAdapter();
        Files.createDirectories(Path.of("build"));
        var source = Path.of("build/native-fraction.docx");
        try (var output = Files.newOutputStream(source)) {
            adapter.writeMath(document, output);
        }
        DocxAdapter.MathReadResult imported;
        try (var input = Files.newInputStream(source)) {
            imported = adapter.readMath(input, DocxAdapter.Policy.STRICT);
        }
        var changed = imported.document().withEquation("fraction",
                new DocxMath.Fraction(DocxMath.run("1"), DocxMath.run("3a")));
        try (var output = Files.newOutputStream(Path.of("build/native-fraction-edited.docx"))) {
            adapter.writeMath(changed, output);
        }
    }
}
```

`DocxMathDocument` expands nested `Sequence` values, normalizes single-child sequences and orders equations by semantic paragraph order and UTF-16 position. Standard OMML without project IDs can also be read. Import generates equation/host-paragraph IDs without requiring Export4j extension metadata.

## Typed trees and standard nodes

| `DocxMath` node | Semantics and actual OMML |
| --- | --- |
| `Run`, `Sequence` | Mathematical text runs and ordered subtrees; `m:r` / `m:t` |
| `Fraction` | Bar fractions with `m:f` numerator/denominator; not linear or barless fractions |
| `Subscript`, `Superscript`, `SubSuperscript` | Subscripts, superscripts and combined scripts; `m:sSub` / `m:sSup` / `m:sSubSup` |
| `Radical` | Square root or explicit degree, `m:rad`; omitted degree uses standard `degHide` |
| `Nary` | Integrals, double/contour integrals, sums and products; `m:nary`, including limits, limit positions and growth declarations |
| `Matrix` | Rectangular rows/columns, per-column left/center/right alignment and top/center/bottom baseline; `m:m` / `m:mr` / `m:e` |
| `Delimiter` | Opening/closing/separator characters, ordered arguments, growth and shape; `m:d`, including one-sided delimiters for piecewise functions |
| `Function` | Function-name subtree and argument, `m:func`; names may themselves contain superscripts |
| `LowerLimit`, `UpperLimit` | Base and limit expression, `m:limLow` / `m:limUpp` |

`Run` requires valid Unicode, rejecting control characters and isolated surrogates. Styles are italic, upright, bold, bold italic or ordinary text. `fontSize=0` inherits the host paragraph; otherwise it is in points, must be a half-point multiple and cannot exceed 144 pt. `rgb=0` uses the default color; other values are 24-bit RGB.

Math-node/depth budgets count the actual typed tree. Hidden empty root degrees and hidden empty operator limits are standard XML placeholders, not model nodes. Visible empty sequences still count. Hidden arguments must be empty: nonempty content and unknown attributes are rejected, and all XML remains subject to independent package budgets.

Math runs read mapped formatting only. Explicit run fonts and Word-written structural control fonts normalize to the document's default `mathFont` only when exactly equal to it. Other control formats, unknown nodes/attributes and unmapped syntax are rejected. Hidden degrees/limits containing nonempty content fail rather than using hiding flags to discard content.

## Anchors and settings

Every `Equation` occupies one real `U+FFFC` in an ordinary host paragraph, anchored by `DocxSemanticDocument.Point(paragraphId, utf16)`. Offsets use original UTF-16; emoji occupy two units. Each marker binds exactly one equation and cannot also be an image or field. Unbound object markers in paragraphs fail too.

`INLINE` can share a paragraph with ordinary body text. `DISPLAY_LEFT`, `DISPLAY_CENTER`, `DISPLAY_RIGHT` and `DISPLAY_CENTER_GROUP` require paragraph text to be exactly that single marker. Markers must use the host paragraph's default ordinary-run style with no hyperlink. Equations cannot cover revision, content-control or contents-cache ranges. Existing semantic objects keep their own contracts; math entry points do not relax them.

Import first maps standard equations to markers, then uses the existing semantic reader for body text. Old complex-field caches may normalize from multiple characters to one field marker. Math anchors are relocated afterward using actual retained object slots, not pre-normalization cache lengths.

`DocxMathSettings` stores standard `m:mathPr`: explicit font family, binary-operator break/minus rules, small-fraction/display defaults, margins and paragraph spacing, default equation alignment, wrap indent/direction and default limits for integral/other large operators. Integer distances use twips, with `20 twip = 1 pt`, in `0..31680`. The default family declaration is `Cambria Math`. The adapter does not scan, load or render this system font; Word determines its actual display.

## Budgets, diagnostics and failure

Default `DocxMathLimits` cumulatively allow 10,000 equations, 100,000 math nodes, depth 32, 100,000 matrix cells and 4,000,000 text code points per complete import/output. Callers can tighten explicit limits; `DocxMathDocument` default model ceilings still apply. Each matrix must also be nonempty and rectangular, with at most 1,024 per side and 100,000 cells. Math nodes, text and matrix cells count toward shared DOCX source budgets too, preventing separate budgets from bypassing `DocxLimits`.

Unknown/malformed OMML fails under both `STRICT` and `ALLOW_LOSS`. `ALLOW_LOSS` only retains implemented loss channels for ordinary Word bodies, styles, parts and metadata, returning every actual diagnostic. It never converts unknown equations into text or pictures. Ordinary `read`, `readSemantic` and conversion imported through them cannot represent math trees, so native equations require `readMath`, even with `ALLOW_LOSS`; equations are not silently lost. Whole-package rejection checks run before style materialization and side-story conversion. Only standard `mathPr` settings leaves are exempted in `settings.xml`.

Borrowed input/output streams are not closed. Math models, the full package and all encoded bytes are validated before touching output. Budgets, structural errors and synchronous cancellation do not write partial packages early. Final `output.write(...)` I/O failure may still leave partial bytes. Callers needing atomic file publication should write a temporary file and move it only on success. Package, relationship, XML, macro/active-object, external-resource and field-safety restrictions match the [native-semantic entry points](docx-semantic-api.md); loss policy cannot bypass them.

## Examples and independent verification

Run from the repository root. On Linux/macOS, replace `.\gradlew.bat` with `./gradlew`. Real Word verification runs only on Windows with Microsoft Word installed.

```powershell
.\gradlew.bat :examples:manuWeaveMath
python scripts/verify_manuweave_math.py

& verification/verify_docx_math_word.ps1 `
    -InputPath examples/build/manuweave-math/math.docx `
    -OutputDirectory build/verification/manuweave-math-word
.\gradlew.bat :examples:manuWeaveMath `
    --args="--verify-word-saved build/verification/manuweave-math-word/word-saved.docx"
python scripts/verify_manuweave_math.py `
    --word-directory build/verification/manuweave-math-word
```

The sample's eight equations cover fractions/radicals, scripts, integration, summation, matrices, piecewise functions, Greek letters/trigonometry and limits/products. Three standard-package rounds remove all project namespaces/attributes. The independent script compares standard OMML and verifies that editing changes only the first denominator. Optional published-artifact checks use isolated task `nativeMath`, passing the current build directory to `--consumer-build-directory`. Without explicit Word or consumer-directory options, corresponding reports are `not_requested`, not passed.

PDF/SVG examples separately use JLaTeXMath 1.0.7 within `examples`, with explicit font resources to generate vector outlines and source TeX retained as PDF ActualText/SVG aria-label. This dependency does not enter the public DOCX adapter. Java constructs the same example's native equation trees independently; there is no general TeX↔OMML conversion promise.

Actual Word 16.0 build `16.0.20527` application automation opened eight native equations, edited the first denominator `2a→5a`, saved, reopened and exported PDF. The other seven equations stayed unchanged. Importing the Word-saved file generated 75 explicit diagnostics for ordinary styles, theme/comment parts and metadata. Math trees/settings remained equal after re-encoding and strict read-back. The original Word file still does not pass whole-document strict import; this is not arbitrary Word lossless round-tripping.

The independent script checks structure/logical text with pypdf and renders all pages with Poppler/PDFium at 192 DPI. It uses the project's existing blur 2 px, mean error `<4` and changed-pixel ratio `<0.06` protocol, generating individual pages and a contact sheet. These are application-automation and independent-output results, not manual Word UI verification. Word is not part of the library's production pipeline. See [ManuWeave verification (Chinese)](../verification/manuweave-templates.md) and [ADR-0108 (Chinese)](../adr/0108-native-docx-math-and-example-typesetting.md).
