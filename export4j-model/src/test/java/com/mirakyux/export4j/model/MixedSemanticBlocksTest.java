// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import com.mirakyux.export4j.model.edit.DocumentEditor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MixedSemanticBlocksTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static final InlineStyle STYLE = InlineStyle.of(12);

    private static InlineVector vector() {
        return new InlineVector(VectorResource.of(12, 8, 2, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, -8.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(12.0, 2.0)))),
                "${raw}", 0x224466, "https://example.com/formula");
    }

    @Test void bindingAndTextEditingKeepObjectsAtTheirNewUtf16Locations() throws IOException {
        var vector = vector();
        var field = new InlineField(InlineField.Kind.PAGE, "", 20, STYLE);
        var content = List.<InlineContent>of(new InlineText("${name}", STYLE), vector, field, new InlineText(" tail", STYLE));
        var source = List.<Block>of(Heading.mixed("h", content, 1, FORMAT),
                BulletItem.mixed("b", content, "*", 1, FORMAT),
                NumberedItem.mixed("n", "steps", content, 0, FORMAT, 1));
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4,
                source.stream().map(block -> (DocumentTemplate.Node) new DocumentTemplate.ValueBlock(block)).toList(),
                DocumentTemplate.Limits.DEFAULT);
        var bound = template.render(Map.of("name", "A😀"));
        var edited = DocumentEditor.edit(bound).replaceText("tail", "longer tail").execute().document();
        for (var block : edited.blocks()) {
            Paragraph p = switch (block) {
                case Heading value -> value.paragraph();
                case BulletItem value -> value.paragraph();
                case NumberedItem value -> value.paragraph();
                default -> throw new AssertionError();
            };
            assertEquals("A😀\ufffc\ufffc longer tail", p.text());
            assertEquals(3, p.vectors().getFirst().sourceIndex());
            assertEquals(4, p.fields().getFirst().sourceIndex());
            assertEquals(vector, p.vectors().getFirst().vector());
            assertSame(vector.resource(), p.vectors().getFirst().vector().resource());
            assertEquals("${raw}", p.vectors().getFirst().vector().logicalText());
        }
        var replacement = new InlineVector(vector.resource(), "replacement", 0x112233, "https://example.com/replaced");
        var replaced = DocumentEditor.edit(edited).vector("h", 3, replacement).vector("b", 3, replacement)
                .vector("n", 3, replacement).execute().document();
        assertEquals(List.of(Heading.class, BulletItem.class, NumberedItem.class),
                replaced.blocks().stream().map(Block::getClass).toList());
        for (var block : replaced.blocks()) {
            var p = block instanceof Heading h ? h.paragraph() : block instanceof BulletItem b ? b.paragraph()
                    : ((NumberedItem) block).paragraph();
            assertSame(replacement, p.vectors().getFirst().vector());
            assertEquals(4, p.fields().getFirst().sourceIndex());
        }
    }

    @Test void listBuilderCopiesTheWholeMixedBodyBeforeReplacingItWithPlainText() {
        var content = List.<InlineContent>of(new InlineText("A", STYLE), vector());
        var numbered = NumberedItem.mixed("n", "steps", content, 2, FORMAT, 1);
        var bullet = BulletItem.mixed("b", content, "*", 1, FORMAT);
        assertEquals(numbered, numbered.toBuilder().build());
        assertEquals(bullet, bullet.toBuilder().build());
        assertEquals(List.of(), numbered.toBuilder().text("Replaced").build().vectors());
        assertEquals(List.of(), bullet.toBuilder().inlines(List.of(new InlineText("Replaced", STYLE))).build().vectors());
    }

    @Test void mixedListBuildersSnapshotContentAndUseTheFinalObjectPlaceholderStyle() {
        var vector = vector();
        var mutable = new java.util.ArrayList<InlineContent>(List.of(new InlineText("A", STYLE), vector));
        var numbered = NumberedItem.builder().id("n").listId("steps").content(mutable).format(ParagraphFormat.of(16));
        var bullet = BulletItem.builder().id("b").content(mutable).format(ParagraphFormat.of(16));
        mutable.clear();
        for (var p : List.of(numbered.build().paragraph(), bullet.build().paragraph())) {
            assertEquals("A\ufffc", p.text());
            assertEquals(STYLE, p.inlines().getFirst().style());
            assertEquals(InlineStyle.of(16), p.inlines().getLast().style());
            assertSame(vector, p.vectors().getFirst().vector());
        }
        assertEquals(18, numbered.build().toBuilder().format(ParagraphFormat.of(18)).build()
                .inlines().getLast().style().fontSize());
        assertEquals(18, bullet.build().toBuilder().format(ParagraphFormat.of(18)).build()
                .inlines().getLast().style().fontSize());
    }

    @Test void documentConveniencesSnapshotMixedObjectsAndKeepTheirResolvedFormats() {
        var vector = vector();
        var content = new java.util.ArrayList<InlineContent>(List.of(new InlineText("A😀", STYLE), vector,
                new InlineField(InlineField.Kind.PAGE, "", 20, STYLE)));
        var builder = Document.builder().bodyFormat(ParagraphFormat.of(16))
                .headingContent(content, 1).bulletContent(content).numberedContent("steps", content);
        content.clear();
        var document = builder.bodyFormat(ParagraphFormat.of(18)).build();
        assertEquals(List.of("auto-1", "auto-2", "auto-3"), document.blocks().stream().map(Block::id).toList());
        for (var block : document.blocks()) {
            var p = block instanceof Heading h ? h.paragraph() : block instanceof BulletItem b ? b.paragraph()
                    : ((NumberedItem) block).paragraph();
            assertEquals("A😀\ufffc\ufffc", p.text());
            assertEquals(3, p.vectors().getFirst().sourceIndex());
            assertEquals(4, p.fields().getFirst().sourceIndex());
            assertSame(vector, p.vectors().getFirst().vector());
            assertEquals(STYLE, p.inlines().getFirst().style());
            assertEquals(block instanceof Heading ? Heading.defaultFormat(1) : ParagraphFormat.of(16), p.format());
        }
        assertEquals(InlineStyle.of(16), ((BulletItem) document.blocks().get(1)).markerStyle());
        assertEquals(InlineStyle.of(16), ((NumberedItem) document.blocks().get(2)).markerStyle());
    }
}
