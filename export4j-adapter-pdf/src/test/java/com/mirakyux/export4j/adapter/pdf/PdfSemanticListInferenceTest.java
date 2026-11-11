// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.adapter.web.ExportPolicy;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.adapter.web.UnsupportedAction;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class PdfSemanticListInferenceTest {
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = Files.newInputStream(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void mixedIndentedMarkersRetainTheirCompleteTextInsteadOfInventingNumberedParents() throws Exception {
        var context = new Inference();
        var document = recover(context, line("- Bullet.", 40, 100), line("007) Nested.", 80, 140), line("8. Still nested.", 80, 180));
        assertInstanceOf(BulletItem.class, document.blocks().getFirst());
        assertEquals("007) Nested.", assertInstanceOf(Paragraph.class, document.blocks().get(1)).text());
        assertEquals("8. Still nested.", assertInstanceOf(Paragraph.class, document.blocks().get(2)).text());
        assertTrue(context.effects.contains("MIXED_LIST_INDENT_RETAINED_AS_TEXT_UNCERTAIN_LIST"));
        assertEquals(3, context.sources.size());
        assertTrue(markdown(document).contains("007\\) Nested\\."));
        assertTrue(renderedText(document).contains("007) Nested."));
    }

    @Test void alignedMarkerChangesStartIndependentListsAndRetainExplicitRestarts() throws Exception {
        var context = new Inference();
        var document = recover(context, line("3. First.", 40, 100), line("- Bullet.", 40, 140),
                line("4. Separate list.", 40, 180), line("1. Restart.", 40, 220));
        var first = assertInstanceOf(NumberedItem.class, document.blocks().getFirst());
        assertInstanceOf(BulletItem.class, document.blocks().get(1));
        var separate = assertInstanceOf(NumberedItem.class, document.blocks().get(2));
        var restart = assertInstanceOf(NumberedItem.class, document.blocks().get(3));
        assertNotEquals(first.listId(), separate.listId()); assertEquals(separate.listId(), restart.listId());
        assertEquals(List.of(1, 1, 1), List.of(first.level(), separate.level(), restart.level()));
        assertEquals(List.of(3, 4, 1), List.of(first.restartAt(), separate.restartAt(), restart.restartAt()));
        assertTrue(context.effects.contains("ALIGNED_MIXED_MARKERS_INFERRED_AS_INDEPENDENT_LIST"));
        assertTrue(markdown(document).contains("Separate list\\."));
        String rendered = renderedText(document); assertTrue(rendered.contains("1. Restart."), rendered);
    }

    @Test void numericLevelGapsStayLiteralWhileValidNestingAndRestartsRemainConsumable() throws Exception {
        var context = new Inference();
        var document = recover(context, line("3. Parent.", 40, 100), line("1) Missing parent.", 88, 140),
                line("1. Child.", 64, 180), line("2. Grandchild.", 88, 220), line("1. Restart parent.", 40, 260),
                line("1) Missing child after restart.", 88, 300), line("1. New child.", 64, 340));
        assertEquals("1) Missing parent.", assertInstanceOf(Paragraph.class, document.blocks().get(1)).text());
        assertEquals("1) Missing child after restart.", assertInstanceOf(Paragraph.class, document.blocks().get(5)).text());
        var numbered = document.blocks().stream().filter(NumberedItem.class::isInstance).map(NumberedItem.class::cast).toList();
        assertEquals(List.of(1, 2, 3, 1, 2), numbered.stream().map(NumberedItem::level).toList());
        assertEquals(1, numbered.stream().map(NumberedItem::listId).distinct().count());
        assertTrue(context.effects.contains("NUMBERED_LIST_LEVEL_GAP_RETAINED_AS_TEXT_UNCERTAIN_LIST"));
        assertEquals(7, context.sources.size());
        assertTrue(markdown(document).contains("1\\) Missing child after restart\\."));
        String rendered = renderedText(document);
        assertTrue(rendered.contains("1) Missing parent."), rendered); assertTrue(rendered.contains("1.1. New child."), rendered);
    }

    @Test void zeroMarkersStayLiteralAndOrdinaryTextEndsThePreviousListContext() throws Exception {
        var context = new Inference();
        var document = recover(context, line("1. First.", 40, 100), line("0. Literal zero.", 64, 140),
                line("2. New root.", 88, 180), line("Ordinary text.", 40, 220), line("1. Final list.", 88, 260));
        assertEquals("0. Literal zero.", assertInstanceOf(Paragraph.class, document.blocks().get(1)).text());
        var first = assertInstanceOf(NumberedItem.class, document.blocks().getFirst());
        var next = assertInstanceOf(NumberedItem.class, document.blocks().get(2));
        var last = assertInstanceOf(NumberedItem.class, document.blocks().get(4));
        assertEquals(1, next.level()); assertEquals(1, last.level());
        assertNotEquals(first.listId(), next.listId()); assertNotEquals(next.listId(), last.listId());
        assertTrue(context.effects.contains("ZERO_NUMBER_MARKER_RETAINED_AS_TEXT_UNCERTAIN_LIST"));
        assertTrue(markdown(document).contains("0\\. Literal zero\\.")); assertTrue(renderedText(document).contains("0. Literal zero."));
    }

    private static PdfSemanticImporter.Glyph line(String text, double x, double baseline) {
        return new PdfSemanticImporter.Glyph(text, x, baseline, 220, 12, InlineStyle.of(12));
    }

    private static Document recover(Inference context, PdfSemanticImporter.Glyph... lines) throws IOException {
        var blocks = new PdfSemanticAnalysis(context, 0).recover(List.of(lines), 600).stream().map(PdfSemanticImporter.Item::block).toList();
        return new Document(DocumentMetadata.EMPTY, PageSettings.A4, blocks);
    }

    private static String markdown(Document document) throws IOException {
        var output = new ByteArrayOutputStream();
        MarkdownAdapter.builder().policy(ExportPolicy.builder().defaultAction(UnsupportedAction.WARN).build()).build().export(document).to(output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String renderedText(Document document) {
        var layout = DocumentEngine.builder(fonts).build().layout(document);
        assertEquals(1, layout.body().pages().size());
        return layout.display().pages().stream().flatMap(page -> page.commands().stream()).filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).map(text -> text.run().text()).collect(java.util.stream.Collectors.joining());
    }

    private static final class Inference implements PdfSemanticAnalysis.Context {
        private int next;
        private final List<String> effects = new ArrayList<>();
        private final List<PreparedPdfImport.Source> sources = new ArrayList<>();
        @Override public String id() { return "inferred-" + next++; }
        @Override public void work() { }
        @Override public void inferred(String effect, int page) { effects.add(effect); }
        @Override public List<PreparedPdfImport.Source> sources() { return sources; }
    }
}
