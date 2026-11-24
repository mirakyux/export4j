// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.math.api.MathException;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathContentAppearanceTest {
    private static final String TEX = "\\notASupportedCommand{x}";
    private static final String LITERAL = "$" + TEX + "$";

    @Test void textFallbackKeepsTheDeclaredHostRoleAcrossFormatsAndTargets() throws Exception {
        for (var format : List.of(ContentOptions.Format.TEXT, ContentOptions.Format.MARKDOWN, ContentOptions.Format.HTML)) {
            for (boolean rawHtml : format == ContentOptions.Format.MARKDOWN ? List.of(false, true) : List.of(false)) {
                var declarations = ContentOptions.builder().format(format).rawHtml(rawHtml)
                        .fontRole("report").fontSize(14).rgb(0x335577).build();
                var math = compiler().defaults(declarations).build();
                String source = format == ContentOptions.Format.HTML ? "<p>Before " + LITERAL + " after</p>"
                        : "Before " + LITERAL + " after";
                var result = math.read(source);
                var expected = new InlineStyle("report", 14, 0x335577, 0, false, false);
                assertBothStyles(result, List.of(expected));
                assertEquals(1, result.formulas().size());
                assertEquals(2, result.degradations().size());
                assertTrue(result.degradations().stream().allMatch(value -> value.representation() == MathContent.Fallback.TEXT
                        && value.reason() == MathContent.Reason.UNSUPPORTED_FORMULA));
                assertTrue(result.docx().equations().isEmpty());
            }
        }
    }

    @Test void eachMarkdownOccurrenceKeepsItsResolvedVariantAndDecoration() throws Exception {
        var math = compiler().defaults(ContentOptions.builder().fontRole("report").rgb(0x224466).build()).build();
        var result = math.read(LITERAL + " **" + LITERAL + "** _" + LITERAL + "_ ***" + LITERAL + "*** ~~" + LITERAL + "~~");
        assertEquals(5, result.formulas().size());
        assertBothStyles(result, List.of(
                new InlineStyle("report", 12, 0x224466, 0, false, false),
                new InlineStyle("report_bold", 12, 0x224466, 0, false, false),
                new InlineStyle("report_italic", 12, 0x224466, 0, false, false),
                new InlineStyle("report_boldItalic", 12, 0x224466, 0, false, false),
                new InlineStyle("report", 12, 0x224466, 0, false, true)));
        assertEquals(10, result.degradations().size());
    }

    @Test void htmlCssAndAnExplicitFormulaLinkSurviveBothTextProjections() throws Exception {
        for (var format : List.of(ContentOptions.Format.HTML, ContentOptions.Format.MARKDOWN)) {
            var math = compiler().defaults(ContentOptions.builder().format(format).rawHtml(true).build()).build();
            var result = math.compile(importer -> {
                var parsed = importer.read("<p><span style='font-family:Report;font-size:15pt;color:#224466;"
                        + "font-weight:bold;font-style:italic;text-decoration:underline line-through'>"
                        + LITERAL + "</span></p>").execute().document();
                var paragraph = (Paragraph) parsed.blocks().getFirst();
                var content = new ArrayList<InlineContent>();
                for (var inline : paragraph.content()) {
                    content.add(inline instanceof InlineVector vector
                            ? new InlineVector(vector.resource(), vector.logicalText(), vector.rgb(), "https://example.org/formula") : inline);
                }
                return Document.builder().add(Paragraph.mixed(paragraph.id(), content, paragraph.format())).build();
            });
            assertBothStyles(result, List.of(new InlineStyle("report_boldItalic", 15, 0x224466, 0, true, true)));
            for (var paragraph : paragraphs(result)) {
                var run = fallbackRuns(paragraph).getFirst();
                assertEquals("https://example.org/formula", run.hyperlink());
                assertEquals(LITERAL, run.text());
            }
        }
    }

    @Test void xmlLexicalContentOverridesAreCapturedBeforeTheSourceSessionCloses() throws Exception {
        var result = compiler().build().compile(importer -> XmlTemplate.builder().contentParser(importer).build().compile("""
                <document version="1" content-format="MARKDOWN" formulas="true">
                  <scope role="report" color="#335577" size="15">
                    <content id="scoped"><![CDATA[**%s**]]></content>
                  </scope>
                </document>
                """.formatted(LITERAL)).render(Map.of()));
        assertBothStyles(result, List.of(new InlineStyle("report_bold", 15, 0x335577, 0, false, false)));
        assertEquals(2, result.degradations().size());
    }

    @Test void strictFormulaFailuresStillRejectWithoutAnExplicitCandidate() {
        var math = MathContent.builder().defaults(ContentOptions.builder().fontRole("report").build())
                .fontFamily("Report", "report", "report_bold", "report_italic", "report_boldItalic").build();
        assertThrows(MathException.class, () -> math.read(LITERAL));
        assertThrows(MathException.class, () -> math.readVisual(LITERAL));
        assertThrows(MathException.class, () -> math.readDocx(LITERAL));
    }

    @Test void anOversizedValidFormulaUsesTheSameHostStyleForItsTextCandidate() throws Exception {
        String tex = "abcdefghij";
        double width = TexMath.vector(tex).resource().width();
        var page = PageSettings.builder().size(width / 2 + 20, 200).margins(10).build();
        var result = compiler().defaults(ContentOptions.builder().fontRole("report").rgb(0x663311).build())
                .pageSettings(page).build().read("$" + tex + "$");
        for (var paragraph : paragraphs(result)) {
            var run = paragraph.content().stream().filter(InlineText.class::isInstance).map(InlineText.class::cast).findFirst().orElseThrow();
            assertEquals("$" + tex + "$", run.text());
            assertEquals(new InlineStyle("report", 12, 0x663311, 0, false, false), run.style());
        }
        assertEquals(2, result.degradations().size());
        assertTrue(result.degradations().stream().allMatch(value -> value.reason() == MathContent.Reason.TOO_WIDE));
    }

    private static MathContent.Builder compiler() {
        return MathContent.builder().fontFamily("Report", "report", "report_bold", "report_italic", "report_boldItalic")
                .fallbacks(List.of(MathContent.Fallback.TEXT));
    }

    private static List<Paragraph> paragraphs(MathContent.Result result) {
        return List.of((Paragraph) result.document().blocks().getFirst(),
                (Paragraph) result.docx().semantic().body().sections().getFirst().blocks().getFirst());
    }

    private static List<InlineText> fallbackRuns(Paragraph paragraph) {
        return paragraph.content().stream().filter(InlineText.class::isInstance).map(InlineText.class::cast)
                .filter(run -> run.text().contains(TEX)).toList();
    }

    private static void assertBothStyles(MathContent.Result result, List<InlineStyle> expected) {
        for (var paragraph : paragraphs(result)) {
            assertEquals(expected, fallbackRuns(paragraph).stream().map(InlineText::style).toList());
        }
    }
}
