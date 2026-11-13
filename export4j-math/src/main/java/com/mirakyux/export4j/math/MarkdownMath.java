// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;
import com.mirakyux.export4j.adapter.web.ImportResult;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.VectorResource;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import com.mirakyux.export4j.model.processing.ProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Opt-in, offline dollar-delimited Markdown mathematics. Code, links, images, references and raw
 * HTML are excluded. Single-dollar formulas stay on one line, with non-space inner boundaries
 * and no digit immediately after the closing dollar. Double-dollar blocks must stand on their
 * own lines. This convention reduces currency ambiguity; it is not formula inference or OCR.
 * Immutable and reusable across threads; callbacks run synchronously with a fresh importer builder.
 * Call inside ExecutionContext.execute to propagate cancellation/deadlines.
 */
public final class MarkdownMath {
    private static final Pattern RESOURCE_NAMESPACE = Pattern.compile("export4j-formula:([0-9]{1,7}):");
    /** Strict is the default. KEEP_SOURCE renders an escaped literal and reports the failed occurrence. */
    public enum Fallback { ERROR, KEEP_SOURCE }

    /** Original Markdown UTF-16 range includes delimiters. tex is stripped of boundary whitespace. */
    public record FormulaSpan(int start, int end, String tex, boolean display) {
        public FormulaSpan {
            Objects.requireNonNull(tex, "tex");
            if (start < 0 || end <= start || tex.isBlank()) { throw new IllegalArgumentException("invalid formula span"); }
        }
    }

    /** Redacted per-occurrence loss, independent of the ordinary Markdown import report. */
    public record Degradation(int start, int end, MathException.Reason reason) {
        public Degradation { Objects.requireNonNull(reason, "reason"); }
    }

    public record VectorDocument(Document document, ProcessingReport importReport, List<Degradation> degradations,
            List<FormulaSpan> formulas) {
        public VectorDocument {
            Objects.requireNonNull(document, "document"); Objects.requireNonNull(importReport, "importReport");
            degradations = List.copyOf(degradations); formulas = List.copyOf(formulas);
        }
    }

    public record NativeDocument(DocxMathDocument document, ProcessingReport importReport, List<Degradation> degradations,
            List<FormulaSpan> formulas) {
        public NativeDocument {
            Objects.requireNonNull(document, "document"); Objects.requireNonNull(importReport, "importReport");
            degradations = List.copyOf(degradations); formulas = List.copyOf(formulas);
        }
    }

    private final MathOptions options;
    private final Fallback fallback;
    private final int maxInputLength;
    private final int maxFormulas;
    private final int maxTotalSegments;

    public MarkdownMath() { this(new Builder()); }
    private MarkdownMath(Builder builder) {
        options = builder.options; fallback = builder.fallback;
        maxInputLength = builder.maxInputLength; maxFormulas = builder.maxFormulas;
        maxTotalSegments = builder.maxTotalSegments;
    }

    public static Builder builder() { return new Builder(); }

    /** Scans source only; does not parse TeX or allocate any rendered geometry. */
    public List<FormulaSpan> scan(String source) {
        return MarkdownFormulaScanner.scan(Objects.requireNonNull(source, "source"), maxInputLength,
                maxFormulas, options.maxSourceLength());
    }

    public VectorDocument vector(String source) throws IOException { return vector(source, ignored -> { }); }

    /** Imports directly into the existing semantic model. Fonts and a sufficiently tall fixed line
     * height remain explicit caller choices. configure never receives or retains library resources.
     */
    public VectorDocument vector(String source, Consumer<MarkdownImporter.Builder> configure) throws IOException {
        var prepared = prepare(source, false);
        var importer = MarkdownImporter.builder();
        Objects.requireNonNull(configure, "configure").accept(importer);
        prepared.vectors.forEach(importer::vector);
        var imported = execute(importer, prepared);
        NativeMathBinding.verifySlots(imported.document(), prepared.vectors.values());
        return new VectorDocument(imported.document(), imported.report(), prepared.degradations, prepared.formulas);
    }

    public NativeDocument docx(String source) throws IOException { return docx(source, ignored -> { }); }

    /** Imports editable Word equations, binding every occurrence to its actual paragraph/UTF-16 slot.
     * Repeated TeX is bound independently. Native equations inherit the surrounding paragraph size.
     */
    public NativeDocument docx(String source, Consumer<MarkdownImporter.Builder> configure) throws IOException {
        var prepared = prepare(source, true);
        var importer = MarkdownImporter.builder();
        Objects.requireNonNull(configure, "configure").accept(importer);
        prepared.vectors.forEach(importer::vector);
        var imported = execute(importer, prepared);
        var document = NativeMathBinding.bind(imported.document(), prepared.nativeMath);
        return new NativeDocument(document, imported.report(), prepared.degradations, prepared.formulas);
    }

    private Prepared prepare(String source, boolean nativeTarget) {
        var formulas = scan(source);
        if (formulas.isEmpty()) { return new Prepared(source, Map.of(), Map.of(), List.of(), formulas, source.length(), List.of()); }
        String prefix = namespace(source);
        var vectors = new LinkedHashMap<String, InlineVector>();
        var nativeMath = new LinkedHashMap<String, NativeMathBinding.Equation>();
        var degradations = new ArrayList<Degradation>();
        var markdown = new StringBuilder();
        var replacements = new ArrayList<MathSourceMap.Replacement>();
        int previous = 0;
        int segments = 0;
        for (var span : formulas) {
            ExecutionContext.checkpoint();
            markdown.append(source, previous, span.start());
            int rewrittenStart = markdown.length();
            var local = options.withDisplay(span.display());
            try {
                String key = prefix + span.start();
                InlineVector vector;
                if (nativeTarget) {
                    DocxMath expression = TexMath.docx(span.tex(), local);
                    vector = slot(key);
                    nativeMath.put(key, new NativeMathBinding.Equation(vector, expression, span.display()));
                } else {
                    vector = TexMath.vector(span.tex(), local);
                }
                int count = vector.resource().segmentCount();
                if (count > maxTotalSegments - segments) { throw new MathException(MathException.Reason.LIMIT_EXCEEDED); }
                segments += count;
                vectors.put(key, vector);
                markdown.append("![](").append(key).append(')');
            } catch (MathException failure) {
                if (fallback == Fallback.ERROR || failure.reason() == MathException.Reason.LIMIT_EXCEEDED) { throw failure; }
                degradations.add(new Degradation(span.start(), span.end(), failure.reason()));
                literal(markdown, source.substring(span.start(), span.end()));
            }
            replacements.add(new MathSourceMap.Replacement(span.start(), span.end(), rewrittenStart, markdown.length()));
            previous = span.end();
        }
        markdown.append(source, previous, source.length());
        return new Prepared(markdown.toString(), vectors, nativeMath, degradations, formulas, source.length(), replacements);
    }

    private static ImportResult execute(MarkdownImporter.Builder importer, Prepared prepared) throws IOException {
        try {
            var result = importer.build().read(prepared.markdown).execute();
            return new ImportResult(result.document(), prepared.report(result.report()));
        } catch (ProcessingException failure) {
            var report = prepared.report(new ProcessingReport(ProcessingReport.Phase.PREVIEW, List.of(failure.issue())));
            throw new ProcessingException(report.issues().getFirst());
        }
    }

    private static String namespace(String source) {
        var occupied = new HashSet<Integer>();
        var matcher = RESOURCE_NAMESPACE.matcher(source);
        while (matcher.find()) {
            ExecutionContext.checkpoint();
            occupied.add(Integer.parseInt(matcher.group(1)));
        }
        int value = 0;
        while (occupied.contains(value)) { ExecutionContext.checkpoint(); value++; }
        return "export4j-formula:" + value + ":";
    }

    private static InlineVector slot(String key) {
        return new InlineVector(VectorResource.of(1, 1, 0, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(1.0, -1.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(1.0, 0.0)),
                new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of()))), key);
    }

    private static void literal(StringBuilder target, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '!' && c <= '/' || c >= ':' && c <= '@' || c >= '[' && c <= '`' || c >= '{' && c <= '~') {
                target.append('\\');
            }
            target.append(c);
        }
    }

    private record Prepared(String markdown, Map<String, InlineVector> vectors,
            Map<String, NativeMathBinding.Equation> nativeMath, List<Degradation> degradations,
            List<FormulaSpan> formulas, int originalLength, List<MathSourceMap.Replacement> replacements) {
        private ProcessingReport report(ProcessingReport report) {
            return MathSourceMap.map(report, originalLength, markdown.length(), replacements);
        }
    }

    /** Mutable caller-confined builder; all values are copied into an immutable instance. */
    public static final class Builder {
        private MathOptions options = MathOptions.DEFAULT;
        private Fallback fallback = Fallback.ERROR;
        private int maxInputLength = 1_048_576;
        private int maxFormulas = 256;
        private int maxTotalSegments = 400_000;

        private Builder() { }
        public Builder options(MathOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
        public Builder fallback(Fallback value) { fallback = Objects.requireNonNull(value, "fallback"); return this; }
        public Builder maxInputLength(int value) { maxInputLength = value; return this; }
        public Builder maxFormulas(int value) { maxFormulas = value; return this; }
        public Builder maxTotalSegments(int value) { maxTotalSegments = value; return this; }
        public MarkdownMath build() {
            if (maxInputLength < 1 || maxFormulas < 1 || maxTotalSegments < 1) {
                throw new IllegalArgumentException("Markdown math limits must be positive");
            }
            return new MarkdownMath(this);
        }
    }
}
