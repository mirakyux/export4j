// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.PageSettings;

/** One-pass output, or explicit two-pass source/layout proof replay, with one active body page. */
final class StreamingDocumentRenderer {
    private final DocumentLayoutLimits limits;
    private final ComponentLimits componentLimits;

    StreamingDocumentRenderer(DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        this.limits = limits;
        this.componentLimits = componentLimits;
    }

    PagedExportSummary write(PageSettings settings, BlockSource.Factory factory, FontGroup fonts,
            Map<String, FontGroup> roles, PageTemplate template, DocumentMetadata metadata, PageBackend backend,
            OutputStream target, int expectedPages, boolean replay, Consumer<ContextualDiagnostic> diagnostics) throws IOException {
        var bodySettings = template.bodySettings(settings);
        var proofs = replay ? new ArrayList<byte[]>() : null;
        byte[] expectedSource = null;
        int pages = expectedPages;
        ExecutionContext.checkpoint();
        if (replay) {
            var sourceProof = new PagedReplayDigest.Source();
            try (var source = Objects.requireNonNull(factory.open(), "block source")) {
                pages = layout(bodySettings, source, sourceProof, settings, template, fonts, roles, (page, pageSources) -> {
                    checkPage(page.pageIndex());
                    validateFields(page);
                    proofs.add(PagedReplayDigest.layout(page, new IdentityHashMap<>()));
                });
            }
            expectedSource = sourceProof.finish();
            if (expectedPages > 0 && pages != expectedPages) { throw wrongTotal(); }
        }
        checkTotal(pages);
        for (var placement : java.util.stream.Stream.concat(template.background().stream(), template.foreground().stream()).toList()) {
            if (placement.page() > pages) { throw new IllegalArgumentException("positioned page is outside the streamed document"); }
        }
        var output = new CountingOutput(target);
        var sourceProof = replay ? new PagedReplayDigest.Source() : null;
        long count;
        ExecutionContext.progress("OUTPUT", "", -1, 0, pages);
        try (var writer = Objects.requireNonNull(backend.open(metadata, output), "page writer")) {
            writer.configureNavigation(new PageNavigation(pages, List.of(), List.of()));
            try (var pass = new Pass(settings, template, fonts, roles, writer, pages, proofs, diagnostics)) {
                int actual;
                try (var source = Objects.requireNonNull(factory.open(), "block source")) {
                    actual = layout(bodySettings, source, sourceProof, settings, template, fonts, roles, pass::accept);
                }
                if (actual != pages || pass.pageIndex != pages) { throw wrongTotal(); }
                if (replay && !Arrays.equals(expectedSource, sourceProof.finish())) { throw changed(); }
                count = pass.diagnosticCount;
            }
            writer.finish();
        }
        ExecutionContext.progress("COMPLETE", "", -1, pages, pages);
        return new PagedExportSummary(pages, pages, 0, count, output.count);
    }

    private int layout(PageSettings body, BlockSource source, PagedReplayDigest.Source proof, PageSettings original,
            PageTemplate template, FontGroup fonts, Map<String, FontGroup> roles, DocumentLayouter.BlockPageConsumer consumer) throws IOException {
        var layouter = new DocumentLayouter(new com.mirakyux.export4j.text.ParagraphLayouter(), limits, componentLimits);
        var prepared = new PreparedSource(source, proof, block -> layouter.validate(new Document(DocumentMetadata.EMPTY, body, List.of(block))));
        return layouter.layoutBlocks(body, prepared, fonts, roles,
                page -> template.bodyExclusions(original, fonts, roles, page, page + 1), consumer);
    }

    private final class Pass implements AutoCloseable {
        private final PageSettings settings;
        private final PageTemplate template;
        private final FontGroup fonts;
        private final Map<String, FontGroup> roles;
        private final PageWriter writer;
        private final int pages;
        private final List<byte[]> proofs;
        private final Consumer<ContextualDiagnostic> diagnostics;
        private final DocumentPainter.Session painter = DocumentPainter.openSession();
        private final PageComposer composer = new PageComposer(componentLimits);
        private final InlineFields.Session fields;
        private long commands;
        private long codePoints;
        private long lines;
        private long placements;
        private long interactions;
        private long diagnosticCount;
        private int pageIndex;

        Pass(PageSettings settings, PageTemplate template, FontGroup fonts, Map<String, FontGroup> roles,
                PageWriter writer, int pages, List<byte[]> proofs, Consumer<ContextualDiagnostic> diagnostics) {
            this.settings = settings;
            this.template = template;
            this.fonts = fonts;
            this.roles = roles;
            this.writer = writer;
            this.pages = pages;
            this.proofs = proofs;
            this.diagnostics = diagnostics;
            fields = new InlineFields.Session(pages, 0, page -> page + 1, Map.of(), Map.of(), List.of(),
                    componentLimits.maxInteractions(), limits.maxCodePoints());
        }

        void accept(LayoutPage original, List<Block> sources) throws IOException {
            ExecutionContext.checkpoint();
            if (original.pageIndex() != pageIndex || pageIndex >= pages) { throw wrongTotal(); }
            checkPage(pageIndex);
            validateFields(original);
            if (proofs != null && (pageIndex >= proofs.size()
                    || !Arrays.equals(proofs.get(pageIndex), PagedReplayDigest.layout(original, new IdentityHashMap<>())))) {
                throw changed();
            }
            var rotationSources = new ArrayList<>(sources);
            for (var fragment : original.elements()) {
                if (fragment instanceof ComponentFragment component) {
                    for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                        rotationSources.add(located.placement().source());
                    }
                }
            }
            var rotations = ModelRotations.of(rotationSources);
            var external = InlineHyperlinks.collectPage(original, sources, componentLimits.maxInteractions() - interactions);
            var resolved = fields.resolve(original, fonts, roles, rotations);
            var page = resolved.page();
            var pageDiagnostics = new ArrayList<>(resolved.diagnostics());
            for (var fragment : page.elements()) {
                SemanticFragments.walk(fragment, located -> {
                    if (located.fragment() instanceof ComponentFragment component) {
                        component.layout().diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic(component.blockId(),
                                pageIndex, "COMPONENT", -1, -1, d, located.sourcePath())));
                    }
                }, leaf -> leaf.layout().diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic(leaf.blockId(),
                        pageIndex, leaf.sourcePath().isEmpty() ? "BODY" : "TABLE", leaf.rowIndex(), leaf.columnIndex(), d, leaf.sourcePath()))));
            }
            var body = painter.paintPage(page, rotations);
            var composed = composer.composePageDetailed(body, new PageComposer.PageSpec(settings, template, pageIndex + 1),
                    fonts, roles, pageIndex, pages);
            placements += composed.declaredPlacements();
            lines += bodyLines(page);
            for (var placed : composed.placements()) {
                lines += placed.layout().textLines();
                placed.layout().diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic("", pageIndex,
                        placed.region().name(), -1, -1, d)));
            }
            countCommands(composed.page().commands());
            interactions += external.size();
            if (placements > componentLimits.maxPlacements() || commands > componentLimits.maxCommands()
                    || codePoints > limits.maxCodePoints() || lines > limits.maxLines() || interactions > componentLimits.maxInteractions()) {
                throw new IllegalArgumentException("ordinary block stream combined output budget exceeded");
            }
            for (var diagnostic : pageDiagnostics) { diagnostics.accept(diagnostic); }
            writer.accept(composed.page(), external);
            diagnosticCount = Math.addExact(diagnosticCount, pageDiagnostics.size());
            pageIndex++;
            ExecutionContext.progress("OUTPUT", "", pageIndex - 1, pageIndex, pages);
        }

        private void countCommands(List<DrawCommand> source) {
            for (var command : source) {
                commands++;
                if (command instanceof DrawCommand.GlyphText text) { codePoints += text.run().glyphs().size(); }
                else if (command instanceof DrawCommand.OutlineText text) { codePoints += text.logicalText().codePointCount(0, text.logicalText().length()); }
                else if (command instanceof DrawCommand.Text text) { codePoints += text.text().codePointCount(0, text.text().length()); }
                else if (command instanceof DrawCommand.Group group) { countCommands(group.commands()); }
                else if (command instanceof DrawCommand.PaintFill fill && fill.paint() instanceof Paint.Tile tile) { countCommands(tile.commands()); }
                else if (command instanceof DrawCommand.PaintStroke stroke && stroke.paint() instanceof Paint.Tile tile) { countCommands(tile.commands()); }
                if (commands > componentLimits.maxCommands() || codePoints > limits.maxCodePoints()) {
                    throw new IllegalArgumentException("ordinary block stream combined drawing budget exceeded");
                }
            }
        }

        @Override public void close() { painter.close(); }
    }

    private static final class PreparedSource implements BlockSource {
        private final BlockSource source;
        private final PagedReplayDigest.Source proof;
        private final Consumer<Block> validate;
        private final SemanticBlocks.Stream semantics = new SemanticBlocks.Stream();

        PreparedSource(BlockSource source, PagedReplayDigest.Source proof, Consumer<Block> validate) {
            this.source = source; this.proof = proof; this.validate = validate;
        }
        @Override public Optional<Block> next() throws IOException {
            var next = Objects.requireNonNull(source.next(), "block source next result");
            if (next.isEmpty()) { return Optional.empty(); }
            var original = next.orElseThrow();
            var prepared = semantics.compile(original);
            if (proof != null) {
                // Reject an over-budget individual source before canonical hashing; the incremental
                // layouter independently charges it once to the complete source's shared counters.
                validate.accept(prepared);
                proof.add(original);
            }
            return Optional.of(prepared);
        }
    }

    private static void validateFields(LayoutPage page) {
        for (var fragment : page.elements()) {
            SemanticFragments.walk(fragment, ignored -> { }, leaf -> {
                for (var line : leaf.layout().lines()) {
                    for (var field : line.fields()) {
                        var kind = field.field().kind();
                        if (kind != InlineField.Kind.PAGE && kind != InlineField.Kind.PAGES) {
                            throw new IllegalArgumentException("ordinary block streams support only PAGE and PAGES fields");
                        }
                    }
                }
            });
        }
    }

    private static long bodyLines(LayoutPage page) {
        long result = 0;
        for (var fragment : page.elements()) {
            result += switch (fragment) {
                case ParagraphFragment paragraph -> paragraph.layout().lines().size();
                case ComponentFragment component -> component.layout().textLines();
                case TableFragment table -> table.rows().stream().flatMap(row -> row.cells().stream()).mapToLong(com.mirakyux.export4j.layout.CellFragment::lineCount).sum();
            };
        }
        return result;
    }

    private void checkPage(int index) {
        if (index < 0 || index >= limits.maxPages() || index >= 100_000) {
            throw new IllegalArgumentException("ordinary block stream page budget exceeded");
        }
    }
    private void checkTotal(int pages) {
        if (pages <= 0 || pages > limits.maxPages() || pages > 100_000) {
            throw new IllegalArgumentException("ordinary block stream total page budget exceeded");
        }
    }
    private static IllegalArgumentException wrongTotal() { return new IllegalArgumentException("ordinary block stream page total differs from declaration or replay"); }
    private static IllegalArgumentException changed() { return new IllegalArgumentException("ordinary block source, pagination, geometry or drawing changed between passes"); }

    private static final class CountingOutput extends OutputStream {
        private final OutputStream target;
        private long count;
        CountingOutput(OutputStream target) { this.target = target; }
        @Override public void write(int value) throws IOException { target.write(value); count = Math.addExact(count, 1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            target.write(bytes, offset, length); count = Math.addExact(count, length);
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() { /* Borrowed destination. */ }
    }
}
