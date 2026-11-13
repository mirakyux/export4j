// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.SemanticSourceRegistry;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphLayouter;

/** Plans compact navigation and page signatures, then paints and releases one replayed page at a time. */
final class PagedDocumentRenderer {
    private final DocumentLayoutLimits limits;
    private final ComponentLimits componentLimits;

    PagedDocumentRenderer(DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.componentLimits = Objects.requireNonNull(componentLimits, "componentLimits");
    }

    PagedExportSummary write(SectionedDocument source, List<SectionTemplate> templates, Navigation navigation,
            List<Note> notes, FontGroup fonts, Map<String, FontGroup> roles, PageBackend backend, OutputStream target,
            Consumer<ContextualDiagnostic> diagnostics) throws IOException {
        var plan = plan(source, templates, navigation, notes, fonts, roles);
        var proofs = new ArrayList<byte[]>();
        var links = new ArrayList<DocumentExtras.Link>();
        try (var pass = new Pass(plan, null, null, proofs, links, null)) {
            pass.run();
        }
        var bookmarks = plan.navigation().bookmarks().stream().map(bookmark -> {
            var anchor = anchor(plan.anchors(), bookmark.blockId());
            return new DocumentExtras.Bookmark(bookmark.title(), bookmark.level(), anchor.pageIndex() + plan.offset(), anchor.point());
        }).toList();
        var finalNavigation = new PageNavigation(plan.pages(), bookmarks, links);
        var output = new CountingOutput(target);
        long diagnosticCount;
        ExecutionContext.progress("OUTPUT", "", -1, 0, plan.pages());
        try (var writer = backend.open(source.metadata(), output)) {
            writer.configureNavigation(finalNavigation);
            try (var pass = new Pass(plan, writer, diagnostics, proofs, null, finalNavigation.links())) {
                pass.run();
                diagnosticCount = pass.diagnostics;
            }
            writer.finish();
        }
        ExecutionContext.progress("COMPLETE", "", -1, plan.pages(), plan.pages());
        return new PagedExportSummary(plan.pages(), plan.bodyPages(), plan.offset(), diagnosticCount, output.count);
    }

    private record SectionPlan(Section section, SectionTemplate template, Footnotes.Plan footnotes,
            int start, int blockBase, int pages, int displayedStart, List<byte[]> signatures) { }

    private record Plan(SectionedDocument document, List<SectionPlan> sections, Navigation navigation,
            SemanticBlocks.Result semantic, FontGroup fonts, Map<String, FontGroup> roles,
            Map<String, ResolvedAnchor> anchors, List<Block> sources, Map<String, Double> rotations,
            ContentsLayouter.Result contents, int bodyPages) {
        int offset() { return contents.pages().size(); }
        int pages() { return Math.addExact(bodyPages, offset()); }
        int number(int bodyPage, int offset) {
            for (int index = sections.size() - 1; index >= 0; index--) {
                var section = sections.get(index);
                if (section.start() <= bodyPage && section.section().firstPageNumber() > 0) {
                    return Math.addExact(section.section().firstPageNumber(), bodyPage - section.start());
                }
            }
            return Math.addExact(bodyPage, Math.addExact(offset, 1));
        }
    }

    private Plan plan(SectionedDocument source, List<SectionTemplate> templates, Navigation navigation,
            List<Note> notes, FontGroup fonts, Map<String, FontGroup> roles) throws IOException {
        templates = List.copyOf(templates);
        roles = Map.copyOf(roles);
        if (!source.navigation().equals(DocumentNavigation.NONE)) {
            if (!navigation.equals(Navigation.EMPTY)) { throw new IllegalStateException("portable navigation conflicts with runtime navigation"); }
            navigation = Navigation.fromDocument(source);
        }
        if (!navigation.comments().isEmpty() || !navigation.fields().isEmpty()) {
            throw new IllegalArgumentException("page export cannot represent comments or editable form fields");
        }
        long fixedInteractions = navigation.bookmarks().size() + (navigation.contents().enabled() ? (long) navigation.bookmarks().size() : 0);
        if (fixedInteractions > componentLimits.maxInteractions()) { throw new IllegalArgumentException("document interaction budget exceeded"); }
        var semantic = SemanticBlocks.compile(source, notes);
        var document = semantic.document();
        if (templates.size() != document.sections().size()) { throw new IllegalArgumentException("one template is required per section"); }
        var sources = new ArrayList<Block>(document.sections().stream().flatMap(section -> section.blocks().stream()).toList());
        var layouter = new DocumentLayouter(new ParagraphLayouter(), limits, componentLimits);
        layouter.validate(Footnotes.validationSource(new Document(document.metadata(),
                document.sections().getFirst().pageSettings(), sources), notes, limits));
        var reserved = new LinkedHashSet<String>();
        SemanticTree.walk(sources, visit -> reserved.add(visit.block().id()));
        notes.forEach(note -> reserved.add(note.id()));
        var activeSources = new HashMap<String, SemanticSourceRegistry>();
        var sourceHashes = new IdentityHashMap<Table, byte[]>();
        var anchors = new HashMap<String, ResolvedAnchor>();
        var sections = new ArrayList<SectionPlan>();
        int bodyPages = 0;
        int blockBase = 0;
        long[] semanticPlacements = {0};
        long[] semanticNodes = {0};
        for (int sectionIndex = 0; sectionIndex < document.sections().size(); sectionIndex++) {
            var section = document.sections().get(sectionIndex);
            var template = templates.get(sectionIndex);
            int start = bodyPages;
            int base = blockBase;
            int displayedStart = section.firstPageNumber() > 0 ? section.firstPageNumber()
                    : start == 0 ? 1 : sectionNumber(sections, start - 1) + 1;
            ExecutionContext.progress("SECTION", section.id(), -1, sectionIndex, document.sections().size());
            var signatures = new ArrayList<byte[]>();
            var fontRoles = roles;
            IntFunction<List<Rect>> exclusions = avoidsBody(template) ? local -> template.page(local, displayedStart + local)
                    .bodyExclusions(section.pageSettings(), fonts, fontRoles, start + local, displayedStart + local) : null;
            var footnotes = Footnotes.plan(new Document(document.metadata(), template.bodySettings(section.pageSettings()), section.blocks()),
                    notes, fonts, roles, limits, componentLimits, exclusions, local -> {
                        checkPageBudget(start + local.pageIndex() + 1);
                        var page = global(local, start, base);
                        if (local.pageIndex() != signatures.size()) { throw changed(); }
                        signatures.add(PagedReplayDigest.layout(page, sourceHashes));
                        for (var fragment : page.elements()) {
                            SemanticFragments.walk(fragment, located -> anchors.putIfAbsent(located.fragment().blockId(),
                                    new ResolvedAnchor(page.pageIndex(), located.origin())), ignored -> { });
                            if (fragment instanceof ComponentFragment component) {
                                for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                                    if (++semanticPlacements[0] > componentLimits.maxPlacements()) {
                                        throw new IllegalArgumentException("sectioned semantic placement budget exceeded");
                                    }
                                    registerSource(component.blockId(), located.placement(), activeSources, reserved, sources, semanticNodes);
                                }
                            }
                        }
                    });
            int count = footnotes.pages();
            int required = variants(template).flatMap(value -> java.util.stream.Stream.concat(value.background().stream(), value.foreground().stream()))
                    .mapToInt(Placement::page).max().orElse(0);
            checkPageBudget(Math.max(start + count, required));
            while (start + count < required) {
                signatures.add(PagedReplayDigest.layout(blank(section, template, start + count, base), sourceHashes));
                count++;
            }
            validatePhysicalPlacements(template, start, count, displayedStart);
            sections.add(new SectionPlan(section, template, footnotes, start, base, count, displayedStart, List.copyOf(signatures)));
            bodyPages += count;
            blockBase += section.blocks().size();
        }
        if (!activeSources.isEmpty()) { throw new IllegalArgumentException("semantic continuation stream is incomplete"); }
        var bodySettings = templates.getFirst().bodySettings(document.sections().getFirst().pageSettings());
        layouter.validate(Footnotes.validationSource(new Document(document.metadata(), bodySettings, sources), notes, limits));
        for (var bookmark : navigation.bookmarks()) { anchor(anchors, bookmark.blockId()); }
        var partial = new Plan(document, List.copyOf(sections), navigation, semantic, fonts, roles, Map.copyOf(anchors), List.copyOf(sources),
                ModelRotations.of(sources), new ContentsLayouter.Result(List.of(), List.of(), List.of()), bodyPages);
        var contents = new ContentsLayouter().layout(navigation, bodySettings, fonts, anchors,
                Math.min(limits.maxPages(), 100_000) - bodyPages, limits, componentLimits, partial::number);
        if (!contents.pages().isEmpty() && templates.stream().flatMap(PagedDocumentRenderer::variants)
                .flatMap(value -> java.util.stream.Stream.concat(value.background().stream(), value.foreground().stream()))
                .anyMatch(placement -> placement.page() > 0 || placement.avoidBody())) {
            throw new IllegalArgumentException("physical-page placements and body avoidance cannot be combined with generated contents pages");
        }
        checkPageBudget(bodyPages + contents.pages().size());
        return new Plan(document, partial.sections(), navigation, semantic, fonts, roles, partial.anchors(), partial.sources(), partial.rotations(), contents, bodyPages);
    }

    private int sectionNumber(List<SectionPlan> sections, int page) {
        for (int index = sections.size() - 1; index >= 0; index--) {
            var section = sections.get(index);
            if (section.section().firstPageNumber() > 0) { return Math.addExact(section.section().firstPageNumber(), page - section.start()); }
        }
        return Math.addExact(page, 1);
    }

    private void replay(Plan plan, java.util.function.BiConsumer<SectionPlan, LayoutPage> consumer) throws IOException {
        var sourceHashes = new IdentityHashMap<Table, byte[]>();
        for (var section : plan.sections()) {
            int[] localPage = {0};
            section.footnotes().replay(plan.fonts(), plan.roles(), limits, componentLimits, local -> {
                if (local.pageIndex() != localPage[0]++) { throw changed(); }
                var page = global(local, section.start(), section.blockBase());
                if (local.pageIndex() >= section.signatures().size()
                        || !Arrays.equals(PagedReplayDigest.layout(page, sourceHashes), section.signatures().get(local.pageIndex()))) { throw changed(); }
                consumer.accept(section, page);
            });
            while (localPage[0] < section.pages()) {
                var page = blank(section.section(), section.template(), section.start() + localPage[0], section.blockBase());
                if (!Arrays.equals(PagedReplayDigest.layout(page, sourceHashes), section.signatures().get(localPage[0]++))) { throw changed(); }
                consumer.accept(section, page);
            }
            if (localPage[0] != section.pages()) { throw changed(); }
        }
    }

    /** One pass retains one current page plus bounded global interaction declarations/proofs. */
    private final class Pass implements AutoCloseable {
        private final Plan plan;
        private final PageWriter writer;
        private final Consumer<ContextualDiagnostic> diagnosticSink;
        private final List<byte[]> proofs;
        private final List<DocumentExtras.Link> plannedLinks;
        private final List<DocumentExtras.Link> expectedLinks;
        private final InlineFields.Session fields;
        private final InlineHyperlinks.Session hyperlinks;
        private final DocumentPainter.Session painter = DocumentPainter.openSession();
        private final PageComposer composer = new PageComposer(componentLimits);
        private long commands;
        private long codePoints;
        private long lines;
        private long placements;
        private long interactions;
        private long diagnostics;
        private int pageIndex;
        private int linkIndex;

        Pass(Plan plan, PageWriter writer, Consumer<ContextualDiagnostic> diagnosticSink, List<byte[]> proofs,
                List<DocumentExtras.Link> plannedLinks, List<DocumentExtras.Link> expectedLinks) {
            this.plan = plan;
            this.writer = writer;
            this.diagnosticSink = diagnosticSink;
            this.proofs = proofs;
            this.plannedLinks = plannedLinks;
            this.expectedLinks = expectedLinks;
            interactions = plan.navigation().bookmarks().size();
            fields = new InlineFields.Session(plan.pages(), plan.offset(), page -> plan.number(page, plan.offset()), plan.anchors(),
                    plan.semantic().labels(), plan.semantic().notes(), componentLimits.maxInteractions(), limits.maxCodePoints());
            hyperlinks = new InlineHyperlinks.Session(plan.sources(), plan.offset(), componentLimits.maxInteractions());
        }

        void run() throws IOException {
            for (int index = 0; index < plan.offset(); index++) {
                var section = plan.sections().getFirst();
                var trace = plan.contents().traces().get(index);
                var pageDiagnostics = new ArrayList<ContextualDiagnostic>();
                int physical = index;
                trace.diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic("", physical, "CONTENTS", -1, -1, d)));
                var pageLinks = plan.contents().links().stream().filter(link -> link.pageIndex() == physical).toList();
                var spec = new PageComposer.PageSpec(section.section().pageSettings(),
                        index % 2 == 0 ? section.template().odd() : section.template().even(), index + 1);
                emit(plan.contents().pages().get(index), spec, trace.textLines(), pageDiagnostics, pageLinks, List.of());
            }
            try {
                replay(plan, (section, original) -> {
                    try { body(section, original); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
            } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
            if (pageIndex != plan.pages() || writer != null && linkIndex != expectedLinks.size()) { throw changed(); }
        }

        private void body(SectionPlan section, LayoutPage original) throws IOException {
            ExecutionContext.checkpoint();
            int physical = original.pageIndex() + plan.offset();
            var external = hyperlinks.collect(original);
            var resolved = fields.resolve(original, plan.fonts(), plan.roles(), plan.rotations());
            var page = resolved.page();
            var pageDiagnostics = new ArrayList<ContextualDiagnostic>(resolved.diagnostics());
            bodyDiagnostics(page, physical, pageDiagnostics);
            var links = new ArrayList<>(resolved.links());
            var painted = painter.paintPage(page, plan.rotations());
            var builder = new DisplayListBuilder(painted.size());
            builder.appendCommands(painted.commands());
            int local = page.pageIndex() - section.start();
            var footnote = section.footnotes().page(local);
            builder.appendCommands(footnote.commands());
            footnote.diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic("", physical, "FOOTNOTE", -1, -1, d)));
            for (var fragment : page.elements()) {
                var reference = plan.semantic().references().get(fragment.blockId());
                if (reference == null) { continue; }
                var target = anchor(plan.anchors(), reference.targetId());
                String label = reference.kind() == CrossReference.Kind.PAGE
                        ? Integer.toString(plan.number(target.pageIndex(), plan.offset())) : plan.semantic().labels().get(reference.targetId());
                if (label == null) { throw new IllegalArgumentException("number reference requires a caption or numbered item"); }
                var component = (ComponentFragment) fragment;
                var value = new TextComponent(reference.prefix() + label, SemanticBlocks.style(reference.format()), RgbColor.BLACK)
                        .layout(new ComponentContext(component.layout().width(), component.layout().height(), plan.fonts(),
                                page.pageIndex(), plan.bodyPages(), page.pageIndex() + 1, plan.roles()));
                if (value.width() > Math.nextUp(component.layout().width()) || value.height() > Math.nextUp(component.layout().height())) {
                    throw new IllegalArgumentException("resolved cross reference exceeds reserved geometry");
                }
                builder.save();
                if (reference.format().rotation() != 0) {
                    builder.transform(AffineTransform.fittedRotation(new Rect(fragment.origin().x(), fragment.origin().y(),
                            value.width(), value.height()), reference.format().rotation()));
                }
                builder.transform(AffineTransform.translation(fragment.origin().x(), fragment.origin().y())).appendCommands(value.commands()).restore();
                value.diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic(reference.id(), physical, "REFERENCE", -1, -1, d)));
                links.add(new DocumentExtras.Link(physical, new Rect(fragment.origin().x(), fragment.origin().y(),
                        component.layout().width(), component.layout().height()), target.pageIndex() + plan.offset(), target.point()));
            }
            int number = plan.number(page.pageIndex(), plan.offset());
            var spec = new PageComposer.PageSpec(section.section().pageSettings(), section.template().page(local, number), number);
            emit(builder.build(), spec, bodyLines(page) + footnote.textLines(), pageDiagnostics, links, external);
        }

        private void emit(DisplayPage body, PageComposer.PageSpec spec, long bodyLines, List<ContextualDiagnostic> pageDiagnostics,
                List<DocumentExtras.Link> links, List<DocumentExtras.ExternalLink> external) throws IOException {
            ExecutionContext.checkpoint();
            var composed = composer.composePageDetailed(body, spec, plan.fonts(), plan.roles(), pageIndex, plan.pages());
            placements += composed.declaredPlacements();
            lines += bodyLines;
            for (var placed : composed.placements()) {
                lines += placed.layout().textLines();
                placed.layout().diagnostics().forEach(d -> pageDiagnostics.add(new ContextualDiagnostic("", placed.pageIndex(), placed.region().name(), -1, -1, d)));
            }
            countCommands(composed.page().commands());
            interactions += links.size() + (long) external.size();
            if (placements > componentLimits.maxPlacements() || commands > componentLimits.maxCommands()
                    || codePoints > limits.maxCodePoints() || lines > limits.maxLines() || interactions > componentLimits.maxInteractions()) {
                throw new IllegalArgumentException("paged document combined output budget exceeded");
            }
            byte[] proof = PagedReplayDigest.page(composed.page(), links, external, pageDiagnostics);
            if (writer == null) {
                proofs.add(proof);
                plannedLinks.addAll(links);
            } else {
                if (pageIndex >= proofs.size() || !Arrays.equals(proofs.get(pageIndex), proof)) { throw changed(); }
                for (var link : links) {
                    if (linkIndex >= expectedLinks.size() || !link.equals(expectedLinks.get(linkIndex++))) { throw changed(); }
                }
                for (var diagnostic : pageDiagnostics) { diagnosticSink.accept(diagnostic); }
                writer.accept(composed.page(), external);
                ExecutionContext.progress("OUTPUT", "", pageIndex, pageIndex + 1L, plan.pages());
            }
            diagnostics += pageDiagnostics.size();
            pageIndex++;
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
                    throw new IllegalArgumentException("paged document combined drawing budget exceeded");
                }
            }
        }

        @Override public void close() { painter.close(); }
    }

    private static void bodyDiagnostics(LayoutPage page, int physical, List<ContextualDiagnostic> target) {
        for (var fragment : page.elements()) {
            SemanticFragments.walk(fragment, located -> {
                if (located.fragment() instanceof ComponentFragment component) {
                    component.layout().diagnostics().forEach(d -> target.add(new ContextualDiagnostic(component.blockId(), physical, "COMPONENT", -1, -1, d, located.sourcePath())));
                }
            }, leaf -> leaf.layout().diagnostics().forEach(d -> target.add(new ContextualDiagnostic(leaf.blockId(), physical,
                    leaf.sourcePath().isEmpty() ? "BODY" : "TABLE", leaf.rowIndex(), leaf.columnIndex(), d, leaf.sourcePath()))));
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

    private static LayoutPage global(LayoutPage local, int start, int base) {
        return new LayoutPage(start + local.pageIndex(), local.size(), local.contentBounds(), local.elements().stream().map(fragment -> switch (fragment) {
            case ParagraphFragment p -> (LayoutFragment) new ParagraphFragment(p.blockId(), p.blockIndex() + base, p.firstLineIndex(), p.origin(), p.layout());
            case TableFragment t -> new TableFragment(t.blockId(), t.blockIndex() + base, t.origin(), t.width(), t.height(), t.rows(), t.drawBorders());
            case ComponentFragment c -> new ComponentFragment(c.blockId(), c.blockIndex() + base, c.origin(), c.layout());
        }).toList());
    }

    private static LayoutPage blank(Section section, SectionTemplate template, int page, int base) {
        var settings = template.bodySettings(section.pageSettings());
        return new LayoutPage(page, settings.size(), new Rect(settings.margins().left(), settings.margins().top(), settings.contentWidth(), settings.contentHeight()), List.of());
    }

    private static java.util.stream.Stream<com.mirakyux.export4j.layout.core.PageTemplate> variants(SectionTemplate template) {
        return java.util.stream.Stream.of(template.first(), template.firstEven(), template.odd(), template.even());
    }

    private static boolean avoidsBody(SectionTemplate template) {
        return variants(template).flatMap(value -> java.util.stream.Stream.concat(value.background().stream(), value.foreground().stream())).anyMatch(Placement::avoidBody);
    }

    private static void validatePhysicalPlacements(SectionTemplate template, int start, int count, int displayedStart) {
        var selected = new HashSet<Placement>();
        var rendered = new HashSet<Placement>();
        for (int local = 0; local < count; local++) {
            var value = template.page(local, displayedStart + local);
            for (var placement : java.util.stream.Stream.concat(value.background().stream(), value.foreground().stream()).toList()) {
                if (placement.page() > 0) {
                    selected.add(placement);
                    if (placement.page() == start + local + 1) { rendered.add(placement); }
                }
            }
        }
        if (!rendered.containsAll(selected)) { throw new IllegalArgumentException("positioned page is outside its declaring section or selected page variant"); }
    }

    private void checkPageBudget(int pages) {
        if (pages > limits.maxPages() || pages > 100_000) { throw new IllegalArgumentException("paged document page budget exceeded"); }
    }

    private static ResolvedAnchor anchor(Map<String, ResolvedAnchor> anchors, String id) {
        var anchor = anchors.get(id);
        if (anchor == null) { throw new IllegalArgumentException("interaction refers to an unknown block"); }
        return anchor;
    }

    private static IllegalArgumentException changed() {
        return new IllegalArgumentException("document source, pagination, geometry or drawing changed between planning and replay");
    }

    /** A completed stream releases its first IR placement immediately; only semantic snapshots/IDs survive. */
    private static void registerSource(String host, SemanticPlacement placement, Map<String, SemanticSourceRegistry> active,
            Set<String> claimed, List<Block> sources, long[] semanticNodes) {
        String id = placement.source().id();
        var registry = active.get(id);
        if (registry == null) {
            var ids = new HashSet<String>();
            SemanticTree.walk(placement.source(), visit -> {
                String descendant = visit.block().id();
                if (claimed.contains(descendant) || !ids.add(descendant)) {
                    throw new IllegalArgumentException("semantic source descendant ID conflicts with another document source");
                }
            });
            if (ids.size() + semanticNodes[0] > 100_000) { throw new IllegalArgumentException("semantic source descendant budget exceeded"); }
            registry = new SemanticSourceRegistry(List.of());
            registry.accept(host, placement);
            semanticNodes[0] += ids.size();
            claimed.addAll(ids);
            sources.add(placement.source());
            active.put(id, registry);
        } else { registry.accept(host, placement); }
        if (placement.fragmentIndex() + 1 == placement.fragmentCount()) {
            registry.sources();
            active.remove(id);
        }
    }

    private static final class CountingOutput extends OutputStream {
        private final OutputStream target;
        private long count;
        CountingOutput(OutputStream target) { this.target = Objects.requireNonNull(target, "target"); }
        @Override public void write(int value) throws IOException { target.write(value); count = Math.addExact(count, 1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            target.write(bytes, offset, length); count = Math.addExact(count, length);
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() { /* Borrowed stream. */ }
    }
}
