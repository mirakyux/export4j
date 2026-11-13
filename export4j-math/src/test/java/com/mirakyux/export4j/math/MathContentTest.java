// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止源内容在不同容器或输出目标中丢失公式、把重复出现合并，或由降级吞掉取消和预算。 */
class MathContentTest {
    @Test void oneCompilationBindsHeadingsListsRepeatedHeadersAndInequalities() throws Exception {
        var math = MathContent.builder().defaults(ContentOptions.builder().rawHtml(true).build()).build();
        var result = math.read("# Heading $x^2$\n\n- Item $x^2$\n\n"
                + "<table><thead><tr><th>Header $x$</th></tr></thead><tbody><tr><td>$x < 1$</td></tr></tbody></table>\n\n"
                + "`$code$`\n\n$$\n\\frac{1}{2}\n$$\n");
        assertEquals(5, result.formulas().size());
        assertEquals(5, result.docx().equations().size());
        assertTrue(result.degradations().isEmpty());
        assertTrue(result.document().blocks().getFirst() instanceof Heading);
        assertTrue(result.document().blocks().stream().anyMatch(BulletItem.class::isInstance));
        assertEquals(5, result.docx().equations().stream().map(e -> e.anchor()).distinct().count());
        assertEquals(1, result.docx().equations().stream().filter(e -> e.placement() == DocxMathDocument.Placement.DISPLAY_CENTER).count());
        var table = result.docx().semantic().body().sections().getFirst().blocks().stream().filter(Table.class::isInstance).map(Table.class::cast).findFirst().orElseThrow();
        assertEquals(1, table.headerRows());
        assertFalse(table.rows().getFirst().cells().getFirst().hasBlocks());
        var adapter = DocxAdapter.builder().lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
        var bytes = new ByteArrayOutputStream(); adapter.writeMath(result.docx(), bytes);
        var restored = adapter.readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document();
        assertEquals(result.docx().equations(), restored.equations());
        assertTrue(text(result.document()).contains("$code$"));
    }

    @Test void nativeFailureHasAnExplicitImageCandidateAndSeparateTargetReport() throws Exception {
        var result = MathContent.builder().fallbacks(List.of(MathContent.Fallback.IMAGE, MathContent.Fallback.TEXT)).build()
                .read("Sum $\\sum_{i=1}^n i$ and editable $x^2$.");
        assertEquals(2, result.formulas().size());
        assertEquals(1, result.docx().equations().size());
        assertEquals(List.of(new MathContent.Degradation(0, MathContent.Target.DOCX,
                MathContent.Fallback.IMAGE, MathContent.Reason.UNSUPPORTED_FORMULA)), result.degradations());
        var nativeBody = (Paragraph) result.docx().semantic().body().sections().getFirst().blocks().getFirst();
        assertEquals(1, nativeBody.images().size());
        assertTrue(nativeBody.images().getFirst().image().resource().pixelWidth() >= nativeBody.images().getFirst().image().width() * 4 - 1);
        assertThrows(MathException.class, () -> MathContent.builder().build().read("$\\sum_{i=1}^n i$"));
    }

    @Test void textCandidateKeepsCompleteTexAndCannotHideUnknownHtml() throws Exception {
        var math = MathContent.builder().defaults(ContentOptions.builder().rawHtml(true).build())
                .fallbacks(List.of(MathContent.Fallback.TEXT)).build();
        var result = math.read("Before $\\unknown{x_y}$ after.");
        assertEquals(2, result.degradations().size());
        assertEquals("Before $\\unknown{x_y}$ after.", text(result.document()));
        assertTrue(result.docx().equations().isEmpty());
        assertThrows(RuntimeException.class, () -> math.read("<script>alert(1)</script>"));
    }

    @Test void sourceCountersCanBeRaisedWithoutAHidden256FormulaCeiling() throws Exception {
        assertEquals(257, MarkdownMath.builder().maxFormulas(257).build().scan("$x$ ".repeat(257)).size());
        var result = MathContent.builder().maxFormulas(300).build().read("$x$ ".repeat(257));
        assertEquals(257, result.formulas().size());
        assertEquals(257, result.docx().equations().size());
        var limits = new ImportLimits(1_200_000, 1_200_000, 100_000, 64, 2_400_000, 100_000, 10_000, 30_000_000);
        var large = MathContent.builder().limits(limits).build().read("a".repeat(1_100_000));
        assertEquals(1_100_000, ((Paragraph) large.document().blocks().getFirst()).text().length());
    }

    @Test void diagnosticsAndFormulaStateAreConfinedToOneCompilation() throws Exception {
        var math = MathContent.builder().fallbacks(List.of(MathContent.Fallback.TEXT)).build();
        assertEquals(2, math.read("$\\unknown{x}$").degradations().size());
        assertTrue(math.read("$x$").degradations().isEmpty());
        assertEquals(1, math.read("$x$").formulas().size());
        assertThrows(IllegalArgumentException.class, () -> math.compile(importer -> {
            importer.read("$x$").execute();
            return Document.builder().paragraph("discarded").build();
        }));
        var options = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> math.read("$x$")));
        assertThrows(MathException.class, () -> MathContent.builder().maxFormulas(1)
                .fallbacks(List.of(MathContent.Fallback.TEXT)).build().read("$x$ $y$"));
    }

    @Test void derivativePrimeIsAnEditableMathematicalSuperscript() {
        var equation = TexMath.docx("f''(x)");
        assertTrue(equation instanceof DocxMath.Sequence);
        var first = ((DocxMath.Sequence) equation).elements().getFirst();
        assertTrue(first instanceof DocxMath.Superscript);
        var script = ((DocxMath.Superscript) first).superscript();
        assertTrue(script instanceof DocxMath.Sequence);
        assertEquals(List.of(new DocxMath.Run("′", DocxMath.Style.PLAIN), new DocxMath.Run("′", DocxMath.Style.PLAIN)), ((DocxMath.Sequence) script).elements());
    }

    @Test void xmlAndValueCompactionCannotCoalesceRepeatedFormulaOccurrences() throws Exception {
        var result = MathContent.builder().build().compile(importer -> XmlTemplate.builder().contentParser(importer).build().compile("""
                <document version="1" content-format="MARKDOWN" formulas="true">
                  <content id="first"><![CDATA[$x$ and $x$]]></content>
                  <content id="second"><![CDATA[| Header |\n|---|\n| $x$ |\n| $x$ |]]></content>
                </document>
                """).render(Map.of()));
        assertEquals(4, result.formulas().size());
        assertEquals(4, result.docx().equations().size());
        assertEquals(4, result.docx().equations().stream().map(e -> e.anchor()).distinct().count());
        var bytes = new ByteArrayOutputStream();
        new DocxAdapter().writeMath(result.docx(), bytes);
        assertEquals(4, new DocxAdapter().readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document().equations().size());
    }

    @Test void fittingUsesFirstLineAndNestedListIndentationBeforeChoosingAnImage() throws Exception {
        String tex = "abcdefghij";
        double equationWidth = TexMath.vector(tex).resource().width();
        var page = com.mirakyux.export4j.model.PageSettings.builder().size(equationWidth + 60, 400).margins(10).build();
        var compiler = MathContent.builder().fallbacks(List.of(MathContent.Fallback.IMAGE)).build();
        var result = compiler.compile(importer -> {
            var parsed = (Paragraph) importer.read("$" + tex + "$").execute().document().blocks().getFirst();
            var format = parsed.format().toBuilder().firstLineIndent(50).subsequentLineIndent(0).build();
            return Document.builder().pageSettings(page)
                    .add(Paragraph.mixed("indented", parsed.content(), format)).build();
        });
        var paragraph = (Paragraph) result.document().blocks().getFirst();
        assertEquals(1, paragraph.images().size());
        assertTrue(paragraph.images().getFirst().image().width() <= page.contentWidth() - 50 + 1e-9);
        var nested = compiler.compile(importer -> {
            var parsed = (Paragraph) importer.read("$" + tex + "$").execute().document().blocks().getFirst();
            var format = parsed.format().toBuilder().firstLineIndent(8).subsequentLineIndent(8).build();
            return Document.builder().pageSettings(page).add(BulletItem.mixed("nested", parsed.content(), "•", 4, format)).build();
        });
        var item = (BulletItem) nested.document().blocks().getFirst();
        assertEquals(1, item.images().size());
        assertTrue(item.images().getFirst().image().width() <= page.contentWidth() - 8 - 3 * item.format().fontSize() * 1.5 + 1e-9);
    }

    @Test void anUnrelatedAutomaticTableDoesNotEnterFormulaGeometryResolution() throws Exception {
        var plain = new Table("plain", List.of(new com.mirakyux.export4j.model.ColumnWidth.Auto(1, 500)),
                List.of(new com.mirakyux.export4j.model.TableRow(List.of(com.mirakyux.export4j.model.TableCell.builder().text("ordinary").build()))), 0,
                com.mirakyux.export4j.model.TableFormat.DEFAULT);
        var result = MathContent.builder().build().compile(importer -> {
            var parsed = importer.read("$x$").execute().document();
            return Document.builder().add(plain).add(parsed.blocks().getFirst()).build();
        });
        assertEquals(plain, result.document().blocks().getFirst());
        assertEquals(1, result.docx().equations().size());
    }

    @Test void nativeWordSizeRestrictionsUseTheDeclaredRepresentationCandidates() throws Exception {
        var result = MathContent.builder().defaults(ContentOptions.builder().format(ContentOptions.Format.HTML).build())
                .fallbacks(List.of(MathContent.Fallback.IMAGE)).build()
                .read("<p><span style=\"font-size:11.25pt\">$x$</span></p>");
        assertEquals(1, result.formulas().size());
        assertEquals(1, ((Paragraph) result.document().blocks().getFirst()).vectors().size());
        assertTrue(result.docx().equations().isEmpty());
        assertEquals(List.of(new MathContent.Degradation(0, MathContent.Target.DOCX,
                MathContent.Fallback.IMAGE, MathContent.Reason.UNSUPPORTED_APPEARANCE)), result.degradations());
        var bytes = new ByteArrayOutputStream();
        new DocxAdapter().writeMath(result.docx(), bytes);
        assertTrue(bytes.size() > 0);
    }

    private static String text(Document document) {
        var result = new StringBuilder();
        SemanticTree.walk(document.blocks(), visit -> { if (visit.block() instanceof Paragraph p) { result.append(p.text()); } });
        return result.toString();
    }
}
