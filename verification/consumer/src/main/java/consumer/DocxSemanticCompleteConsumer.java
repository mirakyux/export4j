// SPDX-License-Identifier: Apache-2.0
package consumer;

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
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxSemanticDocument;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Published-JAR native semantics, four-level tables, section bands and real cross-format projection. */
public final class DocxSemanticCompleteConsumer {
    private DocxSemanticCompleteConsumer() { }
    private static final DocxAdapter ADAPTER = new DocxAdapter();
    private static final OffsetDateTime DATE = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final ProcessingPolicy WARN = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
            .feature("COMMENTS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("PAGE_NUMBERING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("NAVIGATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
            .feature("CUSTOM_BLOCK", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD).build();
    public static void main(String[] arguments) throws Exception {
        var output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("docx-semantic-complete"); Files.createDirectories(output);
        if (arguments.length > 0 && arguments[0].equals("read-back")) {
            verifyWordSaved(output.resolve("word-saved.docx"), output.resolve("word-read-back.json"), false); return;
        }
        if (arguments.length == 2 && arguments[0].equals("windows-ui-read-back")) {
            var file = Path.of(arguments[1]);
            verifyWordSaved(file, file.resolveSibling("word-ui-read-back.json"), true); return;
        }
        var nativeSource = sample(); var sourceBytes = new ByteArrayOutputStream(); ADAPTER.writeSemantic(nativeSource, sourceBytes); Files.write(output.resolve("semantic-source.docx"), sourceBytes.toByteArray());
        var imported = ADAPTER.readSemantic(new ByteArrayInputStream(sourceBytes.toByteArray()), DocxAdapter.Policy.STRICT); if (!imported.diagnostics().isEmpty()) { throw new IllegalStateException("native source lost semantics"); }
        var prepared = ADAPTER.semanticConversion(sourceBytes.toByteArray()).edit(edit -> edit.setControlText("plain", "READY-NAME")
                .setControlText("dropdown", "B").setControlText("combo", "Custom choice").setControlText("date", "2026-10-28").setChecked("checkbox", true)
                .setChecked("legacy-check", true).setControlText("legacy-dropdown", "Second").setControlText("legacy-text", "FORM-READY")
                .replaceText("textbox-p", 0, "TEXTBOX-OLD".length(), "TEXTBOX-READY")
                .replaceText("deep-p", 0, "DEEP-OLD".length(), "DEEP-READY")
                .updateContents("toc", Map.of("heading-1", 1, "heading-2", 4))).prepare();
        prepared.writeTo(output.resolve("semantic-edited.docx")); var edited = prepared.document();
        var decisions = edited.edit().acceptRevision("deletion").rejectRevision("insertion").acceptRevision("move-from").rejectRevision("run-format").rejectRevision("paragraph-format").execute().document();
        try (var stream = Files.newOutputStream(output.resolve("semantic-decided.docx"))) { ADAPTER.writeSemantic(decisions, stream); }
        DocxSemanticDocument decoded; try (var input = Files.newInputStream(output.resolve("semantic-decided.docx"))) { decoded = ADAPTER.readSemantic(input, DocxAdapter.Policy.STRICT).document(); }
        if (!decoded.revisions().isEmpty() || decoded.controls().size() != nativeSource.controls().size() || decoded.drawings().size() != 2 || decoded.comments().size() != 1 || decoded.body().sections().size() != 2) { throw new IllegalStateException("native semantic rebuild differs"); }
        var font = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var symbols = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LXGWWenKaiTC-Regular.ttf")));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font, symbols))).build();
        int losses = 0;
        var bytes = new ByteArrayOutputStream(); ADAPTER.writeSemantic(decisions, bytes);
        for (var target : List.of(ADAPTER.conversionTarget(), new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget(), engine.conversionTarget("PDF", new PdfBackend()))) {
            var converted = ADAPTER.conversion(bytes.toByteArray()).policy(WARN).prepare(target); var result = converted.to(output.resolve("semantic-portable." + target.format().toLowerCase(Locale.ROOT))); losses += result.report().losses().size();
            if (!converted.preview().issues().equals(result.report().issues()) || result.report().losses().isEmpty()) { throw new IllegalStateException("native projection losses missing"); }
        }
        Files.writeString(output.resolve("summary.json"), "{\"sections\":2,\"tableDepth\":4,\"drawings\":2,\"comments\":1,\"revisionKinds\":6,\"controls\":" + nativeSource.controls().size() + ",\"nativeIssues\":" + prepared.report().issues().size() + ",\"crossFormatLosses\":" + losses + "}");
    }
    private static void verifyWordSaved(Path file, Path reportFile, boolean uiOnly) throws Exception {
        String expectedPlain = uiOnly ? "WINDOWS-UI-READY-NAME" : "WORD-EDITED-NAME";
        String expectedTextbox = uiOnly ? "TEXTBOX-READY" : "WORD-TEXTBOX-READY";
        try (var input = Files.newInputStream(file)) {
            var wordAdapter = DocxAdapter.builder().regionGeometry(new DocxAdapter.RegionGeometry(18, 3, 18, 3)).build();
            var result = wordAdapter.readSemantic(input, DocxAdapter.Policy.ALLOW_LOSS); var doc = result.document();
            if (doc.body().sections().size() != 2 || doc.drawings().size() != 2 || doc.controls().size() < 10 || doc.contents().size() != 1 || doc.comments().size() != 1) { throw new IllegalStateException("Word save lost native objects"); }
            var paragraphs = new LinkedHashMap<String, String>(); var body = new StringBuilder();
            for (var section : doc.body().sections()) { appendText(section.blocks(), body, paragraphs); }
            var boxes = new StringBuilder();
            for (var drawing : doc.drawings()) { if (drawing.content() instanceof TextBox box) { appendText(box.blocks(), boxes, paragraphs); } }
            var comments = new StringBuilder();
            for (var comment : doc.comments()) { appendText(comment.blocks(), comments, new LinkedHashMap<>()); }
            if (!body.toString().contains(expectedPlain) || body.toString().replace(expectedPlain, "").contains("READY-NAME") || body.toString().contains("NAME-OLD")
                    || !boxes.toString().contains(expectedTextbox) || boxes.toString().replace(expectedTextbox, "").contains("TEXTBOX-READY")
                    || boxes.toString().contains("TEXTBOX-OLD") || !boxes.toString().contains("Box table")
                    || !body.toString().contains("DEEP-READY") || body.toString().contains("DEEP-OLD")
                    || !body.toString().contains("SECTION-TWO-READY") || !comments.toString().contains("COMMENT-READY: a real editable comment range.")) {
                throw new IllegalStateException("Word edits, deep table, second section or comment text did not survive semantic import");
            }
            var plain = doc.controls().stream().filter(c -> c.tag().equals("plain")).findFirst().orElseThrow();
            if (!rangeText(plain.range(), paragraphs).equals(expectedPlain)) { throw new IllegalStateException("Word plain control value was not imported"); }
            var legacyText = doc.controls().stream().filter(c -> c.kind() == ControlKind.LEGACY_TEXT).findFirst().orElseThrow();
            var legacyList = doc.controls().stream().filter(c -> c.kind() == ControlKind.LEGACY_DROP_DOWN).findFirst().orElseThrow();
            var legacyCheck = doc.controls().stream().filter(c -> c.kind() == ControlKind.LEGACY_CHECKBOX).findFirst().orElseThrow();
            if (!rangeText(legacyText.range(), paragraphs).equals("FORM-READY") || !rangeText(legacyList.range(), paragraphs).equals("Second") || !legacyCheck.checked()) {
                throw new IllegalStateException("Word save changed the actual legacy form values");
            }
            var report = new StringBuilder("{\"sections\":2,\"actualControlEdit\":true,\"actualTextboxEdit\":")
                    .append(!uiOnly).append(",\"deepTableTextPreserved\":true,\"commentTextPreserved\":true,\"legacyCurrentValuesPreserved\":true,\"drawings\":")
                    .append(doc.drawings().size()).append(",\"controls\":").append(doc.controls().size()).append(",\"contents\":").append(doc.contents().size())
                    .append(",\"comments\":").append(doc.comments().size()).append(",\"diagnosticCount\":").append(result.diagnostics().size()).append(",\"diagnostics\":[");
            var features = new LinkedHashMap<String, Integer>(); boolean first = true;
            for (var diagnostic : result.diagnostics()) {
                if (!first) { report.append(','); } first = false;
                report.append("{\"part\":").append(json(diagnostic.part())).append(",\"feature\":").append(json(diagnostic.element()))
                        .append(",\"message\":").append(json(diagnostic.message())).append('}');
                features.merge(diagnostic.element(), 1, Integer::sum);
            }
            report.append("],\"diagnosticsByFeature\":{"); first = true;
            for (var entry : features.entrySet()) { if (!first) { report.append(','); } first = false; report.append(json(entry.getKey())).append(':').append(entry.getValue()); }
            Files.writeString(reportFile, report.append("}}").toString());
        }
    }
    private static String rangeText(Range range, Map<String, String> paragraphs) {
        if (!range.start().paragraphId().equals(range.end().paragraphId())) { throw new IllegalStateException("expected one paragraph for the edited form value"); }
        var text = paragraphs.get(range.start().paragraphId());
        if (text == null) { throw new IllegalStateException("form value paragraph was not traversed"); }
        return text.substring(range.start().utf16(), range.end().utf16());
    }
    private static void appendText(List<Block> blocks, StringBuilder text, Map<String, String> paragraphs) {
        for (var block : blocks) {
            if (block instanceof Paragraph paragraph) { paragraphs.put(paragraph.id(), paragraph.text()); text.append(paragraph.text()).append('\n'); }
            else if (block instanceof Heading heading) { paragraphs.put(heading.id(), heading.text()); text.append(heading.text()).append('\n'); }
            else if (block instanceof Table table) {
                for (var row : table.rows()) { for (var cell : row.cells()) { if (cell.hasBlocks()) { appendText(cell.blocks(), text, paragraphs); } else { text.append(cell.text()).append('\n'); } } }
            }
        }
    }
    private static String json(String value) {
        var output = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> output.append("\\\""); case '\\' -> output.append("\\\\"); case '\n' -> output.append("\\n"); case '\r' -> output.append("\\r"); case '\t' -> output.append("\\t");
                default -> { if (character < 0x20) { output.append(String.format(Locale.ROOT, "\\u%04x", (int)character)); } else { output.append(character); } }
            }
        }
        return output.append('"').toString();
    }
    private static Paragraph p(String id, String text) { return new Paragraph(id, text, ParagraphFormat.of(11)); }
    private static Placement place(double top) { return new Placement(Axis.offset(RelativeTo.MARGIN, 290), Axis.offset(RelativeTo.PARAGRAPH, top), Wrap.SQUARE, WrapSide.BOTH, List.of(), false, true, true, 2, 4, 4, 4, 4); }
    private static DocxSemanticDocument sample() throws IOException {
        var raster = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB); for (int y = 0; y < 40; y++) { for (int x = 0; x < 80; x++) { raster.setRGB(x, y, x < 40 ? 0xdd3322 : 0x2244dd); } }
        var png = new ByteArrayOutputStream(); ImageIO.write(raster, "png", png); var image = new InlineImage(ImageResource.load(png.toByteArray()), 96, 48, 0, "Red and blue transformed picture");
        var blocks = new ArrayList<Block>(); blocks.add(new Heading("heading-1", "Native Word semantics", 1)); blocks.add(p("toc-cache", "Native Word semantics\t1\nSecond section\t2"));
        blocks.add(p("review", "KEEP DELETE-PRIVATE INSERTED-PRIVATE END")); blocks.add(p("move-from-p", "MOVED-CONTENT")); blocks.add(p("move-to-p", "MOVED-CONTENT"));
        blocks.add(p("format-p", "FORMAT-REVIEW")); blocks.add(p("plain-p", "NAME-OLD")); blocks.add(p("rich-p", "Rich text value")); blocks.add(p("checkbox-p", "[ ]")); blocks.add(p("dropdown-p", "First")); blocks.add(p("combo-p", "First")); blocks.add(p("date-p", "2026-01-01"));
        blocks.add(p("legacy-text-p", "FORM-OLD")); blocks.add(p("legacy-check-p", "[ ]")); blocks.add(p("legacy-dropdown-p", "First")); blocks.add(p("group-p", "Grouped editable value")); blocks.add(p("repeat-a", "Repeated item A")); blocks.add(p("repeat-b", "Repeated item B"));
        blocks.add(Paragraph.mixed("picture-control-p", List.of(image), ParagraphFormat.of(11)));
        blocks.add(Paragraph.mixed("picture-p", List.of(image), ParagraphFormat.of(11))); blocks.add(p("textbox-anchor", "Text box follows the semantic anchor."));
        Block nested = p("deep-p", "DEEP-OLD");
        for (int depth = 4; depth >= 1; depth--) { final int level = depth; nested = Table.builder("deep-table-" + depth).format(f -> f.padding(2).drawBorders(depthValue(level)))
                .row(TableCell.builder().blocks(List.of(p("deep-before-" + depth, "LEVEL-" + depth + " BEFORE"), nested, p("deep-after-" + depth, "LEVEL-" + depth + " AFTER"))).build()).build(); }
        blocks.add(nested);
        blocks.add(new PageBreak("default-band-page"));
        blocks.add(p("default-band-body", "DEFAULT-BAND-READY"));
        var defaultHeader = new PageRegion(18, 3, List.of(p("header-default", "DEFAULT HEADER"))); var firstHeader = new PageRegion(18, 3, List.of(p("header-first", "FIRST HEADER"))); var evenHeader = new PageRegion(18, 3, List.of(p("header-even", "EVEN HEADER")));
        var footer = new PageRegion(18, 3, List.of(Paragraph.mixed("footer", List.of(new InlineText("Page ", InlineStyle.of(10)), new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(10))), ParagraphFormat.of(10))));
        var regions = PageRegions.builder().header(defaultHeader).footer(footer).header(PageRegions.Variant.FIRST, firstHeader).footer(PageRegions.Variant.FIRST, footer).header(PageRegions.Variant.EVEN, evenHeader).footer(PageRegions.Variant.EVEN, footer).build();
        var sections = List.of(new Section("first", PageSettings.A4, blocks, 1, regions), new Section("second", PageSettings.A4, List.of(new Heading("heading-2", "Second section", 1), p("second-body", "SECTION-TWO-READY")), 0, regions));
        var controls = new ArrayList<Control>();
        controls.add(control("plain", ControlKind.PLAIN_TEXT, "plain-p", 8)); controls.add(control("rich", ControlKind.RICH_TEXT, "rich-p", 15)); controls.add(control("checkbox", ControlKind.CHECKBOX, "checkbox-p", 3));
        var items = List.of(new ListItem("First", "A"), new ListItem("Second", "B")); controls.add(new Control("dropdown", ControlKind.DROP_DOWN, Range.of("dropdown-p", 0, 5), "choice", "Choice", false, items, "", "", false)); controls.add(new Control("combo", ControlKind.COMBO_BOX, Range.of("combo-p", 0, 5), "combo", "Combo", false, items, "", "", false));
        controls.add(new Control("date", ControlKind.DATE, Range.of("date-p", 0, 10), "date", "Date", false, List.of(), "yyyy-MM-dd", "en-US", false)); controls.add(control("picture", ControlKind.PICTURE, "picture-control-p", 1));
        controls.add(new Control("legacy-text", ControlKind.LEGACY_TEXT, Range.of("legacy-text-p", 0, 8), "legacy-text", "legacy-text", false, List.of(), "", "", false, new ControlOptions("DEFAULT", 20, "regular", "", "Editable form", "Form value", true, true, 0, false, 0)));
        controls.add(control("legacy-check", ControlKind.LEGACY_CHECKBOX, "legacy-check-p", 3)); controls.add(new Control("legacy-dropdown", ControlKind.LEGACY_DROP_DOWN, Range.of("legacy-dropdown-p", 0, 5), "legacy-list", "legacy-list", false, List.of(new ListItem("First", "First"), new ListItem("Second", "Second")), "", "", false));
        controls.add(new Control("group", ControlKind.GROUP, Range.of("group-p", 0, 22), "group", "Group", false, List.of(), "", "", true));
        controls.add(new Control("repeat-section", ControlKind.REPEATING_SECTION, new Range(new Point("repeat-a", 0), new Point("repeat-b", 15)), "repeat", "Repeated rows", false, List.of(), "", "", true)); controls.add(new Control("repeat-item-a", ControlKind.REPEATING_ITEM, Range.of("repeat-a", 0, 15), "", "", false, List.of(), "", "", true)); controls.add(new Control("repeat-item-b", ControlKind.REPEATING_ITEM, Range.of("repeat-b", 0, 15), "", "", false, List.of(), "", "", true));
        var revisions = List.of(new Revision("deletion", RevisionKind.DELETE, Range.of("review", 5, 19), "Reviewer", DATE, "", null, null), new Revision("insertion", RevisionKind.INSERT, Range.of("review", 20, 36), "Reviewer", DATE, "", null, null), new Revision("move-from", RevisionKind.MOVE_FROM, Range.of("move-from-p", 0, 13), "Reviewer", DATE, "move-1", null, null), new Revision("move-to", RevisionKind.MOVE_TO, Range.of("move-to-p", 0, 13), "Reviewer", DATE, "move-1", null, null), new Revision("run-format", RevisionKind.RUN_FORMAT, Range.of("format-p", 0, 13), "Reviewer", DATE, "", InlineStyle.of(11).toBuilder().rgb(0x224488).build(), null), new Revision("paragraph-format", RevisionKind.PARAGRAPH_FORMAT, Range.of("format-p", 0, 13), "Reviewer", DATE, "", null, ParagraphFormat.of(11).toBuilder().alignment(ParagraphAlignment.CENTER).build()));
        return new DocxSemanticDocument(new SectionedDocument(new DocumentMetadata("Typed Word document", "Export4j"), sections), List.of(new Drawing("floating-picture", new Point("picture-p", 0), place(0), new Picture(image, new Transform(25, true, false, .1, 0, .05, 0))), new Drawing("editable-textbox", new Point("textbox-anchor", 0), place(8), new TextBox(List.of(p("textbox-p", "TEXTBOX-OLD"), Table.builder("textbox-table").row("Box table", "Value").build()), 170, 90, 5, 5, 5, 5, VerticalAlignment.TOP, 0xffffee, 0x336699, 1, Transform.NONE))),
                List.of(new Comment("review-comment", new Range(new Point("plain-p", 0), new Point("rich-p", 4)), "Reviewer", "RV", DATE, List.of(p("comment-content", "COMMENT-READY: a real editable comment range.")))), revisions, controls, List.of(new Contents("toc", Range.of("toc-cache", 0, "Native Word semantics\t1\nSecond section\t2".length()), 1, 3, true, true, true)));
    }
    private static boolean depthValue(int depth) { return depth % 2 == 0; }
    private static Control control(String id, ControlKind kind, String p, int length) { return new Control(id, kind, Range.of(p, 0, length), id, id, false, List.of(), "", "", false); }
}
