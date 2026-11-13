// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.VectorResource;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineVectorImportExportTest {
    private static InlineVector vector(String logical) {
        return new InlineVector(VectorResource.of(12, 6, 3, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, -6.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(12.0, -6.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(12.0, 3.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(0.0, 3.0)),
                new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of()))), logical);
    }
    private static ProcessingPolicy degrade() {
        return ProcessingPolicy.builder().feature("INLINE_VECTOR", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE).build();
    }
    @Test void htmlRegistrationSnapshotsPathsLogicalTextAndSourceMarker() throws Exception {
        var expected = vector("x+y");
        var builder = HtmlImporter.builder().vector("formula:one", expected);
        var importer = builder.build(); builder.vector("formula:one", vector("replacement"));
        var result = importer.read("<p>A<a href='https://example.com/math'><img src='formula:one' alt='wrong' width='16' height='12' style='width:12pt;height:9pt'></a>B</p>").execute();
        var paragraph = (Paragraph) result.document().blocks().getFirst();
        assertEquals("A\uFFFCB", paragraph.text());
        assertEquals(1, paragraph.vectors().getFirst().sourceIndex());
        var actual = paragraph.vectors().getFirst().vector();
        assertEquals(expected.resource(), actual.resource()); assertEquals("x+y", actual.logicalText());
        assertEquals("https://example.com/math", actual.hyperlink());
        assertTrue(paragraph.images().isEmpty()); assertTrue(result.report().issues().isEmpty());
        // Shared conversion copies the same resource environment, rather than dropping registration.
        var converted = importer.conversion("<p><img src='formula:one'></p>").policy(degrade())
                .prepare(new HtmlAdapter().conversionTarget());
        var bytes = new ByteArrayOutputStream(); converted.to(bytes);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("x+y"));
    }
    @Test void conflictingDimensionsAlwaysFailBeforeLossPoliciesCanDiscardThem() {
        for (String declarations : List.of("width='17'", "height='13'", "style='width:13pt'",
                "style='height:10pt'", "width='17' style='width:12pt'")) {
            var importer = HtmlImporter.builder().vector("formula:one", vector("x+y"))
                    .policy(ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build()).build();
            assertThrows(IllegalArgumentException.class,
                    () -> importer.read("<p><img src='formula:one' " + declarations + "></p>").execute());
        }
    }
    @Test void registeredImagesAndVectorsCannotClaimTheSameKeyInEitherOrder() throws Exception {
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", encoded);
        var image = new InlineImage(ImageResource.load(encoded.toByteArray()), 12, 9, 0, "raster");
        assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().image("formula:one", image).vector("formula:one", vector("x+y")));
        assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().vector("formula:one", vector("x+y")).image("formula:one", image));
        assertThrows(IllegalArgumentException.class, () -> MarkdownImporter.builder().image("formula:one", image).vector("formula:one", vector("x+y")));
        assertThrows(IllegalArgumentException.class, () -> MarkdownImporter.builder().vector("formula:one", vector("x+y")).image("formula:one", image));
    }
    @Test void markdownAndHtmlKeepRegisteredVectorsInsideHeadingsWithoutDegradation() throws Exception {
        var result = MarkdownImporter.builder().vector("formula:one", vector("x+y")).build()
                .read("A[![wrong](formula:one)](https://example.com/math)B").execute();
        var paragraph = (Paragraph) result.document().blocks().getFirst();
        assertEquals("A\uFFFCB", paragraph.text()); assertEquals("x+y", paragraph.vectors().getFirst().vector().logicalText());
        assertEquals("https://example.com/math", paragraph.vectors().getFirst().vector().hyperlink());
        assertTrue(result.report().issues().isEmpty());
        var heading = MarkdownImporter.builder().vector("formula:one", vector("x+y")).build()
                .read("# ![wrong](formula:one)").execute();
        assertEquals("x+y", ((com.mirakyux.export4j.model.Heading) heading.document().blocks().getFirst()).vectors().getFirst().vector().logicalText());
        assertTrue(heading.report().issues().isEmpty());
        var html = HtmlImporter.builder().vector("formula:one", vector("x+y"))
                .build().read("<h1><img src='formula:one'></h1>").execute();
        assertEquals("x+y", ((com.mirakyux.export4j.model.Heading) html.document().blocks().getFirst()).vectors().getFirst().vector().logicalText());
        assertTrue(html.report().issues().isEmpty());
        var colored = new InlineVector(vector("x+y").resource(), "x+y", 0x123456, "https://example.com/math");
        for (var imported : List.of(
                HtmlImporter.builder().vector("formula:one", colored).policy(degrade()).build().read("<h1><img src='formula:one'></h1>").execute(),
                MarkdownImporter.builder().vector("formula:one", colored).policy(degrade()).build().read("# ![wrong](formula:one)").execute())) {
            var kept = ((com.mirakyux.export4j.model.Heading) imported.document().blocks().getFirst()).vectors().getFirst().vector();
            assertEquals(colored, kept); assertTrue(imported.report().issues().isEmpty());
        }
    }
    @Test void webExportRejectsOrReportsActualLogicalTextDegradeAcrossBodyAndCell(@TempDir Path directory) throws Exception {
        var v = new InlineVector(vector("x+y").resource(), "x+y", 0x123456, "https://example.com/math");
        var paragraph = Paragraph.mixed("body", List.of(new InlineText("A", InlineStyle.of(12)), v,
                new InlineText("B", InlineStyle.of(12))), ParagraphFormat.of(12));
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1))
                .headerCells(TableCell.builder().content(List.of(v)).build()).row("body").build();
        var document = Document.builder().add(paragraph).add(table).build();
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            assertEquals("INLINE_VECTOR", assertThrows(ProcessingException.class,
                    () -> DocumentConversion.from(document).prepare(target)).issue().feature());
            var prepared = DocumentConversion.from(document).policy(degrade()).prepare(target);
            var bytes = new ByteArrayOutputStream(); var result = prepared.to(bytes);
            var losses = result.report().losses().stream().filter(issue -> issue.feature().equals("INLINE_VECTOR")).toList();
            assertEquals(2, losses.size()); assertEquals(prepared.preview().issues(), result.report().issues());
            assertTrue(losses.stream().allMatch(issue -> issue.effects().equals(List.of("VECTOR_REPLACED_WITH_LOGICAL_TEXT"))));
            assertEquals(1, losses.getFirst().location().startUtf16()); assertEquals(2, losses.getFirst().location().endUtf16());
            assertEquals("table", losses.getLast().location().cellPath().getFirst().tableId());
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(target.format().equals("HTML") ? "x+y" : "x\\+y"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("https://example.com/math"));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("<svg"));
            for (var treatment : List.of(ProcessingPolicy.Treatment.PRESERVE, ProcessingPolicy.Treatment.DISCARD)) {
                var unavailable = ProcessingPolicy.builder().feature("INLINE_VECTOR", ProcessingPolicy.Action.WARN, treatment).build();
                assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(document).policy(unavailable).prepare(target));
            }
        }
        Path target = directory.resolve("old.html"); Files.writeString(target, "old");
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(document).to(target));
        assertEquals("old", Files.readString(target));
        var noReport = HtmlAdapter.builder().policy(ExportPolicy.builder().defaultAction(UnsupportedAction.IGNORE).build()).build();
        assertThrows(ExportException.class, () -> noReport.export(document).to(target));
        assertEquals("old", Files.readString(target));
    }
}
