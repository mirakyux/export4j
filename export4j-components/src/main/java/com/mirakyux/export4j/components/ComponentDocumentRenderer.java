// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.layout.core.SemanticSourceRegistry;
import com.mirakyux.export4j.layout.core.PageComposer;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.text.ParagraphLayouter;

/**
 * Reusable component orchestration: body pagination, contents, final anchors, page regions and interactions.
 * No file I/O or backend dependency. Standard components/fonts are immutable; custom SPI must be thread-safe for reuse.
 */
public final class ComponentDocumentRenderer {
    private final DocumentLayoutLimits layoutLimits;
    private final ComponentLimits componentLimits;

    public ComponentDocumentRenderer() {
        this(DocumentLayoutLimits.DEFAULT, ComponentLimits.DEFAULT);
    }

    public ComponentDocumentRenderer(DocumentLayoutLimits layoutLimits, ComponentLimits componentLimits) {
        this.layoutLimits = Objects.requireNonNull(layoutLimits, "layoutLimits");
        this.componentLimits = Objects.requireNonNull(componentLimits, "componentLimits");
    }

    /** All arguments/entries non-null. Fails before output for unknown anchors, impossible geometry or exhausted budgets. */
    public ComponentDocumentLayout layout(Document document, PageTemplate template, Navigation navigation, List<FontFace> fonts) {
        return layout(new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0))),
                List.of(SectionTemplate.uniform(template)), navigation, fonts, Map.of());
    }

    /** Explicit font-group policy for a single-section document. */
    public ComponentDocumentLayout layout(Document document, PageTemplate template, Navigation navigation, FontGroup fonts) {
        return layout(new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0))),
                List.of(SectionTemplate.uniform(template)), navigation, fonts, Map.of(), List.of());
    }

    /** Sections begin on new pages. Templates and explicit font roles are resolved before body pagination. */
    public ComponentDocumentLayout layout(SectionedDocument document, List<SectionTemplate> templates, Navigation navigation,
            List<FontFace> fonts, Map<String, List<FontFace>> roles) {
        return layout(document, templates, navigation, fonts, roles, List.of());
    }

    /** Includes lists/captions, wrapped references and continued notes; reservation is bounded and monotone. */
    public ComponentDocumentLayout layout(SectionedDocument source, List<SectionTemplate> templates, Navigation navigation,
            List<FontFace> fonts, Map<String, List<FontFace>> roles, List<Note> notes) {
        var groups = new HashMap<String, FontGroup>();
        roles.forEach((key, value) -> groups.put(key, FontGroup.strict(value)));
        return layout(source, templates, navigation, FontGroup.strict(fonts), groups, notes);
    }

    /** Missing-glyph policies apply consistently to body, components, contents, notes and page decorations.
     * Named font groups are snapshotted per layout and preserved in fixed page-region component contexts. */
    public ComponentDocumentLayout layout(SectionedDocument source, List<SectionTemplate> templates, Navigation navigation,
            FontGroup fonts, Map<String, FontGroup> roles, List<Note> notes) {
        Objects.requireNonNull(source, "document");
        Objects.requireNonNull(navigation, "navigation");
        if (!source.navigation().equals(com.mirakyux.export4j.model.DocumentNavigation.NONE)) {
            if (!navigation.equals(Navigation.EMPTY)) { throw new IllegalStateException("portable navigation conflicts with runtime navigation"); }
            navigation = Navigation.fromDocument(source);
        }
        roles = Map.copyOf(roles);
        var semantic = SemanticBlocks.compile(source, notes);
        SectionedDocument document = semantic.document();
        new DocumentLayouter(new ParagraphLayouter(), layoutLimits, componentLimits).validate(new Document(document.metadata(),
                document.sections().getFirst().pageSettings(), document.sections().stream().flatMap(section -> section.blocks().stream()).toList()));
        Objects.requireNonNull(document, "document");
        templates = List.copyOf(templates);
        if (templates.size() != document.sections().size()) {
            throw new IllegalArgumentException("one template is required per section");
        }
        Objects.requireNonNull(navigation, "navigation");
        Objects.requireNonNull(fonts, "fonts");
        long interactions = navigation.bookmarks().size() + (long) navigation.comments().size() + navigation.fields().size()
                + (navigation.contents().enabled() ? navigation.bookmarks().size() : 0);
        if (interactions > componentLimits.maxInteractions()) {
            throw new IllegalArgumentException("document interaction budget exceeded");
        }
        List<LayoutPage> bodyPages = new ArrayList<>();
        List<Integer> sectionPages = new ArrayList<>();
        List<Integer> localPages = new ArrayList<>();
        List<com.mirakyux.export4j.layout.ComponentLayout> footnotePages = new ArrayList<>();
        int blockBase = 0;
        long blocks = document.sections().stream().mapToLong(section -> section.blocks().size()).sum();
        if (blocks > layoutLimits.maxParagraphs()) {
            throw new IllegalArgumentException("sectioned document block budget exceeded");
        }
        for (int sectionIndex = 0; sectionIndex < document.sections().size(); sectionIndex++) {
            com.mirakyux.export4j.model.ExecutionContext.progress("SECTION", document.sections().get(sectionIndex).id(), -1, sectionIndex, document.sections().size());
            var section = document.sections().get(sectionIndex);
            var measured = Footnotes.layout(new Document(document.metadata(), templates.get(sectionIndex).bodySettings(section.pageSettings()),
                    section.blocks()), notes, fonts, roles, layoutLimits, componentLimits);
            footnotePages.addAll(measured.pages());
            for (var page : measured.body().pages()) {
                if (bodyPages.size() >= layoutLimits.maxPages()) {
                    throw new IllegalArgumentException("sectioned document page budget exceeded");
                }
                List<LayoutFragment> fragments = new ArrayList<>();
                for (var fragment : page.elements()) {
                    fragments.add(shift(fragment, blockBase));
                }
                bodyPages.add(new LayoutPage(bodyPages.size(), page.size(), page.contentBounds(), fragments));
                sectionPages.add(sectionIndex);
                localPages.add(page.pageIndex());
            }
            blockBase += section.blocks().size();
        }
        var body = new DocumentLayout(document.metadata(), bodyPages);
        PageSettings bodySettings = templates.getFirst().bodySettings(document.sections().getFirst().pageSettings());
        Map<String, ResolvedAnchor> anchors = new HashMap<>();
        var sources = new ArrayList<com.mirakyux.export4j.model.Block>(document.sections().stream().flatMap(section -> section.blocks().stream()).toList());
        var reservedIds = new LinkedHashSet<String>();
        SemanticTree.walk(sources, visit -> reservedIds.add(visit.block().id()));
        notes.forEach(note -> reservedIds.add(note.id()));
        var semanticSources = new SemanticSourceRegistry(List.copyOf(reservedIds));
        long semanticPlacements = 0;
        for (var page : body.pages()) {
            for (var fragment : page.elements()) {
                SemanticFragments.walk(fragment, located -> anchors.putIfAbsent(located.fragment().blockId(),
                        new ResolvedAnchor(page.pageIndex(), located.origin())), ignored -> { });
                if (fragment instanceof ComponentFragment component) {
                    for (var located : ComponentSemantics.locate(component.layout(), component.origin())) {
                        var placed = located.placement();
                        if (++semanticPlacements > componentLimits.maxPlacements()) {
                            throw new com.mirakyux.export4j.layout.core.DocumentLayoutException(
                                    com.mirakyux.export4j.layout.core.DocumentLayoutException.Code.BUDGET_EXCEEDED,
                                    placed.source().id(), component.blockIndex(), page.pageIndex(), -1, -1, -1,
                                    "sectioned semantic placement budget exceeded", null);
                        }
                        semanticSources.accept(component.blockId(), placed);
                    }
                }
            }
        }
        sources.addAll(semanticSources.sources());
        // Nested sources share IDs, cells, images and source-text budgets across sections.
        new DocumentLayouter(new ParagraphLayouter(), layoutLimits, componentLimits).validate(new Document(document.metadata(), bodySettings, sources));
        for (var bookmark : navigation.bookmarks()) {
            requireAnchor(anchors, bookmark.blockId());
        }
        var contents = new ContentsLayouter().layout(navigation, bodySettings, fonts, anchors,
                layoutLimits.maxPages() - body.pages().size(), layoutLimits, componentLimits,
                (page, offset) -> number(document, sectionPages, localPages, page, offset));
        int offset = contents.pages().size();
        // Collect source-linked external rectangles before replacing one-position field slots with generated text.
        var externalLinks = InlineHyperlinks.collect(document, body, offset, componentLimits.maxInteractions() - interactions);
        var inline = InlineFields.resolve(body, offset,
                page -> number(document, sectionPages, localPages, page, offset), anchors, semantic.labels(), notes,
                fonts, roles, componentLimits.maxInteractions() - interactions - externalLinks.size(), layoutLimits.maxCodePoints());
        body = inline.body();
        List<DisplayPage> pages = new ArrayList<>(contents.pages());
        var painted = DocumentPainter.paint(body).pages();
        List<DocumentExtras.Link> links = new ArrayList<>(contents.links());
        links.addAll(inline.links());
        List<com.mirakyux.export4j.layout.ComponentLayout> resolvedReferences = new ArrayList<>();
        List<ContextualDiagnostic> referenceDiagnostics = new ArrayList<>();
        referenceDiagnostics.addAll(inline.diagnostics());
        for (int page = 0; page < painted.size(); page++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var builder = new DisplayListBuilder(painted.get(page).size());
            builder.appendCommands(painted.get(page).commands());
            builder.appendCommands(footnotePages.get(page).commands());
            for (var fragment : body.pages().get(page).elements()) {
                var reference = semantic.references().get(fragment.blockId());
                if (reference != null) {
                    var target = requireAnchor(anchors, reference.targetId());
                    String label = reference.kind() == CrossReference.Kind.PAGE ? Integer.toString(number(document, sectionPages, localPages, target.pageIndex(), offset))
                            : semantic.labels().get(reference.targetId());
                    if (label == null) {
                        throw new IllegalArgumentException("number reference requires a caption or numbered item");
                    }
                    var component = (ComponentFragment) fragment;
                    var resolved = new TextComponent(reference.prefix() + label, SemanticBlocks.style(reference.format()),
                            com.mirakyux.export4j.graphics.RgbColor.BLACK).layout(new com.mirakyux.export4j.layout.core.ComponentContext(
                                    component.layout().width(), component.layout().height(), fonts, page, body.pages().size(), page + 1));
                    builder.save().transform(AffineTransform.translation(fragment.origin().x(), fragment.origin().y())).appendCommands(resolved.commands()).restore();
                    resolvedReferences.add(resolved);
                    int physicalPage = page + offset;
                    resolved.diagnostics().forEach(d -> referenceDiagnostics.add(new ContextualDiagnostic(reference.id(), physicalPage, "REFERENCE", -1, -1, d)));
                    links.add(new DocumentExtras.Link(page + offset, new Rect(fragment.origin().x(), fragment.origin().y(),
                            component.layout().width(), component.layout().height()), target.pageIndex() + offset, target.point()));
                }
            }
            pages.add(builder.build());
        }
        List<DocumentExtras.Bookmark> bookmarks = navigation.bookmarks().stream().map(bookmark -> {
            var anchor = requireAnchor(anchors, bookmark.blockId());
            return new DocumentExtras.Bookmark(bookmark.title(), bookmark.level(), anchor.pageIndex() + offset, anchor.point());
        }).toList();
        List<DocumentExtras.Comment> comments = new ArrayList<>();
        for (var comment : navigation.comments()) {
            var target = resolve(comment.target(), anchors, offset, pages);
            comments.add(new DocumentExtras.Comment(target.page(), target.bounds(), comment.author(), comment.text()));
        }
        List<DocumentExtras.FormField> fields = new ArrayList<>();
        for (var field : navigation.fields()) {
            var target = resolve(field.target(), anchors, offset, pages);
            switch (field) {
                case Navigation.TextField text -> fields.add(new DocumentExtras.TextField(target.page(), target.bounds(),
                        text.name(), text.value(), text.multiline(), text.fonts(), text.style()));
                case Navigation.CheckBox check -> fields.add(new DocumentExtras.CheckBox(target.page(), target.bounds(),
                        check.name(), check.checked(), check.style()));
                case Navigation.ChoiceField choice -> fields.add(new DocumentExtras.ChoiceField(target.page(), target.bounds(),
                        choice.name(), choice.options(), choice.value(), choice.fonts(), choice.style()));
                case Navigation.RadioGroup radio -> fields.add(new DocumentExtras.RadioGroup(target.page(), target.bounds(),
                        radio.name(), radio.options(), radio.value(), radio.style()));
            }
        }
        if ((long) bookmarks.size() + links.size() + comments.size() + fields.size() + externalLinks.size() > componentLimits.maxInteractions()) {
            throw new IllegalArgumentException("combined interaction budget exceeded");
        }
        var extras = new DocumentExtras(bookmarks, links, comments, fields, externalLinks);
        List<PageComposer.PageSpec> specs = new ArrayList<>();
        for (int page = 0; page < offset; page++) {
            specs.add(new PageComposer.PageSpec(document.sections().getFirst().pageSettings(),
                    page % 2 == 0 ? templates.getFirst().odd() : templates.getFirst().even(), page + 1));
        }
        for (int page = 0; page < bodyPages.size(); page++) {
            int section = sectionPages.get(page);
            int label = number(document, sectionPages, localPages, page, offset);
            specs.add(new PageComposer.PageSpec(document.sections().get(section).pageSettings(),
                    templates.get(section).page(localPages.get(page), label), label));
        }
        var uncomposed = new DisplayDocument(document.metadata(), pages, extras);
        checkCombinedBudgets(uncomposed);
        var composed = new PageComposer(componentLimits).composeDetailed(uncomposed, specs, fonts, roles);
        long lines = contents.traces().stream().mapToLong(com.mirakyux.export4j.layout.ComponentLayout::textLines).sum()
                + footnotePages.stream().mapToLong(com.mirakyux.export4j.layout.ComponentLayout::textLines).sum()
                + composed.placements().stream().mapToLong(placed -> placed.layout().textLines()).sum();
        for (var page : body.pages()) {
            for (var fragment : page.elements()) {
                lines += switch (fragment) {
                    case ParagraphFragment p -> p.layout().lines().size();
                    case ComponentFragment c -> c.layout().textLines();
                    case TableFragment t -> t.rows().stream().flatMap(row -> row.cells().stream()).mapToLong(com.mirakyux.export4j.layout.CellFragment::lineCount).sum();
                };
            }
        }
        if (lines > layoutLimits.maxLines()) {
            throw new IllegalArgumentException("sectioned document combined line budget exceeded");
        }
        return new ComponentDocumentLayout(body, offset, composed.display(), contents.traces(), composed.placements(), footnotePages, resolvedReferences, referenceDiagnostics);
    }

    private record Area(int page, Rect bounds) {
    }

    private static Area resolve(Navigation.Target target, Map<String, ResolvedAnchor> anchors, int offset, List<DisplayPage> pages) {
        return switch (target) {
            case Navigation.BlockTarget block -> {
                var anchor = requireAnchor(anchors, block.blockId());
                var bounds = block.relativeBounds();
                yield new Area(anchor.pageIndex() + offset, new Rect(anchor.point().x() + bounds.x(), anchor.point().y() + bounds.y(),
                        bounds.width(), bounds.height()));
            }
            case Navigation.PageTarget page -> {
                if (page.pageIndex() >= pages.size()) {
                    throw new IllegalArgumentException("interaction page does not exist");
                }
                var size = pages.get(page.pageIndex()).size();
                yield new Area(page.pageIndex(), page.position().resolve(new Rect(0, 0, size.width(), size.height())));
            }
        };
    }

    private static LayoutFragment shift(LayoutFragment fragment, int base) {
        return switch (fragment) {
            case ParagraphFragment p -> new ParagraphFragment(p.blockId(), p.blockIndex() + base, p.firstLineIndex(), p.origin(), p.layout());
            case TableFragment t -> new TableFragment(t.blockId(), t.blockIndex() + base, t.origin(), t.width(), t.height(), t.rows(), t.drawBorders());
            case ComponentFragment c -> new ComponentFragment(c.blockId(), c.blockIndex() + base, c.origin(), c.layout());
        };
    }

    private static int number(SectionedDocument document, List<Integer> sections, List<Integer> locals, int page, int offset) {
        int first = page - locals.get(page);
        for (int index = first; index >= 0; index--) {
            int restart = document.sections().get(sections.get(index)).firstPageNumber();
            if (locals.get(index) == 0 && restart > 0) {
                return Math.addExact(restart, page - index);
            }
        }
        return Math.addExact(page, offset + 1);
    }

    private void checkCombinedBudgets(DisplayDocument display) {
        long codePoints = 0;
        long commands = 0;
        for (var page : display.pages()) {
            commands += page.commands().size();
            for (var command : page.commands()) {
                if (command instanceof com.mirakyux.export4j.graphics.DrawCommand.GlyphText text) {
                    codePoints += text.run().glyphs().size();
                } else if (command instanceof com.mirakyux.export4j.graphics.DrawCommand.OutlineText text) {
                    codePoints += text.logicalText().codePointCount(0, text.logicalText().length());
                }
            }
        }
        if (commands > componentLimits.maxCommands() || codePoints > layoutLimits.maxCodePoints()) {
            throw new IllegalArgumentException("sectioned document combined output budget exceeded");
        }
    }

    private static ResolvedAnchor requireAnchor(Map<String, ResolvedAnchor> anchors, String id) {
        var anchor = anchors.get(id);
        if (anchor == null) {
            throw new IllegalArgumentException("interaction refers to an unknown block");
        }
        return anchor;
    }
}
