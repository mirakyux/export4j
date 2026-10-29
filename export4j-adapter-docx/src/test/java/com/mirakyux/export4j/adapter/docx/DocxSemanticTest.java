// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Axis;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Comment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Contents;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Control;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ControlKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ControlOptions;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Drawing;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.ListItem;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Picture;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Placement;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Point;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Range;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RelativeTo;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Revision;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.RevisionKind;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.TextBox;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Transform;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.VerticalAlignment;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.Wrap;
import static com.mirakyux.export4j.adapter.docx.DocxSemanticDocument.WrapSide;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DocxSemanticTest {
    private static final DocxAdapter ADAPTER = new DocxAdapter();
    private static final OffsetDateTime DATE = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static Placement floating() { return new Placement(Axis.offset(RelativeTo.MARGIN, 12), Axis.offset(RelativeTo.PARAGRAPH, 8), Wrap.SQUARE, WrapSide.BOTH, List.of(), false, true, true, 4, 3, 4, 5, 6); }
    private static Paragraph p(String id, String text) { return new Paragraph(id, text, ParagraphFormat.of(12)); }
    private static SectionedDocument body(List<Block> blocks) { return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("body", PageSettings.A4, blocks, 0))); }
    private static byte[] encode(DocxSemanticDocument doc) throws IOException { var out = new ByteArrayOutputStream(); ADAPTER.writeSemantic(doc, out); return out.toByteArray(); }
    private static DocxSemanticDocument decode(byte[] encoded) throws IOException { return ADAPTER.readSemantic(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT).document(); }
    private static InlineImage image() throws IOException {
        var pixels = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB); for (int y = 0; y < 2; y++) { for (int x = 0; x < 4; x++) { pixels.setRGB(x, y, x < 2 ? 0xff0000 : 0x0000ff); } }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(pixels, "png", bytes); return new InlineImage(ImageResource.load(bytes.toByteArray()), 48, 24, 0, "Two colors");
    }
    private static DocxSemanticDocument source() throws IOException {
        var image = image(); var imageParagraph = Paragraph.mixed("image", List.of(new InlineText("A", InlineStyle.of(12)), image, new InlineText("B", InlineStyle.of(12))), ParagraphFormat.of(12));
        var blocks = List.<Block>of(new Heading("h", "Native heading", 1), p("toc", "Native heading\t1"), p("review", "old secret inserted"), p("a", "comment starts"), p("b", "comment ends"), p("control", "Alice"), p("legacy", "default"), imageParagraph, p("box-anchor", "textbox anchor"));
        return new DocxSemanticDocument(body(blocks), List.of(new Drawing("picture", new Point("image", 1), floating(), new Picture(image, new Transform(90, true, false, .5, 0, 0, 0))),
                new Drawing("box", new Point("box-anchor", 7), floating(), new TextBox(List.of(p("box-p", "Editable textbox")), 180, 72, 4, 6, 4, 6, VerticalAlignment.CENTER, 0xffffee, 0x336699, 1, Transform.NONE))),
                List.of(new Comment("c", new Range(new Point("a", 0), new Point("b", 7)), "Reviewer", "RV", DATE, List.of(p("comment-p", "A real comment")))),
                List.of(new Revision("deleted", RevisionKind.DELETE, Range.of("review", 4, 10), "Reviewer", DATE, "", null, null), new Revision("inserted", RevisionKind.INSERT, Range.of("review", 11, 19), "Reviewer", DATE, "", null, null)),
                List.of(new Control("name", ControlKind.PLAIN_TEXT, Range.of("control", 0, 5), "name", "Name", false, List.of(), "", "", false),
                    new Control("form", ControlKind.LEGACY_TEXT, Range.of("legacy", 0, 7), "legacy-name", "legacy-name", false, List.of(), "", "", false, new ControlOptions("default", 12, "regular", "", "Help", "Status", true, true, 0, false, 0))),
                List.of(new Contents("toc-field", Range.of("toc", 0, 16), 1, 3, true, true, true)));
    }
    @Test void freshStandardPackageRestoresTypedDrawingsReviewControlsAndToc() throws IOException {
        var doc = source(); var encoded = encode(doc); var parts = ADAPTER.unzip(new ByteArrayInputStream(encoded)); String main = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        assertTrue(main.contains("wp:anchor")); assertTrue(main.contains("wps:txbx")); assertTrue(main.contains("w:delText")); assertTrue(main.contains("w:ffData")); assertTrue(main.contains("w:sdtContent")); assertTrue(main.contains("TOC"));
        var restored = decode(encoded); assertEquals(2, restored.drawings().size()); assertEquals(1, restored.comments().size()); assertEquals(2, restored.revisions().size()); assertEquals(2, restored.controls().size()); assertEquals(1, restored.contents().size());
        assertEquals(doc.drawings(), restored.drawings()); assertEquals(doc.comments(), restored.comments()); assertEquals(doc.controls().get(1).options(), restored.controls().get(1).options());
        assertEquals("Editable textbox", ((Paragraph)((TextBox)restored.drawings().stream().filter(d -> d.id().equals("box")).findFirst().orElseThrow().content()).blocks().getFirst()).text());
        assertThrows(IOException.class, () -> ADAPTER.read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT));
        assertFalse(ADAPTER.read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.ALLOW_LOSS).diagnostics().isEmpty());
    }
    @Test void trackedRemovalAndEditsRebaseActualUtf16AnchorsWithoutRetainingDeletedSource() throws IOException {
        var doc = decode(encode(source()));
        var edited = doc.edit().acceptRevision("deleted").rejectRevision("inserted").setControlText("name", "Alexander").replaceText("box-p", 0, 8, "Revised").replaceText("a", 0, 0, "prefix ").execute().document();
        assertEquals("old  ", ((Paragraph)edited.body().sections().getFirst().blocks().get(2)).text()); assertTrue(edited.revisions().isEmpty());
        assertEquals(9, edited.controls().stream().filter(c -> c.id().equals("name")).findFirst().orElseThrow().range().end().utf16());
        assertEquals(0, edited.comments().getFirst().range().start().utf16());
        byte[] encoded = encode(edited); var parts = ADAPTER.unzip(new ByteArrayInputStream(encoded));
        for (var entry : parts.entrySet()) { if (entry.getKey().endsWith(".xml")) { String text = new String(entry.getValue(), StandardCharsets.UTF_8); assertFalse(text.contains("secret")); assertFalse(text.contains("inserted")); } }
        assertEquals(edited.comments().getFirst().range(), decode(encoded).comments().getFirst().range());
    }
    @Test void crossParagraphCommentInsideRichControlDoesNotSplitItsNativeIdentity() throws IOException {
        var doc = new DocxSemanticDocument(body(List.of(p("start", "First"), p("rich", "Rich text value"))), List.of(),
                List.of(new Comment("comment", new Range(new Point("start", 0), new Point("rich", 4)), "Reviewer", "RV", DATE, List.of(p("comment-story", "Comment value")))), List.of(),
                List.of(new Control("rich-control", ControlKind.RICH_TEXT, Range.of("rich", 0, 15), "rich", "Rich", false, List.of(), "", "", false)), List.of());
        byte[] bytes = encode(doc); var parts = ADAPTER.unzip(new ByteArrayInputStream(bytes)); var root = DocxAdapter.parse(parts.get("word/document.xml"));
        assertEquals(1, DocxSemanticXml.all(root, DocxSemanticXml.W, "sdt").size());
        var restored = decode(bytes); assertEquals(doc.controls(), restored.controls()); assertEquals(doc.comments(), restored.comments());
    }
    @Test void nativeFormCurrentValuesAndExplicitTocCacheSurviveWithoutAutomaticGlobalUpdates() throws IOException {
        var page = Paragraph.mixed("page", List.of(new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12))), ParagraphFormat.of(12));
        var body = body(List.of(new Heading("heading", "Heading", 1), p("toc", "Heading\t1"), p("text", "CURRENT"), p("list", "Second"), page));
        var controls = List.of(new Control("text-input", ControlKind.LEGACY_TEXT, Range.of("text", 0, 7), "TextInput", "", false, List.of(), "", "", false,
                        new ControlOptions("DEFAULT", 20, "regular", "", "", "", true, true, 0, false, 0)),
                new Control("list-input", ControlKind.LEGACY_DROP_DOWN, Range.of("list", 0, 6), "ListInput", "", false,
                        List.of(new ListItem("First", "First"), new ListItem("Second", "Second")), "", "", false));
        var source = new DocxSemanticDocument(body, List.of(), List.of(), List.of(), controls,
                List.of(new Contents("toc", Range.of("toc", 0, 9), 1, 3, true, true, true)));
        assertTrue(decode(encode(source)).contents().getFirst().dirty());
        assertTrue(source.edit().setControlText("text-input", "EXPLICIT").execute().document().contents().getFirst().dirty());
        var updated = source.edit().setControlText("text-input", "EDITED").updateContents("toc", Map.of("heading", 3)).execute().document();
        assertFalse(updated.contents().getFirst().dirty());
        var rebased = updated.edit().replaceText("heading", 0, 7, "Changed heading").execute().document();
        assertFalse(rebased.contents().getFirst().dirty());
        assertEquals("Heading\t3", DocxSemanticSupport.paragraphs(rebased.body(), rebased.drawings()).get("toc").text());
        var parts = ADAPTER.unzip(new ByteArrayInputStream(encode(updated))); var root = DocxAdapter.parse(parts.get("word/document.xml"));
        assertTrue(DocxSemanticXml.all(root, DocxSemanticXml.W, "fldChar").stream().noneMatch(field -> field.hasAttributeNS(DocxSemanticXml.W, "fldLock")));
        assertTrue(DocxSemanticXml.all(root, DocxSemanticXml.W, "fldChar").stream().noneMatch(field -> field.hasAttributeNS(DocxSemanticXml.W, "dirty")));
        var fields = DocxSemanticXml.all(root, DocxSemanticXml.W, "fldSimple"); assertEquals(1, fields.size()); assertEquals("PAGE", fields.getFirst().getAttributeNS(DocxSemanticXml.W, "instr"));
        assertFalse(fields.getFirst().hasAttributeNS(DocxSemanticXml.W, "dirty"));
        var settings = DocxAdapter.parse(parts.get("word/settings.xml")); assertTrue(DocxSemanticXml.all(settings, DocxSemanticXml.W, "updateFields").isEmpty());
        var list = DocxSemanticXml.all(root, DocxSemanticXml.W, "ddList").getFirst();
        assertEquals("1", DocxAdapter.child(list, "result").getAttributeNS(DocxSemanticXml.W, "val"));
        assertEquals("0", DocxAdapter.child(list, "default").getAttributeNS(DocxSemanticXml.W, "val"));
        var roundTrip = decode(encode(updated)); assertFalse(roundTrip.contents().getFirst().dirty());
        assertEquals("DEFAULT", roundTrip.controls().stream().filter(c -> c.id().equals("text-input")).findFirst().orElseThrow().options().defaultText());
        assertEquals("EDITED", DocxSemanticSupport.paragraphs(roundTrip.body(), roundTrip.drawings()).get("text").text());
        var ordinary = new ByteArrayOutputStream(); ADAPTER.write(body, ordinary);
        var ordinaryParts = ADAPTER.unzip(new ByteArrayInputStream(ordinary.toByteArray()));
        assertTrue(new String(ordinaryParts.get("word/document.xml"), StandardCharsets.UTF_8).contains("w:dirty=\"true\""));
        assertTrue(new String(ordinaryParts.get("word/settings.xml"), StandardCharsets.UTF_8).contains("w:updateFields"));
        assertThrows(IllegalArgumentException.class, () -> source.edit().replaceText("list", 0, 6, "Unavailable").execute());
    }
    @Test void movedSourceUsesStandardTextAndReviewDatesIncludeSeconds() throws IOException {
        var revisions = List.of(new Revision("from", RevisionKind.MOVE_FROM, Range.of("from-p", 0, 5), "Reviewer", DATE, "move-pair", null, null),
                new Revision("to", RevisionKind.MOVE_TO, Range.of("to-p", 0, 5), "Reviewer", DATE, "move-pair", null, null));
        var doc = new DocxSemanticDocument(body(List.of(p("from-p", "Moved"), p("to-p", "Moved"))), List.of(), List.of(), revisions, List.of(), List.of());
        byte[] bytes = encode(doc); var parts = ADAPTER.unzip(new ByteArrayInputStream(bytes)); var root = DocxAdapter.parse(parts.get("word/document.xml"));
        var from = DocxSemanticXml.all(root, DocxSemanticXml.W, "moveFrom").getFirst();
        assertEquals(1, DocxSemanticXml.all(from, DocxSemanticXml.W, "t").size()); assertTrue(DocxSemanticXml.all(from, DocxSemanticXml.W, "delText").isEmpty());
        assertEquals("2026-01-01T00:00:00Z", from.getAttributeNS(DocxSemanticXml.W, "date")); assertEquals(revisions, decode(bytes).revisions());
        var comments = ADAPTER.unzip(new ByteArrayInputStream(encode(source()))); var commentRoot = DocxAdapter.parse(comments.get("word/comments.xml"));
        assertEquals("2026-01-01T00:00:00Z", DocxSemanticXml.all(commentRoot, DocxSemanticXml.W, "comment").getFirst().getAttributeNS(DocxSemanticXml.W, "date"));
    }
    @Test void priorParagraphPropertiesInheritCurrentFontSizeAndRejectUnmappedFontChanges() throws IOException {
        var current = p("paragraph", "Text"); var prior = current.format().toBuilder().alignment(com.mirakyux.export4j.model.ParagraphAlignment.CENTER).build();
        var revision = new Revision("paragraph-review", RevisionKind.PARAGRAPH_FORMAT, Range.of("paragraph", 0, 4), "Reviewer", DATE, "", null, prior);
        var doc = new DocxSemanticDocument(body(List.of(current)), List.of(), List.of(), List.of(revision), List.of(), List.of());
        byte[] bytes = encode(doc); var parts = ADAPTER.unzip(new ByteArrayInputStream(bytes)); var root = DocxAdapter.parse(parts.get("word/document.xml"));
        var old = DocxAdapter.child(DocxSemanticXml.all(root, DocxSemanticXml.W, "pPrChange").getFirst(), "pPr");
        assertEquals(null, DocxAdapter.child(old, "rPr")); assertEquals(prior, decode(bytes).revisions().getFirst().priorParagraphFormat());
        var rejected = decode(bytes).edit().rejectRevision("paragraph-review").execute().document();
        assertEquals(prior, ((Paragraph)rejected.body().sections().getFirst().blocks().getFirst()).format());
        var changedSize = new Revision("font-change", RevisionKind.PARAGRAPH_FORMAT, revision.range(), "Reviewer", DATE, "", null, prior.toBuilder().fontSize(14).build());
        assertThrows(IllegalArgumentException.class, () -> new DocxSemanticDocument(doc.body(), List.of(), List.of(), List.of(changedSize), List.of(), List.of()));
        old.appendChild(DocxSemanticXml.word(old, "rPr")); parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        assertThrows(IOException.class, () -> decode(DocxSemanticXml.zip(parts, DocxLimits.DEFAULT)));
    }
    @Test void pictureControlsUseRealInlineImagesAndRejectFloatingDrawingCombinations() throws IOException {
        var source = source(); var control = new Control("picture-control", ControlKind.PICTURE, Range.of("image", 1, 2), "picture", "Picture", false, List.of(), "", "", false);
        assertThrows(IllegalArgumentException.class, () -> new DocxSemanticDocument(source.body(), source.drawings(), List.of(), List.of(), List.of(control), List.of()));
        var inline = new DocxSemanticDocument(source.body(), List.of(), List.of(), List.of(), List.of(control), List.of());
        assertEquals(List.of(control), decode(encode(inline)).controls());
        var inlineParts = ADAPTER.unzip(new ByteArrayInputStream(encode(inline))); var inlineRoot = DocxAdapter.parse(inlineParts.get("word/document.xml"));
        for (var drawing : DocxSemanticXml.all(inlineRoot, DocxSemanticXml.WP, "inline")) { drawing.setAttributeNS("http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing", "wp14:editId", "1234ABCD"); }
        inlineParts.put("word/document.xml", DocxSemanticXml.encode(inlineRoot, DocxLimits.DEFAULT.maxEntryBytes()));
        assertEquals(List.of(control), decode(DocxSemanticXml.zip(inlineParts, DocxLimits.DEFAULT)).controls());
        var text = new Control("bad-picture", ControlKind.PICTURE, Range.of("control", 0, 1), "", "", false, List.of(), "", "", false);
        assertThrows(IllegalArgumentException.class, () -> new DocxSemanticDocument(source.body(), List.of(), List.of(), List.of(), List.of(text), List.of()));
    }
    @Test void groupIntrinsicLockKeepsOrdinaryTextLockedAndAllowsNestedEditableControls() throws IOException {
        var group = new Control("group", ControlKind.GROUP, Range.of("group-p", 0, 14), "group", "Group", false, List.of(), "", "", true);
        var child = new Control("child", ControlKind.PLAIN_TEXT, Range.of("group-p", 5, 10), "name", "Name", false, List.of(), "", "", false);
        var doc = new DocxSemanticDocument(body(List.of(p("group-p", "LOCK Alice END"))), List.of(), List.of(), List.of(), List.of(group, child), List.of());
        var restored = decode(encode(doc)); assertEquals(2, restored.controls().size());
        assertThrows(IllegalArgumentException.class, () -> restored.edit().setControlText("group", "Bypass").execute());
        assertThrows(IllegalArgumentException.class, () -> restored.edit().replaceText("group-p", 0, 4, "OPEN").execute());
        assertThrows(IllegalArgumentException.class, () -> restored.edit().replaceText("group-p", 3, 12, "Bypass").execute());
        var edited = restored.edit().setControlText("child", "Bob").execute().document();
        assertEquals("LOCK Bob END", ((Paragraph)edited.body().sections().getFirst().blocks().getFirst()).text());
        assertEquals(2, decode(encode(edited)).controls().size());
        var parts = ADAPTER.unzip(new ByteArrayInputStream(encode(doc))); var root = DocxAdapter.parse(parts.get("word/document.xml"));
        for (var pr : DocxSemanticXml.all(root, DocxSemanticXml.W, "sdtPr")) {
            if (DocxAdapter.child(pr, "text") != null) { pr.appendChild(DocxSemanticXml.property(pr, "lock", "contentLocked")); }
        }
        parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        byte[] locked = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
        assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(locked), DocxAdapter.Policy.ALLOW_LOSS));
    }
    @Test void supportedDrawingChoiceAndValidatedWordEditIdsReadWithoutDuplicatingFallback() throws IOException {
        var doc = source(); var parts = ADAPTER.unzip(new ByteArrayInputStream(encode(doc))); var root = DocxAdapter.parse(parts.get("word/document.xml"));
        for (var anchor : DocxSemanticXml.all(root, DocxSemanticXml.WP, "anchor")) { anchor.setAttributeNS("http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing", "wp14:anchorId", "1234ABCD"); }
        var drawing = DocxSemanticXml.all(root, DocxSemanticXml.W, "drawing").getLast();
        String compatibility = "http://schemas.openxmlformats.org/markup-compatibility/2006";
        var alternate = DocxSemanticXml.element(drawing, compatibility, "mc:AlternateContent");
        var choice = DocxSemanticXml.element(drawing, compatibility, "mc:Choice"); choice.setAttribute("Requires", "wps"); alternate.appendChild(choice);
        var fallback = DocxSemanticXml.element(drawing, compatibility, "mc:Fallback"); fallback.appendChild(DocxSemanticXml.textRun(fallback, "FALLBACK MUST NOT DUPLICATE")); alternate.appendChild(fallback);
        drawing.getParentNode().insertBefore(alternate, drawing); choice.appendChild(drawing);
        parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        var restored = decode(DocxSemanticXml.zip(parts, DocxLimits.DEFAULT)); assertEquals(doc.drawings(), restored.drawings());
        assertFalse(restored.body().sections().getFirst().blocks().stream().filter(Paragraph.class::isInstance).map(Paragraph.class::cast).anyMatch(p -> p.text().contains("FALLBACK")));
        choice.setAttribute("Requires", "unsupportedPrefix"); parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        assertThrows(IOException.class, () -> decode(DocxSemanticXml.zip(parts, DocxLimits.DEFAULT)));
        choice.setAttribute("Requires", "wps"); fallback.appendChild(DocxSemanticXml.word(fallback, "object")); parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(DocxSemanticXml.zip(parts, DocxLimits.DEFAULT)), DocxAdapter.Policy.ALLOW_LOSS));
    }
    @Test void portableTabLayoutUsesExplicitPolicyAcrossBodyCellsAndTextboxes() throws IOException {
        var style = InlineStyle.of(12).toBuilder().rgb(0x224488).build();
        var blocks = List.<Block>of(Paragraph.mixed("tabs", List.of(new InlineText("Name\tValue", style, "https://example.com")), ParagraphFormat.of(12)),
                Table.builder("inline-table").row("A\tB").build(), Table.builder("block-table").row(TableCell.builder().blocks(List.of(p("nested-tabs", "C\tD"))).build()).build(), p("box-anchor", "Box"));
        var nativeDoc = new DocxSemanticDocument(body(blocks), List.of(new Drawing("box", new Point("box-anchor", 0), floating(),
                new TextBox(List.of(p("box-tabs", "E\tF")), 180, 72, 4, 4, 4, 4, VerticalAlignment.TOP, null, null, 0, Transform.NONE))), List.of(), List.of(), List.of(), List.of());
        var rejected = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("TAB_LAYOUT", ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.DEGRADE).build();
        assertThrows(ProcessingException.class, () -> ADAPTER.project(nativeDoc, rejected));
        var projected = ADAPTER.project(nativeDoc, policy(ProcessingPolicy.Action.WARN)); var result = projected.document().sections().getFirst().blocks();
        assertEquals(4, projected.report().issues().stream().filter(issue -> issue.feature().equals("TAB_LAYOUT")).count());
        var paragraph = (Paragraph)result.getFirst(); assertEquals("Name Value", paragraph.text()); assertEquals(style, paragraph.inlines().getFirst().style()); assertEquals("https://example.com", paragraph.inlines().getFirst().hyperlink());
        assertEquals("A B", ((Table)result.get(1)).rows().getFirst().cells().getFirst().text());
        assertEquals("C D", ((Paragraph)((Table)result.get(2)).rows().getFirst().cells().getFirst().blocks().getFirst()).text());
        assertEquals("E F", ((Paragraph)result.getLast()).text());
        var quiet = ADAPTER.project(nativeDoc, policy(ProcessingPolicy.Action.IGNORE)); assertFalse(quiet.report().issues().stream().anyMatch(ProcessingIssue::warning));
        assertEquals(projected.document(), quiet.document()); assertEquals(projected.report().issues().size(), quiet.report().issues().size());
    }
    @Test void projectedInlineImagesExpandInsufficientLineHeightOnlyWithExplicitPolicy() throws IOException {
        var image = image(); var paragraph = Paragraph.mixed("inline-image", List.of(image), ParagraphFormat.of(12));
        var cell = TableCell.mixed(List.of(image), CellFormat.of(12));
        var ordinary = Paragraph.mixed("ordinary", List.of(new InlineText("Unchanged", InlineStyle.of(12))), ParagraphFormat.of(12));
        var doc = new DocxSemanticDocument(body(List.of(paragraph, Table.builder("image-cell").row(cell).build(), ordinary)));
        var rejected = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("INLINE_IMAGE_LINE_HEIGHT", ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.DEGRADE).build();
        assertThrows(ProcessingException.class, () -> ADAPTER.project(doc, rejected));
        var warned = ADAPTER.project(doc, policy(ProcessingPolicy.Action.WARN)); var blocks = warned.document().sections().getFirst().blocks();
        assertEquals(2, warned.report().issues().stream().filter(issue -> issue.feature().equals("INLINE_IMAGE_LINE_HEIGHT")).count());
        assertEquals(36, ((Paragraph)blocks.getFirst()).format().lineHeight());
        assertEquals(36, ((Table)blocks.get(1)).rows().getFirst().cells().getFirst().format().lineHeight());
        assertEquals(ordinary, blocks.getLast());
        var ignored = ADAPTER.project(doc, policy(ProcessingPolicy.Action.IGNORE)); assertEquals(warned.document(), ignored.document());
        assertEquals(warned.report().issues().size(), ignored.report().issues().size()); assertFalse(ignored.report().issues().stream().anyMatch(ProcessingIssue::warning));
    }
    @Test void projectionHasThreeActionsAndRealImagePixelTransforms() throws IOException {
        var doc = source(); assertThrows(ProcessingException.class, () -> ADAPTER.project(doc, ProcessingPolicy.STRICT));
        var warn = policy(ProcessingPolicy.Action.WARN); var projected = ADAPTER.project(doc, warn);
        assertTrue(projected.report().issues().stream().allMatch(ProcessingIssue::warning)); assertTrue(projected.report().issues().stream().anyMatch(i -> i.feature().equals("IMAGE_TRANSFORM")));
        var imageP = (Paragraph)projected.document().sections().getFirst().blocks().stream().filter(b -> b.id().equals("image")).findFirst().orElseThrow(); var resource = imageP.images().getFirst().image().resource();
        assertEquals(2, resource.pixelWidth()); assertEquals(4, resource.pixelHeight()); var pixels = ImageIO.read(new ByteArrayInputStream(resource.bytes())); assertEquals(0x0000ff, pixels.getRGB(0, 0) & 0xffffff);
        var quiet = ADAPTER.project(doc, policy(ProcessingPolicy.Action.IGNORE)); assertEquals(projected.report().issues().size(), quiet.report().issues().size()); assertFalse(quiet.report().issues().stream().anyMatch(ProcessingIssue::warning));
        assertTrue(projected.document().sections().getFirst().blocks().stream().anyMatch(b -> b.id().equals("box-p")));
        var prepared = ADAPTER.conversion(encode(doc)).policy(warn).prepare(new DocumentConversion.Target("MODEL_CHECK", (body, notes, policy, out) -> new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of())));
        assertTrue(prepared.preview().issues().stream().anyMatch(i -> i.feature().equals("COMMENTS")));
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) { return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).feature("COMMENTS", action, ProcessingPolicy.Treatment.DISCARD).build(); }
    @Test void commentAndPictureAnchorsCannotDangleOrSplitSurrogates() throws IOException {
        var doc = source(); assertThrows(IllegalArgumentException.class, () -> new DocxSemanticDocument(doc.body(), List.of(new Drawing("bad", new Point("image", 900), floating(), doc.drawings().getFirst().content())), List.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DocxSemanticDocument(body(List.of(p("p", "A\ud83d\ude00B"))), List.of(), List.of(new Comment("c", Range.of("p", 2, 3), "", "", null, List.of(p("note", "comment")))), List.of(), List.of(), List.of()));
    }
    @Test void nativeConversionPublishesOnlyAfterImportEditAndEncodingSucceed() throws IOException {
        var prepared = ADAPTER.semanticConversion(encode(source())).edit(edit -> edit.setControlText("name", "Bob").acceptRevision("deleted")).prepare(); var output = new ByteArrayOutputStream(); prepared.writeTo(output);
        assertEquals(prepared.bytes(), output.size()); assertTrue(decode(output.toByteArray()).revisions().stream().noneMatch(r -> r.id().equals("deleted")));
        assertThrows(IllegalArgumentException.class, () -> source().edit().setControlText("form", "this exceeds the input constraint").execute());
        var sentinel = new ByteArrayOutputStream(); sentinel.write(7); var invalid = source().edit().contents(new Contents("bad", Range.of("toc", 0, 900), 1, 3, true, true, true));
        assertThrows(IllegalArgumentException.class, invalid::execute); assertEquals(1, sentinel.size());
    }
    @Test void handwrittenExternalOoxmlNeedsNoPrivateMetadataAndEditsRealNativeObjects() throws IOException {
        var base = new ByteArrayOutputStream(); ADAPTER.write(com.mirakyux.export4j.model.Document.builder().paragraph("unused", "unused").build(), base);
        var parts = ADAPTER.unzip(new ByteArrayInputStream(base.toByteArray()));
        try (var main = getClass().getResourceAsStream("/docx/native-semantics.xml"); var comments = getClass().getResourceAsStream("/docx/native-comments.xml")) { assertNotNull(main); assertNotNull(comments); parts.put("word/document.xml", main.readAllBytes()); parts.put("word/comments.xml", comments.readAllBytes()); }
        String relations = new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8).replace("</Relationships>", "<Relationship Id=\"external-comments\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/></Relationships>");
        parts.put("word/_rels/document.xml.rels", relations.getBytes(StandardCharsets.UTF_8));
        String types = new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8).replace("</Types>", "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/></Types>"); parts.put("[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8));
        var imported = decode(DocxSemanticXml.zip(parts, DocxLimits.DEFAULT)); assertEquals(6, imported.revisions().size()); assertEquals(2, imported.controls().size()); assertEquals(1, imported.comments().size()); assertEquals(1, imported.contents().size());
        var edited = imported.edit().setControlText("42", "EDITED-EXTERNAL").rejectRevision("3").acceptRevision("4").acceptRevision("11").rejectRevision("20").rejectRevision("21").execute().document();
        assertTrue(edited.revisions().isEmpty()); var readBack = decode(encode(edited));
        assertEquals("EDITED-EXTERNAL", DocxSemanticSupport.paragraphs(readBack.body(), readBack.drawings()).get(readBack.controls().stream().filter(c -> c.id().equals("42")).findFirst().orElseThrow().range().start().paragraphId()).text());
        var output = ADAPTER.unzip(new ByteArrayInputStream(encode(edited))); String xml = new String(output.get("word/document.xml"), StandardCharsets.UTF_8); assertFalse(xml.contains("ADDED")); assertFalse(xml.contains("DELETED")); assertTrue(xml.contains("w:jc w:val=\"center\"")); assertTrue(xml.contains("224488"));
    }
    @Test void eachNativeFamilyIsReportedEvenWhenNoTextWasEdited() throws IOException {
        var complete = source();
        var families = List.of(new DocxSemanticDocument(complete.body(), complete.drawings(), List.of(), List.of(), List.of(), List.of()),
                new DocxSemanticDocument(complete.body(), List.of(), complete.comments(), List.of(), List.of(), List.of()),
                new DocxSemanticDocument(complete.body(), List.of(), List.of(), complete.revisions(), List.of(), List.of()),
                new DocxSemanticDocument(complete.body(), List.of(), List.of(), List.of(), complete.controls(), List.of()),
                new DocxSemanticDocument(complete.body(), List.of(), List.of(), List.of(), List.of(), complete.contents()));
        for (var family : families) { byte[] bytes = encode(family); assertThrows(IOException.class, () -> ADAPTER.read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT)); assertFalse(ADAPTER.read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.ALLOW_LOSS).diagnostics().isEmpty()); }
    }
    @Test void semanticReadCannotUseLossPolicyToBypassSecurityOrInvalidRanges() throws IOException {
        byte[] source = encode(source());
        for (String entry : List.of("word/vbaProject.bin", "word/activeX/activeX1.xml", "word/embeddings/oleObject1.bin", "_xmlsignatures/sig1.xml")) { var parts = ADAPTER.unzip(new ByteArrayInputStream(source)); parts.put(entry, "unsafe".getBytes(StandardCharsets.UTF_8)); assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(DocxSemanticXml.zip(parts, DocxLimits.DEFAULT)), DocxAdapter.Policy.ALLOW_LOSS)); }
        var protectedParts = ADAPTER.unzip(new ByteArrayInputStream(source)); protectedParts.put("word/settings.xml", "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:documentProtection w:edit=\"readOnly\" w:enforcement=\"1\"/></w:settings>".getBytes(StandardCharsets.UTF_8)); assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(DocxSemanticXml.zip(protectedParts, DocxLimits.DEFAULT)), DocxAdapter.Policy.ALLOW_LOSS));
        var dangling = ADAPTER.unzip(new ByteArrayInputStream(source)); dangling.remove("word/comments.xml"); assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(DocxSemanticXml.zip(dangling, DocxLimits.DEFAULT)), DocxAdapter.Policy.ALLOW_LOSS));
    }
    @Test void commentAndTextboxStoriesCannotResetTheCumulativeSourceBudget() throws IOException {
        var nativeDoc = new DocxSemanticDocument(body(List.of(p("body", "Anchor"))), List.of(), List.of(new Comment("comment", Range.of("body", 0, 3), "", "", null, List.of(p("note-a", "First"), p("note-b", "Second")))), List.of(), List.of(), List.of());
        var adapter = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxBlocks(2).build()); var untouched = new ByteArrayOutputStream(); untouched.write(7);
        assertThrows(IOException.class, () -> adapter.writeSemantic(nativeDoc, untouched)); assertEquals(1, untouched.size()); assertEquals(7, untouched.toByteArray()[0]);
    }
    @Test void repeatedSectionWrapsRealItemControlsAndTheirValueEdits() throws IOException {
        var doc = new DocxSemanticDocument(body(List.of(p("first", "A"), p("second", "B"))), List.of(), List.of(), List.of(),
                List.of(new Control("section", ControlKind.REPEATING_SECTION, new Range(new Point("first", 0), new Point("second", 1)), "", "", false, List.of(), "", "", true),
                    new Control("item-a", ControlKind.REPEATING_ITEM, Range.of("first", 0, 1), "", "", false, List.of(), "", "", true),
                    new Control("item-b", ControlKind.REPEATING_ITEM, Range.of("second", 0, 1), "", "", false, List.of(), "", "", true)), List.of());
        var imported = decode(encode(doc)); assertEquals(3, imported.controls().size());
        var edited = imported.edit().replaceText("first", 0, 1, "Ready A").execute().document(); var result = decode(encode(edited));
        assertEquals(3, result.controls().size()); assertEquals(7, result.controls().stream().filter(c -> c.id().equals("item-a")).findFirst().orElseThrow().range().end().utf16());
        var parts = ADAPTER.unzip(new ByteArrayInputStream(encode(edited))); var xml = DocxAdapter.parse(parts.get("word/document.xml"));
        var section = DocxSemanticXml.all(xml, DocxSemanticXml.W, "sdt").stream().filter(e -> e.getAttributeNS(DocxSemanticXml.OD, "id").equals("section")).findFirst().orElseThrow();
        assertEquals(2, DocxAdapter.children(DocxAdapter.child(section, "sdtContent")).stream().filter(e -> DocxSemanticXml.is(e, "sdt")).count());
    }
}
