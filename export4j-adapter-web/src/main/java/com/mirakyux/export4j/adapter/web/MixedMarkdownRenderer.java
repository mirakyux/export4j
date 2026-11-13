// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.renderer.html.CoreHtmlNodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;

/** Internal CommonMark-to-supported-HTML bridge. Every emitted core node retains its original
 * source interval; raw HTML is validated later without removing unknown attributes or CSS. */
final class MixedMarkdownRenderer {
    record Range(int start, int end, int sourceStart, int sourceEnd, boolean direct) { }
    record Result(String html, List<Range> ranges) { }

    private MixedMarkdownRenderer() { }

    static Result render(Node tree, String source, ContentBudget budget, boolean literalTables) {
        if (literalTables) { literalTables(tree, source, budget); }
        var output = new StringBuilder();
        var ranges = new ArrayList<Range>();
        Appendable bounded = new Appendable() {
            @Override public Appendable append(CharSequence value) { return append(value, 0, value.length()); }
            @Override public Appendable append(CharSequence value, int start, int end) {
                budget.work(end - start); output.append(value, start, end); return this;
            }
            @Override public Appendable append(char value) { budget.work(1); output.append(value); return this; }
        };
        var builder = HtmlRenderer.builder().escapeHtml(false)
                .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
                .nodeRendererFactory(context -> new CoreHtmlNodeRenderer(context) {
                    @Override public void render(Node node) {
                        int start = output.length();
                        super.render(node);
                        int first = Integer.MAX_VALUE, last = -1;
                        for (var span : node.getSourceSpans()) {
                            first = Math.min(first, span.getInputIndex());
                            last = Math.max(last, span.getInputIndex() + span.getLength());
                        }
                        if (last < 0 || start == output.length()) { return; }
                        boolean direct = (node instanceof HtmlInline || node instanceof HtmlBlock)
                                && output.substring(start).equals(source.substring(first, last));
                        ranges.add(new Range(start, output.length(), first, last, direct));
                        if (!direct && (node instanceof HtmlInline || node instanceof HtmlBlock)) {
                            String original = source.substring(first, last);
                            int exact = output.indexOf(original, start);
                            if (exact >= start && exact + original.length() <= output.length()) {
                                ranges.add(new Range(exact, exact + original.length(), first, last, true));
                            }
                        }
                    }
                });
        var renderer = builder.build();
        renderer.render(tree, bounded);
        return new Result(output.toString(), List.copyOf(ranges));
    }
    private static void literalTables(Node tree, String source, ContentBudget budget) {
        var pending = new java.util.ArrayDeque<Node>(); pending.push(tree);
        while (!pending.isEmpty()) {
            var node = pending.pop(); budget.work(1);
            if (node instanceof TableBlock) {
                int first = Integer.MAX_VALUE, last = -1;
                for (var span : node.getSourceSpans()) {
                    first = Math.min(first, span.getInputIndex()); last = Math.max(last, span.getInputIndex() + span.getLength());
                }
                if (last < 0) { throw new IllegalArgumentException("Markdown table source range unavailable"); }
                String literal = source.substring(first, last); budget.work(literal.length());
                var replacement = new HtmlBlock(); replacement.setSourceSpans(node.getSourceSpans());
                replacement.setLiteral("<p style=\"white-space:pre-wrap\">" + literal.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>");
                node.insertBefore(replacement); node.unlink();
            } else {
                for (var child = node.getLastChild(); child != null; child = child.getPrevious()) { pending.push(child); }
            }
        }
    }
}
