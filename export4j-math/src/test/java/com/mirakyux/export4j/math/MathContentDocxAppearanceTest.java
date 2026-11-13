// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vector placeholder's paragraph style cannot carry an inline HTML formula's own size. */
class MathContentDocxAppearanceTest {
    @Test void inlineHtmlFormulaRetainsOwnSizeColorAndLinkInNativeWordOutput() throws Exception {
        var result = MathContent.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.HTML).build()).build()
                .compile(importer -> {
                    // Link labels are deliberately literal source contexts. The author may attach
                    // an explicit destination to a parsed vector without changing formula identity.
                    var parsed = importer.read("<p>Before <span style=\"font-size:18pt;color:#2457a4\">$x$</span> after</p>").execute().document();
                    var p = (Paragraph) parsed.blocks().getFirst();
                    var content = p.content().stream().map(item -> item instanceof InlineVector v
                            ? (InlineContent) new InlineVector(v.resource(), v.logicalText(), v.rgb(), "https://example.org/formula") : item).toList();
                    return new Document(parsed.metadata(), parsed.pageSettings(), List.of(Paragraph.mixed(p.id(), content, p.format())));
                });
        assertEquals(1, result.formulas().size());
        var paragraph = (Paragraph) result.docx().semantic().body().sections().getFirst().blocks().getFirst();
        var marker = paragraph.inlines().stream().filter(run -> run.text().equals("\ufffc")).findFirst().orElseThrow();
        assertEquals(12, paragraph.format().fontSize()); assertEquals(18, marker.style().fontSize());
        assertEquals(0x2457a4, marker.style().rgb()); assertEquals("https://example.org/formula", marker.hyperlink());
        var expression = (DocxMath.Run) result.docx().equations().getFirst().expression();
        assertEquals(18, expression.fontSize()); assertEquals(0x2457a4, expression.rgb());
        var adapter = new DocxAdapter(); var bytes = new ByteArrayOutputStream(); adapter.writeMath(result.docx(), bytes);
        var restored = adapter.readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document();
        assertEquals(result.docx().equations(), restored.equations());
        assertEquals(paragraph.inlines(), ((Paragraph) restored.semantic().body().sections().getFirst().blocks().getFirst()).inlines());
    }

    @Test void bindingRecursesThroughExistingContainersAndWordRejectsUnsupportedLayoutExplicitly() {
        var first = TexMath.vector("x"); var second = TexMath.vector("y");
        var columns = ColumnFlow.builder("columns").add(Paragraph.mixed("column-content", List.of(first), ParagraphFormat.of(12))).build();
        var grid = GridLayout.builder("grid").add(GridItem.builder("item").columnSpan(12)
                .add(Paragraph.mixed("grid-content", List.of(second), ParagraphFormat.of(12))).build()).build();
        var source = Document.builder().add(columns).add(grid).build();
        var bound = NativeMathBinding.bind(source, Map.of("first", new NativeMathBinding.Equation(first, TexMath.docx("x"), false),
                "second", new NativeMathBinding.Equation(second, TexMath.docx("y"), false)), true);
        var blocks = bound.semantic().body().sections().getFirst().blocks();
        assertTrue(blocks.getFirst() instanceof ColumnFlow); assertTrue(blocks.get(1) instanceof GridLayout);
        assertEquals(2, bound.equations().size());
        assertTrue(((Paragraph) ((ColumnFlow) blocks.getFirst()).blocks().getFirst()).vectors().isEmpty());
        assertTrue(((Paragraph) ((GridLayout) blocks.get(1)).items().getFirst().blocks().getFirst()).vectors().isEmpty());
        var bytes = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new DocxAdapter().writeMath(bound, bytes));
        assertEquals(0, bytes.size());
    }
}
