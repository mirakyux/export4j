// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.SectionedDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Native, editable Word equations anchored to one U+FFFC UTF-16 slot each in the semantic document.
 * Display equations occupy their entire paragraph. Core layout/projection does not render this AST.
 * Math overlapping revisions, content controls or TOCs is rejected rather than flattened.
 * Immutable and reusable; replacing an equation never mutates the source document. */
public record DocxMathDocument(DocxSemanticDocument semantic, List<Equation> equations, DocxMathSettings settings) {
    public enum Placement { INLINE, DISPLAY_LEFT, DISPLAY_CENTER, DISPLAY_RIGHT, DISPLAY_CENTER_GROUP }
    public record Equation(String id, DocxSemanticDocument.Point anchor, Placement placement, DocxMath expression) {
        public Equation {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(anchor, "anchor"); Objects.requireNonNull(placement, "placement"); Objects.requireNonNull(expression, "expression");
            if (id.isBlank() || id.length() > 256 || id.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                    || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) { throw new IllegalArgumentException("invalid equation ID"); }
        }
    }
    public DocxMathDocument {
        Objects.requireNonNull(semantic, "semantic"); equations = List.copyOf(equations); Objects.requireNonNull(settings, "settings");
        DocxMathSupport.validate(equations, DocxMathLimits.DEFAULT);
        equations = equations.stream().map(equation -> new Equation(equation.id(), equation.anchor(), equation.placement(),
                DocxMathSupport.canonical(equation.expression()))).toList();
        var paragraphs = DocxSemanticSupport.paragraphs(semantic.body(), semantic.drawings());
        for (var comment : semantic.comments()) { DocxSemanticSupport.collect(comment.blocks(), paragraphs); }
        var ids = new HashSet<String>(); var occupied = new HashSet<DocxSemanticDocument.Point>();
        var order = new ArrayList<>(paragraphs.keySet());
        var positions = new java.util.HashMap<String, Integer>(); for (int index = 0; index < order.size(); index++) { positions.put(order.get(index), index); }
        equations = equations.stream().sorted(java.util.Comparator.comparingInt((Equation equation) -> positions.getOrDefault(equation.anchor().paragraphId(), Integer.MAX_VALUE))
                .thenComparingInt(equation -> equation.anchor().utf16())).toList();
        for (var equation : equations) {
            var anchor = equation.anchor(); var paragraph = paragraphs.get(anchor.paragraphId());
            if (!ids.add(equation.id()) || !occupied.add(anchor) || paragraph == null
                    || anchor.utf16() >= paragraph.text().length() || paragraph.text().charAt(anchor.utf16()) != '\ufffc'
                    || paragraph.images().stream().anyMatch(image -> image.sourceIndex() == anchor.utf16())
                    || paragraph.fields().stream().anyMatch(field -> field.sourceIndex() == anchor.utf16())
                    || paragraph.vectors().stream().anyMatch(vector -> vector.sourceIndex() == anchor.utf16())) {
                throw new IllegalArgumentException("equation must own a unique ordinary object marker");
            }
            if (equation.placement() != Placement.INLINE && !paragraph.text().equals("\ufffc")) { throw new IllegalArgumentException("display equation requires an otherwise empty paragraph"); }
            int offset = 0;
            for (var run : paragraph.inlines()) {
                if (anchor.utf16() >= offset && anchor.utf16() < offset + run.text().length()
                        && (!run.hyperlink().isEmpty() || !run.style().equals(com.mirakyux.export4j.model.InlineStyle.of(paragraph.format().fontSize())))) {
                    throw new IllegalArgumentException("mathematical marker requires the paragraph's default plain style");
                }
                offset += run.text().length();
            }
            for (var revision : semantic.revisions()) { if (covers(revision.range(), anchor, order)) { throw new IllegalArgumentException("equation overlaps a revision"); } }
            for (var control : semantic.controls()) { if (covers(control.range(), anchor, order)) { throw new IllegalArgumentException("equation overlaps a content control"); } }
            for (var contents : semantic.contents()) { if (covers(contents.range(), anchor, order)) { throw new IllegalArgumentException("equation overlaps contents"); } }
        }
        for (var paragraph : paragraphs.values()) {
            for (int index = 0; index < paragraph.text().length(); index++) {
                if (paragraph.text().charAt(index) != '\ufffc') { continue; }
                int position = index;
                if (!occupied.contains(new DocxSemanticDocument.Point(paragraph.id(), index))
                        && paragraph.images().stream().noneMatch(image -> image.sourceIndex() == position)
                        && paragraph.fields().stream().noneMatch(field -> field.sourceIndex() == position)
                        && paragraph.vectors().stream().noneMatch(vector -> vector.sourceIndex() == position)) {
                    throw new IllegalArgumentException("unbound mathematical object marker");
                }
            }
        }
    }
    public DocxMathDocument(DocxSemanticDocument semantic, List<Equation> equations) { this(semantic, equations, DocxMathSettings.DEFAULT); }
    public DocxMathDocument(SectionedDocument body, List<Equation> equations) { this(new DocxSemanticDocument(body), equations); }
    public DocxMathDocument withEquation(String id, DocxMath replacement) {
        Objects.requireNonNull(replacement, "replacement"); var changed = new ArrayList<Equation>(); boolean found = false;
        for (var equation : equations) {
            if (equation.id().equals(id)) { changed.add(new Equation(equation.id(), equation.anchor(), equation.placement(), replacement)); found = true; }
            else { changed.add(equation); }
        }
        if (!found) { throw new IllegalArgumentException("unknown equation ID"); }
        return new DocxMathDocument(semantic, changed, settings);
    }
    private static boolean covers(DocxSemanticDocument.Range range, DocxSemanticDocument.Point at, List<String> order) {
        int index = order.indexOf(at.paragraphId()); int start = order.indexOf(range.start().paragraphId()); int end = order.indexOf(range.end().paragraphId());
        return index >= start && index <= end && (index != start || at.utf16() >= range.start().utf16())
                && (index != end || at.utf16() < range.end().utf16());
    }
}
