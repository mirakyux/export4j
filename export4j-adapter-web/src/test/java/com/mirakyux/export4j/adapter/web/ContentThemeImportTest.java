// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.VectorResource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentThemeImportTest {
    private static final VectorResource VECTOR = VectorResource.of(8, 8, 0, List.of(
            new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0)),
            new VectorResource.Segment(VectorResource.Operation.LINE, List.of(8.0, -8.0)),
            new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of())));

    private record Input(ContentOptions.Format format, boolean rawHtml) { }
    private static final List<Input> INPUTS = List.of(new Input(ContentOptions.Format.MARKDOWN, false),
            new Input(ContentOptions.Format.MARKDOWN, true), new Input(ContentOptions.Format.HTML, false));

    @Test void allSixHeadingFormatsAndAppearancesSurviveMarkdownHtmlAndMixedMarkdown() throws Exception {
        var theme = theme();
        for (var input : INPUTS) {
            var source = new StringBuilder();
            for (int level = 1; level <= 6; level++) {
                source.append(input.format() == ContentOptions.Format.HTML
                        ? "<h" + level + ">Title " + level + "</h" + level + ">"
                        : "#".repeat(level) + " Title " + level + "\n\n");
            }
            var document = importer(theme, input).build().read(source.toString()).execute().document();
            assertEquals(6, document.blocks().size());
            for (int level = 1; level <= 6; level++) {
                var heading = (Heading) document.blocks().get(level - 1);
                assertEquals(level, heading.level());
                assertEquals(theme.headingFormat(level), heading.format());
                assertEquals("heading" + level, heading.inlines().getFirst().style().fontRole());
                assertEquals(0x220000 + level, heading.inlines().getFirst().style().rgb());
                assertEquals(30 - level, heading.inlines().getFirst().style().fontSize());
            }
        }
    }

    @Test void tableCellsUseTheirOwnThemeAppearanceAndKeepColumnAlignment() throws Exception {
        var theme = theme();
        for (var input : INPUTS) {
            String source = input.format() == ContentOptions.Format.HTML
                    ? "<table><thead><tr><th>Header</th></tr></thead><tbody><tr><td>Value</td></tr></tbody></table>"
                    : input.rawHtml() ? "| Header |\n|---|\n| Value |" : "| Header |\n|---:|\n| Value |";
            var document = importer(theme, input).build().read(source).execute().document();
            var cell = bodyCell(document);
            assertEquals(17, cell.format().fontSize()); assertEquals(29, cell.format().lineHeight());
            assertEquals(input.format() == ContentOptions.Format.HTML || input.rawHtml() ? ParagraphAlignment.CENTER : ParagraphAlignment.RIGHT,
                    cell.format().alignment());
            assertEquals(LineBreakPolicy.CJK_STRICT, cell.format().lineBreakPolicy());
            assertEquals("cell", cell.inlines().getFirst().style().fontRole());
            assertEquals(0x445566, cell.inlines().getFirst().style().rgb());
        }
    }

    @Test void ancestorCssOverridesThemeEvenWhenValuesEqualBodyDefaultsOrBlack() throws Exception {
        for (var input : List.of(new Input(ContentOptions.Format.HTML, false), new Input(ContentOptions.Format.MARKDOWN, true))) {
            var document = importer(theme(), input)
                    .fontFamily("Fixture Sans", "mapped", "mappedBold", "mappedItalic", "mappedBoth").build()
                    .read("<div style='font-family:Fixture Sans;font-size:12pt;line-height:24pt;color:black;text-align:left;orphans:1;widows:1'>"
                            + "<h1>Title</h1><table><tr><td>Value</td></tr></table></div>").execute().document();
            var heading = (Heading) document.blocks().getFirst();
            assertEquals(12, heading.format().fontSize()); assertEquals(24, heading.format().lineHeight());
            assertEquals(ParagraphAlignment.LEFT, heading.format().alignment());
            assertEquals(1, heading.format().pagination().minLinesBeforeBreak());
            assertEquals(1, heading.format().pagination().minLinesAfterBreak());
            assertEquals("mapped", heading.inlines().getFirst().style().fontRole());
            assertEquals(0, heading.inlines().getFirst().style().rgb());
            var cell = bodyCell(document);
            assertEquals(12, cell.format().fontSize()); assertEquals(24, cell.format().lineHeight());
            assertEquals(ParagraphAlignment.LEFT, cell.format().alignment());
            assertEquals("mapped", cell.inlines().getFirst().style().fontRole());
            assertEquals(0, cell.inlines().getFirst().style().rgb());
        }
    }

    @Test void localCssWinsOverAncestorCssAndCanClearAThemePageBreak() throws Exception {
        var theme = theme();
        var document = importer(theme, new Input(ContentOptions.Format.HTML, false))
                .fontFamily("Fixture Sans", "mapped", "mappedBold", "mappedItalic", "mappedBoth").build()
                .read("<div style='color:blue;font-size:12pt'><h6 style='color:black;font-family:Fixture Sans;font-size:18pt;line-height:30pt;break-before:auto'>Title</h6></div>")
                .execute().document();
        var heading = (Heading) document.blocks().getFirst();
        assertEquals(1, document.blocks().size());
        assertEquals(18, heading.format().fontSize()); assertEquals(30, heading.format().lineHeight());
        assertEquals("mapped", heading.inlines().getFirst().style().fontRole());
        assertEquals(0, heading.inlines().getFirst().style().rgb());
        assertFalse(heading.format().pagination().pageBreakBefore());
    }

    @Test void explicitContentDeclarationsOverrideSemanticDefaultsWithoutValueGuessing() throws Exception {
        var theme = theme();
        for (var input : INPUTS) {
            String source = input.format() == ContentOptions.Format.HTML
                    ? "<h1>Title</h1><table><tr><td>Value</td></tr></table>"
                    : "# Title\n\n| Header |\n|---|\n| Value |";
            var document = importer(theme, input).defaults(ContentOptions.builder().fontRole("body")
                    .rgb(0).fontSize(12).spaceBefore(0).build()).build().read(source).execute().document();
            var heading = (Heading) document.blocks().getFirst();
            assertEquals(12, heading.format().fontSize()); assertEquals(39, heading.format().lineHeight());
            assertEquals(0, heading.format().spaceBefore());
            assertEquals("body", heading.inlines().getFirst().style().fontRole());
            assertEquals(0, heading.inlines().getFirst().style().rgb());
            var cell = bodyCell(document);
            assertEquals("body", cell.inlines().getFirst().style().fontRole());
            assertEquals(0, cell.inlines().getFirst().style().rgb());
        }
    }

    @Test void formulasReceiveFinalHeadingBodyAndCellSizeAndColorInEveryGrammar() throws Exception {
        for (var input : INPUTS) {
            var seen = new ArrayList<String>();
            var importer = importer(theme(), input).defaults(ContentOptions.builder().formulas(true).build())
                    .formulaProcessor((tex, display, size, rgb, segments) -> {
                        seen.add(tex + ":" + size + ":" + Integer.toHexString(rgb));
                        return new InlineVector(VECTOR, tex, rgb, "");
                    }).build();
            String source = input.format() == ContentOptions.Format.HTML
                    ? "<h1>Title $h$</h1><p>Body $b$</p><table><tr><td>$c$</td></tr></table>"
                    : "# Title $h$\n\nBody $b$\n\n| Header |\n|---|\n| $c$ |";
            var document = importer.read(source).execute().document();
            assertEquals(List.of("h:29.0:220001", "b:12.0:123456", "c:17.0:445566"), seen);
            assertEquals("h", ((Heading) document.blocks().getFirst()).paragraph().vectors().getFirst().vector().logicalText());
            assertEquals("b", ((Paragraph) document.blocks().get(1)).vectors().getFirst().vector().logicalText());
            assertEquals("c", bodyCell(document).vectors().getFirst().vector().logicalText());
        }
    }

    @Test void unthemedContentAndOriginalLowLevelImportersKeepTheirHeadingDefaults() throws Exception {
        for (var input : INPUTS) {
            String source = input.format() == ContentOptions.Format.HTML ? "<h1>Title</h1>" : "# Title";
            var document = ContentImporter.builder().defaults(ContentOptions.builder().format(input.format()).rawHtml(input.rawHtml()).build())
                    .build().read(source).execute().document();
            var heading = (Heading) document.blocks().getFirst();
            assertEquals(ParagraphFormat.of(12), heading.format());
            assertEquals(0, heading.inlines().getFirst().style().rgb());
            assertEquals("", heading.inlines().getFirst().style().fontRole());
        }
        var markdown = MarkdownImporter.builder().paragraphFormat(ParagraphFormat.of(18)).build().read("# Title").execute();
        var html = HtmlImporter.builder().paragraphFormat(ParagraphFormat.of(18)).build().read("<h1>Title</h1>").execute();
        assertEquals(24, ((Heading) markdown.document().blocks().getFirst()).format().fontSize());
        assertEquals(24, ((Heading) html.document().blocks().getFirst()).format().fontSize());
        assertTrue(markdown.report().issues().isEmpty()); assertTrue(html.report().issues().isEmpty());
    }

    private static ContentImporter.Builder importer(DocumentTheme theme, Input input) {
        return ContentImporter.builder().defaults(ContentOptions.fromTheme(theme))
                .defaults(ContentOptions.builder().format(input.format()).rawHtml(input.rawHtml()).build());
    }

    private static TableCell bodyCell(Document document) {
        var table = (Table) document.blocks().stream().filter(Table.class::isInstance).findFirst().orElseThrow();
        return table.rows().getLast().cells().getFirst();
    }

    private static DocumentTheme theme() {
        var builder = DocumentTheme.builder().bodyFontRole("body").bodyRgb(0x123456)
                .bodyFormat(p -> p.fontSize(12).lineHeight(24))
                .cellFontRole("cell").cellRgb(0x445566)
                .cellFormat(CellFormat.builder().fontSize(17).lineHeight(29).alignment(ParagraphAlignment.CENTER)
                        .lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build());
        for (int level = 1; level <= 6; level++) {
            int current = level;
            builder.headingFontRole(level, "heading" + level).headingRgb(level, 0x220000 + level)
                    .headingFormat(level, p -> p.fontSize(30 - current).lineHeight(40 - current)
                            .spaceBefore(current).spaceAfter(current + 1).firstLineIndent(current).subsequentLineIndent(current + 2)
                            .alignment(ParagraphAlignment.CENTER).keepTogether(true)
                            .pagination(new PaginationPolicy(true, current == 6, 2, 3)).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).rotation(current));
        }
        return builder.build();
    }
}
