// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DocxConversions {
    private DocxConversions() { }
    static DocumentConversion.Request source(DocxAdapter adapter, byte[] encoded) {
        return adapter.semanticConversion(Objects.requireNonNull(encoded, "encoded")).project();
    }
    static DocumentConversion.Request source(DocxAdapter adapter, Path source) {
        Objects.requireNonNull(source, "source");
        return DocumentConversion.from("DOCX", policy -> {
            try (var input = Files.newInputStream(source)) { return read(adapter, input, policy); }
        });
    }
    static DocumentConversion.Request source(DocxAdapter adapter, InputStream source) {
        Objects.requireNonNull(source, "source");
        return DocumentConversion.from("DOCX", policy -> read(adapter, source, policy));
    }
    private static DocumentConversion.Input read(DocxAdapter adapter, InputStream input, ProcessingPolicy policy) throws IOException {
        var imported = adapter.readSemantic(input, DocxAdapter.Policy.ALLOW_LOSS);
        var issues = DocxSemanticConversion.importIssues(imported.diagnostics(), policy);
        var edited = imported.document().edit().policy(policy).execute();
        issues.addAll(edited.report().issues());
        var projected = DocxSemanticProjection.project(edited.document(), policy);
        issues.addAll(projected.report().issues());
        return new DocumentConversion.Input(projected.document(), projected.notes(),
                new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues));
    }
    static DocumentConversion.Target target(DocxAdapter adapter) {
        return new DocumentConversion.Target("DOCX", (source, notes, policy, output) -> {
            var issues = new ArrayList<ProcessingIssue>();
            source.navigation().validate(source);
            if (!source.navigation().equals(com.mirakyux.export4j.model.DocumentNavigation.NONE)) {
                add(issues, policy, "EXPORT", "MODEL", "DOCX", "NAVIGATION",
                        new ProcessingIssue.Location("", "NAVIGATION", "", List.of(), -1, -1),
                        "portable navigation has no native DOCX mapping", "NAVIGATION_DECLARATIONS_REMOVED", ProcessingPolicy.Treatment.DISCARD);
            }
            for (var note : notes) {
                add(issues, policy, "EXPORT", "MODEL", "DOCX", "NOTES",
                        new ProcessingIssue.Location("", "BODY", note.paragraphId(), List.of(), -1, -1),
                        "DOCX note text has no export mapping", "NOTE_TEXT_REMOVED", ProcessingPolicy.Treatment.DISCARD);
            }
            var mapper = new Styles(source, policy, issues); var sections = new ArrayList<Section>();
            for (var section : source.sections()) {
                var supported = section.pageRegions();
                for (var layer : List.of("BACKGROUND", "FOREGROUND")) {
                    var overlays = layer.equals("BACKGROUND") ? supported.background() : supported.foreground();
                    for (int index = 0; index < overlays.size(); index++) {
                        add(issues, policy, "EXPORT", "MODEL", "DOCX", "PAGE_OVERLAYS",
                                new ProcessingIssue.Location(section.id(), layer + ":" + index, "", List.of(), -1, -1),
                                "fixed page overlay has no DOCX semantic export mapping", "PAGE_OVERLAY_CONTENT_AND_POSITION_REMOVED", ProcessingPolicy.Treatment.DISCARD);
                    }
                }
                supported = supported.toBuilder().background(List.of()).foreground(List.of()).build();
                var regions = supported.map((name, band) -> band.toBuilder()
                        .blocks(mapper.blocks(band.blocks(), section.id(), name, List.of())).build());
                sections.add(new Section(section.id(), section.pageSettings(), mapper.blocks(section.blocks(), section.id(), "BODY", List.of()), section.firstPageNumber(), regions));
            }
            adapter.write(new SectionedDocument(source.metadata(), sections), output);
            return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
        });
    }
    private static final class Styles {
        private record ContainerTarget(String blockId, String feature) { }
        private final ProcessingPolicy policy;
        private final List<ProcessingIssue> issues;
        private final Map<String, ContainerTarget> containerTargets = new HashMap<>();
        Styles(SectionedDocument source, ProcessingPolicy policy, List<ProcessingIssue> issues) {
            this.policy = policy; this.issues = issues;
            // Collect before mapping any paragraph so forward, backward and cell references agree.
            for (var section : source.sections()) {
                SemanticTree.walk(section.blocks(), visit -> {
                    if (visit.block() instanceof ColumnFlow columns) {
                        containerTargets.put(columns.id(), new ContainerTarget(columns.blocks().getFirst().id(), "COLUMN_FLOW"));
                    } else if (visit.block() instanceof GridLayout grid) {
                        containerTargets.put(grid.id(), new ContainerTarget(grid.items().getFirst().blocks().getFirst().id(), "GRID_LAYOUT"));
                    } else if (visit.block() instanceof GridItem item) {
                        containerTargets.put(item.id(), new ContainerTarget(item.blocks().getFirst().id(), "GRID_LAYOUT"));
                    }
                });
            }
        }
        List<Block> blocks(List<Block> source, String section, String region, List<CellLocation> path) {
            var result = new ArrayList<Block>();
            for (var block : source) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var at = new ProcessingIssue.Location(section, region, block.id(), path, -1, -1);
                if (block instanceof GridLayout grid) {
                    add(issues, policy, "EXPORT", "MODEL", "DOCX", "GRID_LAYOUT", at,
                            "track grids have no native DOCX export mapping", "LAYOUT_REMOVED_CONTENT_RETAINED", ProcessingPolicy.Treatment.DEGRADE);
                    for (var item : grid.items()) { result.addAll(blocks(item.blocks(), section, region, path)); }
                } else if (block instanceof ColumnFlow columns) {
                    add(issues, policy, "EXPORT", "MODEL", "DOCX", "COLUMN_FLOW", at,
                            "continuous columns have no native DOCX export mapping", "LAYOUT_REMOVED_CONTENT_RETAINED", ProcessingPolicy.Treatment.DEGRADE);
                    result.addAll(blocks(columns.blocks(), section, region, path));
                } else if (block instanceof Paragraph paragraph) { result.add(paragraph(paragraph, at)); }
                else if (block instanceof Heading heading) {
                    var mapped = paragraph(heading.paragraph(), at);
                    result.add(Heading.rich(heading.id(), mapped.inlines(), heading.level(), mapped.format()));
                } else if (block instanceof BulletItem item) {
                    var mapped = paragraph(item.paragraph(), at);
                    result.add(BulletItem.rich(item.id(), mapped.inlines(), item.marker(), item.level(), mapped.format(), style(item.markerStyle(), at)));
                } else if (block instanceof NumberedItem item) {
                    var mapped = paragraph(item.paragraph(), at);
                    result.add(NumberedItem.rich(item.id(), item.listId(), mapped.inlines(), item.restartAt(), mapped.format(), item.level(), style(item.markerStyle(), at)));
                }
                else if (block instanceof Caption caption) {
                    add(issues, policy, "EXPORT", "MODEL", "DOCX", "CAPTION", at,
                            "caption numbering has no DOCX semantic mapping", "CAPTION_REPLACED_WITH_UNNUMBERED_TEXT", ProcessingPolicy.Treatment.DEGRADE);
                    result.add(paragraph(new Paragraph(caption.id(), caption.text(), caption.format()), at));
                } else if (block instanceof CrossReference reference) {
                    add(issues, policy, "EXPORT", "MODEL", "DOCX", "CROSS_REFERENCE", at,
                            "independent reference has no DOCX semantic mapping", "REFERENCE_REPLACED_WITH_UNRESOLVED_TEXT", ProcessingPolicy.Treatment.DEGRADE);
                    result.add(paragraph(new Paragraph(reference.id(), reference.prefix() + "[unresolved reference]", reference.format()), at));
                }
                else if (block instanceof Table table) {
                    var rows = new ArrayList<TableRow>();
                    for (var row : table.grid().anchorRows()) {
                        var cells = new ArrayList<TableCell>();
                        for (var anchor : row) {
                            var cell = anchor.cell(); var cellsPath = new ArrayList<>(path); cellsPath.add(new CellLocation(table.id(), anchor.rowIndex(), anchor.columnIndex(), -1));
                            if (cell.hasBlocks()) { cells.add(cell.toBuilder().blocks(blocks(cell.blocks(), section, region, List.copyOf(cellsPath))).build()); }
                            else {
                                var format = cell.format();
                                var p = new Paragraph(table.id(), cell.text(), new ParagraphFormat(format.fontSize(), format.lineHeight(), 0, 0, format.alignment(), 0, 0, false), cell.inlines(), cell.images(), cell.fields());
                                cells.add(cell.toBuilder().content(paragraph(p, new ProcessingIssue.Location(section, region, table.id(), cellsPath, -1, -1)).content()).build());
                            }
                        }
                        rows.add(new TableRow(cells));
                    }
                    var format = table.format();
                    if (format.rotation() != 0) {
                        rotation(at, "Table rotation has no native DOCX semantic mapping");
                        format = format.toBuilder().rotation(0).build();
                    }
                    result.add(new Table(table.id(), table.columns(), rows, table.headerRows(), format));
                } else { result.add(block); }
            }
            return List.copyOf(result);
        }
        private Paragraph paragraph(Paragraph source, ProcessingIssue.Location at) {
            var format = source.format();
            if (format.rotation() != 0) {
                rotation(at, "Paragraph rotation has no native DOCX semantic mapping");
                format = format.toBuilder().rotation(0).build();
            }
            var content = new ArrayList<InlineContent>(); int offset = 0;
            for (var value : source.content()) {
                if (value instanceof InlineText run) {
                    var location = new ProcessingIssue.Location(at.sectionId(), at.region(), at.blockId(), at.cellPath(), offset, offset + run.text().length());
                    content.add(new InlineText(run.text(), style(run.style(), location), run.hyperlink())); offset += run.text().length();
                } else if (value instanceof InlineField field && field.kind() == InlineField.Kind.NOTE_MARKER) {
                    add(issues, policy, "EXPORT", "MODEL", "DOCX", "NOTES", at.range(offset, offset + 1),
                            "inline note marker has no DOCX export mapping", "NOTE_MARKER_REMOVED", ProcessingPolicy.Treatment.DISCARD);
                    offset++;
                } else if (value instanceof InlineField field && field.kind() == InlineField.Kind.REFERENCE_PAGE
                        && containerTargets.containsKey(field.targetId())) {
                    var target = containerTargets.get(field.targetId());
                    add(issues, policy, "EXPORT", "MODEL", "DOCX", target.feature(), at.range(offset, offset + 1),
                            "flattened container page reference uses its first retained block", "PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK", ProcessingPolicy.Treatment.DEGRADE);
                    content.add(field.toBuilder().targetId(target.blockId()).build()); offset++;
                } else if (value instanceof InlineImage image && image.rotation() != 0) {
                    rotation(at.range(offset, offset + 1), "Inline image rotation has no portable DOCX image mapping");
                    content.add(image.toBuilder().rotation(0).build()); offset++;
                } else { content.add(value); offset++; }
            }
            return Paragraph.mixed(source.id(), content, format);
        }
        private void rotation(ProcessingIssue.Location at, String reason) {
            add(issues, policy, "EXPORT", "MODEL", "DOCX", "ELEMENT_ROTATION", at,
                    reason, "ROTATION_REMOVED", ProcessingPolicy.Treatment.DEGRADE);
        }
        private InlineStyle style(InlineStyle source, ProcessingIssue.Location at) {
            if (Set.of("", "bold", "italic", "boldItalic").contains(source.fontRole())) { return source; }
            add(issues, policy, "EXPORT", "MODEL", "DOCX", "FONT_ROLE", at,
                    "custom font role has no DOCX font/style mapping", "FONT_ROLE_REPLACED_WITH_DEFAULT", ProcessingPolicy.Treatment.DEGRADE);
            return source.toBuilder().fontRole("").build();
        }
    }
    private static void add(List<ProcessingIssue> issues, ProcessingPolicy policy, String stage, String source, String target,
            String feature, ProcessingIssue.Location location, String reason, String effect, ProcessingPolicy.Treatment available) {
        String format = stage.equals("IMPORT") ? source : target; var rule = policy.resolve(format, feature, stage);
        var issue = new ProcessingIssue(stage, source, target, feature, stage, location, reason, rule.action(), rule.treatment(), List.of(effect));
        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
        if (rule.treatment() != available) { throw new IllegalArgumentException("DOCX conversion treatment unavailable: " + feature); }
        if (issues.size() >= 10_000) { throw new IllegalArgumentException("DOCX conversion report budget exceeded"); }
        issues.add(issue);
    }
}
