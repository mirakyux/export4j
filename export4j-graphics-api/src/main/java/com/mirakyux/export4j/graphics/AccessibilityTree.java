// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Explicit logical reading order and bindings to already laid-out drawing commands.
 * This value never infers semantics from coordinates. Unsemantic decoration must be declared as an artifact.
 * Collections are immutable snapshots; indices are zero based and ranges are half open.
 */
public record AccessibilityTree(String language, List<Node> children, List<CommandRange> artifacts) {
    public static final AccessibilityTree EMPTY = new AccessibilityTree("", List.of(), List.of());
    public enum Role { PART, SECT, DIV, P, H1, H2, H3, H4, H5, H6, L, LI, LBL, LBODY, FIGURE, CAPTION, TABLE, TR, TH, TD, SPAN }
    public enum Scope { NONE, ROW, COLUMN, BOTH }
    public record CommandRange(int pageIndex, int firstCommand, int endCommand) {
        public CommandRange { if (pageIndex < 0 || firstCommand < 0 || endCommand <= firstCommand) { throw new IllegalArgumentException("invalid accessibility command range"); } }
    }
    /** Table header IDs refer to TH nodes. Scope applies to TH; spans use physical row/column counts. */
    public record Cell(Scope scope, int rowSpan, int columnSpan, List<String> headers) {
        public static final Cell NONE = new Cell(Scope.NONE, 1, 1, List.of());
        public Cell { Objects.requireNonNull(scope, "scope"); headers = List.copyOf(headers); if (rowSpan <= 0 || columnSpan <= 0 || rowSpan > 10_000 || columnSpan > 10_000) { throw new IllegalArgumentException("invalid accessible table span"); } }
    }
    public record Node(String id, Role role, String alternativeText, String language, Cell cell,
                       List<CommandRange> content, List<Node> children) {
        public Node {
            text(id, "id"); Objects.requireNonNull(role, "role"); text(alternativeText, "alternativeText"); text(language, "language");
            Objects.requireNonNull(cell, "cell"); content = List.copyOf(content); children = List.copyOf(children);
            if (id.isBlank() || id.length() > 256 || alternativeText.length() > 16_384 || !language.isEmpty() && !language.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) { throw new IllegalArgumentException("invalid accessibility node"); }
            if (role == Role.FIGURE && alternativeText.isBlank()) { throw new IllegalArgumentException("accessible figures require alternative text"); }
            if (role != Role.TH && role != Role.TD && !cell.equals(Cell.NONE) || role == Role.TH && cell.scope() == Scope.NONE) { throw new IllegalArgumentException("invalid table accessibility attributes"); }
            if (role == Role.L && children.stream().anyMatch(item -> item.role() != Role.LI)
                    || role == Role.LI && (children.size() != 2 || children.get(0).role() != Role.LBL || children.get(1).role() != Role.LBODY)
                    || role == Role.TABLE && children.stream().anyMatch(item -> item.role() != Role.TR)
                    || role == Role.TR && children.stream().anyMatch(item -> item.role() != Role.TH && item.role() != Role.TD)) { throw new IllegalArgumentException("invalid accessible list or table hierarchy"); }
        }
        public Node(String id, Role role, List<CommandRange> content, List<Node> children) { this(id, role, "", "", Cell.NONE, content, children); }
    }
    public AccessibilityTree {
        text(language, "language"); children = List.copyOf(children); artifacts = List.copyOf(artifacts);
        if (!children.isEmpty() && !language.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) { throw new IllegalArgumentException("accessible documents require an explicit language"); }
        record Visit(Node node, int depth) { }
        var pending = new ArrayDeque<Visit>(); for (var node : children) { pending.add(new Visit(node, 1)); }
        var ids = new HashMap<String, Role>(); var headers = new HashSet<String>(); int heading = 0;
        while (!pending.isEmpty()) {
            var visit = pending.removeFirst(); var node = visit.node();
            if (visit.depth() > 64 || ids.size() >= 100_000 || ids.putIfAbsent(node.id(), node.role()) != null) { throw new IllegalArgumentException("duplicate or excessive accessibility structure"); }
            if (node.role().name().matches("H[1-6]")) { int level = node.role().name().charAt(1) - '0'; if (level > heading + 1) { throw new IllegalArgumentException("accessible heading hierarchy skips a level"); } heading = level; }
            headers.addAll(node.cell().headers()); for (int i = node.children().size() - 1; i >= 0; i--) { pending.addFirst(new Visit(node.children().get(i), visit.depth() + 1)); }
        }
        if (headers.stream().anyMatch(id -> ids.get(id) != Role.TH)) { throw new IllegalArgumentException("table header reference does not identify a TH node"); }
    }
    /** Verifies exact paint coverage and rejects aliases; state-only commands need no logical binding. */
    public void validatePages(List<DisplayPage> pages) {
        if (children.isEmpty()) { if (!artifacts.isEmpty()) { throw new IllegalArgumentException("artifacts require a logical document"); } return; }
        var covered = new boolean[pages.size()][]; for (int i = 0; i < pages.size(); i++) { covered[i] = new boolean[pages.get(i).commands().size()]; }
        var pending = new ArrayDeque<Node>(children); while (!pending.isEmpty()) { var node = pending.removeFirst(); for (var range : node.content()) { cover(range, covered); } pending.addAll(node.children()); }
        for (var range : artifacts) { cover(range, covered); }
        for (int page = 0; page < pages.size(); page++) { for (int index = 0; index < covered[page].length; index++) { var command = pages.get(page).commands().get(index); if (paints(command) && !covered[page][index]) { throw new IllegalArgumentException("painted content lacks an explicit logical or artifact binding"); } } }
    }
    private static void cover(CommandRange range, boolean[][] covered) {
        if (range.pageIndex() >= covered.length || range.endCommand() > covered[range.pageIndex()].length) { throw new IllegalArgumentException("accessibility range exceeds its page"); }
        for (int index = range.firstCommand(); index < range.endCommand(); index++) { if (covered[range.pageIndex()][index]) { throw new IllegalArgumentException("accessibility command ranges overlap"); } covered[range.pageIndex()][index] = true; }
    }
    public static boolean paints(DrawCommand command) { return !(command instanceof DrawCommand.Save || command instanceof DrawCommand.Restore || command instanceof DrawCommand.Transform || command instanceof DrawCommand.Clip); }
    private static void text(String value, String name) { Objects.requireNonNull(value, name); if (value.codePoints().anyMatch(code -> code < 32 && code != 9 && code != 10 && code != 13 || code >= 0xD800 && code <= 0xDFFF || code == 0xFFFE || code == 0xFFFF)) { throw new IllegalArgumentException("invalid accessibility text"); } }
}
