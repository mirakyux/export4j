// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.docx.web;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxMathContentTest {
    private static final String MATH_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void compilesMarkdownWithEditableEquationsInBodyAndTable() throws Exception {
        String source = """
                Before $x^2$ and $x^2$ with `$literal$`.

                | Header $y$ | Plain |
                | --- | --- |
                | $y$ | Content |

                $$
                \\frac{1}{2}
                $$
                """;
        var document = DocxMathContent.read(source, ContentOptions.Format.MARKDOWN);
        var equations = document.equations();
        assertEquals(5, equations.size());
        assertEquals(equations.get(0).expression(), equations.get(1).expression());
        assertNotEquals(equations.get(0).anchor(), equations.get(1).anchor());
        assertEquals(DocxMathDocument.Placement.DISPLAY_CENTER, equations.getLast().placement());
        assertInstanceOf(DocxMath.Fraction.class, equations.getLast().expression());

        var blocks = document.semantic().body().sections().getFirst().blocks();
        var paragraph = assertInstanceOf(Paragraph.class, blocks.getFirst());
        assertEquals("Before \ufffc and \ufffc with $literal$.", paragraph.text());
        assertTrue(paragraph.vectors().isEmpty());
        assertInstanceOf(Table.class, blocks.get(1));

        var output = new ByteArrayOutputStream();
        new DocxAdapter().writeMath(document, output);
        var body = documentXml(output.toByteArray());
        assertEquals(5, body.getElementsByTagNameNS(MATH_NS, "oMath").getLength());
        assertEquals(2, body.getElementsByTagNameNS(MATH_NS, "sSup").getLength());
        assertEquals(1, body.getElementsByTagNameNS(MATH_NS, "f").getLength());
        assertEquals(1, body.getElementsByTagNameNS(MATH_NS, "oMathPara").getLength());
        assertEquals(0, body.getElementsByTagNameNS(WORD_NS, "drawing").getLength());
    }

    @Test void htmlUsesSourceStylesRolesAndMetadata() throws Exception {
        var metadata = new DocumentMetadata("Native formula report", "Example author");
        var document = DocxMathContent.read("<p>Before <strong style=\"font-size:18pt;color:#2457a4\">$x$</strong> after</p>",
                ContentOptions.Format.HTML, MathOptions.DEFAULT, importer -> importer.metadata(metadata));
        assertEquals(metadata, document.semantic().body().metadata());
        var paragraph = assertInstanceOf(Paragraph.class, document.semantic().body().sections().getFirst().blocks().getFirst());
        var marker = paragraph.inlines().stream().filter(run -> run.text().equals("\ufffc")).findFirst().orElseThrow();
        assertEquals("bold", marker.style().fontRole());
        assertEquals(18, marker.style().fontSize());
        var run = assertInstanceOf(DocxMath.Run.class, document.equations().getFirst().expression());
        assertEquals(DocxMath.Style.BOLD_ITALIC, run.style());
        assertEquals(18, run.fontSize());
        assertEquals(0x2457a4, run.rgb());
    }

    @Test void rejectsUnsupportedMathBudgetViolationsAndCancellation() {
        var unsupported = assertThrows(MathException.class,
                () -> DocxMathContent.read("A $\\overline{x}$.", ContentOptions.Format.MARKDOWN));
        assertEquals(MathException.Reason.UNSUPPORTED_SYNTAX, unsupported.reason());

        var limit = assertThrows(MathException.class, () -> DocxMathContent.read("A $x$ and $y$.",
                ContentOptions.Format.MARKDOWN, MathOptions.DEFAULT, importer -> importer.maxFormulas(1)));
        assertEquals(MathException.Reason.LIMIT_EXCEEDED, limit.reason());

        var controls = ExecutionOptions.builder().cancelled(() -> true).build();
        var cancelled = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(controls,
                () -> DocxMathContent.read("A $x$.", ContentOptions.Format.MARKDOWN)));
        assertEquals(ExecutionException.Code.CANCELLED, cancelled.code());
    }

    private static org.w3c.dom.Document documentXml(byte[] docx) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (var part = zip.getNextEntry(); part != null; part = zip.getNextEntry()) {
                if (part.getName().equals("word/document.xml")) {
                    return DocumentBuilderFactory.newDefaultNSInstance().newDocumentBuilder()
                            .parse(new ByteArrayInputStream(zip.readAllBytes()));
                }
            }
        }
        throw new AssertionError("Word package is missing document.xml");
    }
}
