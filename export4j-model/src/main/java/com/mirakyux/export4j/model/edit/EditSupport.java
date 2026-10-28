// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

final class EditSupport {
    private EditSupport() { }
    static final class State {
        final EditLimits limits;
        final ProcessingPolicy policy;
        final List<ProcessingIssue> issues = new ArrayList<>();
        final List<DocumentEditor.Change> changes = new ArrayList<>();
        long work;
        int replacements;
        State(EditLimits limits, ProcessingPolicy policy) { this.limits = limits; this.policy = policy; }
        void work(long count) {
            ExecutionContext.checkpoint();
            if (count > limits.maxWork() - work) { throw new IllegalArgumentException("editing work budget exceeded"); }
            work += count;
        }
        ProcessingPolicy.Treatment unsupported(String feature, String operation, ProcessingIssue.Location location,
                String reason, List<String> effects, ProcessingPolicy.Treatment... allowed) {
            var rule = policy.resolve("MODEL", feature, operation);
            effects = switch (rule.treatment()) {
                case SKIP_MATCH -> List.of("MATCH_NOT_REPLACED");
                case SKIP_BLOCK -> List.of("BLOCK_NOT_EDITED");
                case SKIP_REQUEST -> List.of("REQUEST_NOT_APPLIED");
                case PRESERVE -> List.of("ORIGINAL_BLOCK_PRESERVED");
                default -> effects;
            };
            var issue = new ProcessingIssue("EDIT", "MODEL", "MODEL", feature, operation, location,
                    reason, rule.action(), rule.treatment(), effects);
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (!List.of(allowed).contains(rule.treatment())) {
                throw new IllegalArgumentException("treatment unavailable for feature: " + feature);
            }
            if (issues.size() >= limits.maxIssues()) { throw new IllegalArgumentException("editing issue budget exceeded"); }
            issues.add(issue);
            if (rule.treatment() == ProcessingPolicy.Treatment.SKIP_REQUEST) { throw new SkipRequest(); }
            return rule.treatment();
        }
        void change(String operation, ProcessingIssue.Location location) {
            work(1);
            if (changes.size() >= limits.maxNodes()) { throw new IllegalArgumentException("editing change budget exceeded"); }
            changes.add(new DocumentEditor.Change(operation, location));
        }
    }
    static final class SkipRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    static final class SkipBlock extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String blockId;
        SkipBlock(String blockId) { this.blockId = blockId; }
    }
    static SectionedDocument map(SectionedDocument source, State state,
            BiFunction<Block, ProcessingIssue.Location, List<Block>> blocks,
            BiFunction<TableCell, ProcessingIssue.Location, TableCell> cells) {
        var headers = new HashMap<PageRegion, PageRegion>();
        var footers = new HashMap<PageRegion, PageRegion>();
        var backgrounds = new HashMap<PageRegion, PageRegion>(); var foregrounds = new HashMap<PageRegion, PageRegion>();
        var sections = new ArrayList<Section>();
        for (var section : source.sections()) {
            var body = mapBlocks(section.blocks(), section.id(), "BODY", List.of(), state, blocks, cells);
            var regions = section.pageRegions().map((name, band) -> mapRegion(band,
                    name.startsWith("HEADER") ? headers : name.startsWith("FOOTER") ? footers
                            : name.startsWith("BACKGROUND") ? backgrounds : foregrounds,
                    section.id(), name, state, blocks, cells));
            sections.add(body.equals(section.blocks()) && regions.equals(section.pageRegions()) ? section
                    : new Section(section.id(), section.pageSettings(), body, section.firstPageNumber(), regions));
        }
        return sections.equals(source.sections()) ? source : new SectionedDocument(source.metadata(), sections, source.navigation());
    }
    private static PageRegion mapRegion(PageRegion source, Map<PageRegion, PageRegion> cache,
            String section, String region, State state, BiFunction<Block, ProcessingIssue.Location, List<Block>> blocks,
            BiFunction<TableCell, ProcessingIssue.Location, TableCell> cells) {
        return cache.computeIfAbsent(source, value -> {
            var mapped = mapBlocks(value.blocks(), section, region, List.of(), state, blocks, cells);
            return mapped.equals(value.blocks()) ? value : new PageRegion(value.height(), value.gap(), mapped);
        });
    }
    private static List<Block> mapBlocks(List<Block> source, String section, String region, List<CellLocation> path,
            State state, BiFunction<Block, ProcessingIssue.Location, List<Block>> blocks,
            BiFunction<TableCell, ProcessingIssue.Location, TableCell> cells) {
        var result = new ArrayList<Block>();
        for (var original : source) {
            int issueStart = state.issues.size();
            int changeStart = state.changes.size();
            try {
                state.work(1);
                var location = new ProcessingIssue.Location(section, region, original.id(), path, -1, -1);
                Block block = original;
                if (block instanceof Table table) {
                    long slots = (long) table.rows().size() * table.columns().size();
                    if (slots > state.limits.maxNodes()) { throw new IllegalArgumentException("editing table structure budget exceeded"); }
                    state.work(slots);
                    var rows = new ArrayList<TableRow>();
                    for (var anchors : table.grid().anchorRows()) {
                        var row = new ArrayList<TableCell>();
                        for (var anchor : anchors) {
                            state.work(1);
                            var cell = anchor.cell();
                            var cellPath = new ArrayList<>(path);
                            cellPath.add(new CellLocation(table.id(), anchor.rowIndex(), anchor.columnIndex(), -1));
                            var cellLocation = new ProcessingIssue.Location(section, region, table.id(), cellPath, -1, -1);
                            if (cell.hasBlocks()) {
                                var nested = new ArrayList<Block>();
                                for (int index = 0; index < cell.blocks().size(); index++) {
                                    var childPath = new ArrayList<>(path);
                                    childPath.add(new CellLocation(table.id(), anchor.rowIndex(), anchor.columnIndex(), index));
                                    nested.addAll(mapBlocks(List.of(cell.blocks().get(index)), section, region, childPath, state, blocks, cells));
                                }
                                if (!nested.equals(cell.blocks())) { cell = cell.toBuilder().blocks(nested).build(); }
                            }
                            row.add(cells.apply(cell, cellLocation));
                        }
                        rows.add(new TableRow(row));
                    }
                    if (!rows.equals(table.rows())) { block = new Table(table.id(), table.columns(), rows, table.headerRows(), table.format()); }
                }
                result.addAll(blocks.apply(block, location));
            } catch (SkipBlock skipped) {
                if (!skipped.blockId.equals(original.id())) { throw skipped; }
                var issue = state.issues.getLast();
                state.issues.subList(issueStart, state.issues.size()).clear(); state.issues.add(issue);
                state.changes.subList(changeStart, state.changes.size()).clear();
                result.add(original);
            }
        }
        return List.copyOf(result);
    }
    static void unicode(String text, State state) {
        state.work(text.length());
        for (int i = 0; i < text.length(); i++) {
            if ((i & 1023) == 0) { ExecutionContext.checkpoint(); }
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("invalid Unicode in editing input");
                }
            } else if (Character.isLowSurrogate(value)) { throw new IllegalArgumentException("invalid Unicode in editing input"); }
        }
    }
}
