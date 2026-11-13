// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Complete source defaults must reach both final projections without flattening semantic styles. */
class MathContentSemanticStylesTest {
    private static final String SOURCE = "# Heading $x$\n\nBody $y$\n\n| Header |\n|---|\n| Cell $z$ |";

    @Test void resolvedDefaultsReachVisualAndNativeFormulaAppearance() throws Exception {
        var theme = theme();
        var defaults = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).formulas(true).build()
                .resolve(ContentOptions.fromTheme(theme));
        var math = MathContent.builder().defaults(defaults).build();
        var visual = math.readVisual(SOURCE);
        var heading = (Heading) visual.document().blocks().get(0);
        var body = (Paragraph) visual.document().blocks().get(1);
        var table = (Table) visual.document().blocks().get(2);
        assertEquals(18, heading.format().fontSize());
        assertEquals(0xAABBCC, heading.inlines().getFirst().style().rgb());
        assertEquals(0xAABBCC, heading.paragraph().vectors().getFirst().vector().rgb());
        assertEquals(10, body.format().fontSize());
        assertEquals(0x223344, body.vectors().getFirst().vector().rgb());
        var cell = table.rows().get(1).cells().getFirst();
        assertEquals(9, cell.format().fontSize());
        assertEquals(0x445566, cell.inlines().getFirst().style().rgb());
        assertEquals(0x445566, cell.vectors().getFirst().vector().rgb());

        var word = math.readDocx(SOURCE);
        assertEquals(3, word.document().equations().size());
        var headingRun = (DocxMath.Run) word.document().equations().get(0).expression();
        var bodyRun = (DocxMath.Run) word.document().equations().get(1).expression();
        var cellRun = (DocxMath.Run) word.document().equations().get(2).expression();
        assertEquals(18, headingRun.fontSize());
        assertEquals(0xAABBCC, headingRun.rgb());
        assertEquals(10, bodyRun.fontSize());
        assertEquals(9, cellRun.fontSize());
        assertEquals(0x445566, cellRun.rgb());
        assertTrue(visual.degradations().isEmpty());
        assertTrue(word.degradations().isEmpty());
    }

    @Test void sparseRequestsAndSubsequentDefaultsKeepUnchangedSemanticProperties() throws Exception {
        var defaults = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).formulas(true).build()
                .resolve(ContentOptions.fromTheme(theme()));
        var math = MathContent.builder().defaults(defaults)
                .defaults(ContentOptions.builder().spaceAfter(0).build()).build();
        var visual = math.compileVisual(parser -> parser.read("# Heading")
                .options(ContentOptions.builder().rgb(0).fontSize(10).build()).execute().document());
        var heading = (Heading) visual.document().blocks().getFirst();
        assertEquals(10, heading.format().fontSize());
        assertEquals(28, heading.format().lineHeight());
        assertEquals(0, heading.format().spaceAfter());
        assertEquals(0, heading.inlines().getFirst().style().rgb());
        var withFormula = math.compileVisual(parser -> parser.read("# Formula $x$")
                .options(ContentOptions.builder().rgb(0).fontSize(10).build()).execute().document());
        var formulaHeading = (Heading) withFormula.document().blocks().getFirst();
        assertEquals(10, formulaHeading.format().fontSize());
        assertEquals(0, formulaHeading.paragraph().vectors().getFirst().vector().rgb());
        assertTrue(formulaHeading.format().lineHeight() > 28, "formula objects retain their safety expansion");
        var unchanged = (Heading) math.readVisual("# Inherited").document().blocks().getFirst();
        assertEquals(18, unchanged.format().fontSize());
        assertEquals(0xAABBCC, unchanged.inlines().getFirst().style().rgb());
    }

    private static DocumentTheme theme() {
        return DocumentTheme.builder().bodyRgb(0x223344).headingRgb(1, 0xAABBCC).cellRgb(0x445566)
                .bodyFormat(p -> p.fontSize(10).lineHeight(22))
                .headingFormat(1, p -> p.fontSize(18).lineHeight(28))
                .cellFormat(c -> c.fontSize(9).lineHeight(20)).build();
    }
}
