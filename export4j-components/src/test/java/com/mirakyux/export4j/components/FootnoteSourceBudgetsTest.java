// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphLayouter;

class FootnoteSourceBudgetsTest {
    private static final List<Note> NOTES = List.of(new Note("note-one", "one", "\uD83D\uDE00Z", Note.Kind.FOOTNOTE),
            new Note("note-two", "two", "Q", Note.Kind.FOOTNOTE));
    private static FontFace face;

    @BeforeAll static void font() throws IOException {
        try (var input = FootnoteSourceBudgetsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            face = TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    @Test void fullAndPagedLayoutsRejectCumulativeFootnoteTextBeforeAnyGlyphLookup() {
        // Two body letters and two field slots plus three footnote scalars require exactly seven.
        rejectsBeforeShaping(new DocumentLayoutLimits(4, 6, 100, 10));
    }

    @Test void fullAndPagedLayoutsShareSourceNodeLimitsAcrossSectionsAndFootnotes() {
        rejectsBeforeShaping(new DocumentLayoutLimits(3, 100, 100, 10));
    }

    @Test void sourcePreflightCountsSupplementaryScalarsOnceAndEndnotesOnlyWhereLowered() {
        var source = compiled(source(), NOTES);
        var exact = new DocumentLayoutLimits(4, 7, 100, 10);
        var layouter = new DocumentLayouter(new ParagraphLayouter(), exact, ComponentLimits.DEFAULT);
        assertDoesNotThrow(() -> layouter.validate(Footnotes.validationSource(source, NOTES, exact)));

        var endnotes = List.of(new Note("end", "anchor", "X", Note.Kind.ENDNOTE));
        var endSource = compiled(new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("section", PageSettings.A4, List.of(anchor("anchor", "", "end")), 0))), endnotes);
        // One field slot plus the lowered endnote "[1] X": two nodes and six scalars.
        var endLimits = new DocumentLayoutLimits(2, 6, 100, 10);
        assertDoesNotThrow(() -> new DocumentLayouter(new ParagraphLayouter(), endLimits, ComponentLimits.DEFAULT)
                .validate(Footnotes.validationSource(endSource, endnotes, endLimits)));
    }

    @Test void discoveredComponentSourcesStillShareTheFootnoteBudget() {
        var notes = List.of(new Note("note", "anchor", "FOOT", Note.Kind.FOOTNOTE));
        var table = Table.builder("hidden-table").row(TableCell.builder().text("TABLE").build()).build();
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PageSettings.A4,
                List.of(anchor("anchor", "", "note"), new ComponentBlock("component", new SemanticTable(table))), 0)));
        var fonts = FontGroup.strict(face);
        // The field slot, note and hidden table contribute 1 + 4 + 5 scalars.
        var limits = new DocumentLayoutLimits(10, 9, 100, 10);
        var full = assertThrows(DocumentLayoutException.class,
                () -> new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT).layout(document,
                        List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, fonts, Map.of(), notes));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, full.code());
        assertEquals("note", full.blockId());
        var paged = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(fonts).notes(notes).build().exportPaged(document).limits(limits)
                        .backend((metadata, target) -> { throw new AssertionError("backend must not open"); })
                        .to(new ByteArrayOutputStream()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, paged.code());
        assertEquals("note", paged.blockId());
    }

    private static void rejectsBeforeShaping(DocumentLayoutLimits limits) {
        var lookups = new AtomicInteger();
        var fonts = FontGroup.strict(new FontFace() {
            @Override public FontData data() { return face.data(); }
            @Override public FontMetrics metrics() { return face.metrics(); }
            @Override public Optional<GlyphMetrics> glyph(int codePoint) {
                lookups.incrementAndGet();
                throw new AssertionError("source rejection must precede note or body shaping");
            }
        });
        var templates = List.of(SectionTemplate.uniform(PageTemplate.EMPTY), SectionTemplate.uniform(PageTemplate.EMPTY));
        var full = assertThrows(DocumentLayoutException.class,
                () -> new ComponentDocumentRenderer(limits, ComponentLimits.DEFAULT)
                        .layout(source(), templates, Navigation.EMPTY, fonts, Map.of(), NOTES));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, full.code());
        assertEquals("note-two", full.blockId());
        var paged = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(fonts).notes(NOTES).build().exportPaged(source()).limits(limits)
                        .backend((metadata, target) -> { throw new AssertionError("backend must not open"); })
                        .to(new ByteArrayOutputStream()));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, paged.code());
        assertEquals("note-two", paged.blockId());
        assertEquals(0, lookups.get());
    }

    private static SectionedDocument source() {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PageSettings.A4, List.of(anchor("one", "A", "note-one")), 0),
                new Section("second", PageSettings.A4, List.of(anchor("two", "B", "note-two")), 0)));
    }

    private static Paragraph anchor(String id, String text, String note) {
        var style = InlineStyle.of(10);
        return Paragraph.mixed(id, List.of(new InlineText(text, style),
                new InlineField(InlineField.Kind.NOTE_MARKER, note, 20, style)), ParagraphFormat.of(10));
    }

    private static Document compiled(SectionedDocument source, List<Note> notes) {
        var document = SemanticBlocks.compile(source, notes).document();
        return new Document(document.metadata(), document.sections().getFirst().pageSettings(),
                document.sections().stream().flatMap(section -> section.blocks().stream()).toList());
    }
}
