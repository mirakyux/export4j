// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.core.PageComposition;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.layout.ComponentFragment;

/** Body IR retains body-local page indices; bodyPageOffset maps them to final pages. Contents/decorations retain diagnostics. */
public record ComponentDocumentLayout(DocumentLayout body, int bodyPageOffset, DisplayDocument display,
        List<ComponentLayout> contentsPages, List<PageComposition.Placed> decorations, List<ComponentLayout> footnotes,
        List<ComponentLayout> resolvedReferences, List<ContextualDiagnostic> referenceDiagnostics) {
    public ComponentDocumentLayout {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(display, "display");
        contentsPages = List.copyOf(contentsPages);
        decorations = List.copyOf(decorations);
        footnotes = List.copyOf(footnotes);
        resolvedReferences = List.copyOf(resolvedReferences);
        referenceDiagnostics = List.copyOf(referenceDiagnostics);
        if (bodyPageOffset < 0) {
            throw new IllegalArgumentException("body page offset must be nonnegative");
        }
    }

    public ComponentDocumentLayout(DocumentLayout body, int bodyPageOffset, DisplayDocument display,
            List<ComponentLayout> contentsPages, List<PageComposition.Placed> decorations) {
        this(body, bodyPageOffset, display, contentsPages, decorations, List.of(), List.of(), List.of());
    }

    public ComponentDocumentLayout(DocumentLayout body, int bodyPageOffset, DisplayDocument display,
            List<ComponentLayout> contentsPages, List<PageComposition.Placed> decorations, List<ComponentLayout> footnotes,
            List<ComponentLayout> resolvedReferences) {
        this(body, bodyPageOffset, display, contentsPages, decorations, footnotes, resolvedReferences, List.of());
    }

    /** Owning block/region and final page, preserving cell-local UTF-16 indexes for semantic tables. */
    public List<ContextualDiagnostic> contextualDiagnostics() {
        var result = new java.util.ArrayList<ContextualDiagnostic>();
        for (var page : body.pages()) {
            int physical = page.pageIndex() + bodyPageOffset;
            for (var fragment : page.elements()) {
                SemanticFragments.walk(fragment, located -> {
                    if (located.fragment() instanceof ComponentFragment component) {
                        component.layout().diagnostics().forEach(d -> result.add(new ContextualDiagnostic(component.blockId(), physical,
                                "COMPONENT", -1, -1, d, located.sourcePath())));
                    }
                }, leaf -> leaf.layout().diagnostics().forEach(d -> result.add(new ContextualDiagnostic(leaf.blockId(), physical,
                        leaf.sourcePath().isEmpty() ? "BODY" : "TABLE", leaf.rowIndex(), leaf.columnIndex(), d, leaf.sourcePath()))));
            }
        }
        for (int page = 0; page < contentsPages.size(); page++) {
            int physical = page;
            contentsPages.get(page).diagnostics().forEach(d -> result.add(new ContextualDiagnostic("", physical, "CONTENTS", -1, -1, d)));
        }
        for (int page = 0; page < footnotes.size(); page++) {
            int physical = page + bodyPageOffset;
            footnotes.get(page).diagnostics().forEach(d -> result.add(new ContextualDiagnostic("", physical, "FOOTNOTE", -1, -1, d)));
        }
        decorations.forEach(p -> p.layout().diagnostics().forEach(d -> result.add(new ContextualDiagnostic("", p.pageIndex(), p.region().name(), -1, -1, d))));
        result.addAll(referenceDiagnostics);
        return List.copyOf(result);
    }

    /** Collects structured fallback information and missing-glyph warnings without logging document text. */
    public List<TextDiagnostic> diagnostics() {
        var result = new java.util.ArrayList<TextDiagnostic>();
        for (var page : body.pages()) {
            for (var fragment : page.elements()) {
                SemanticFragments.walk(fragment, located -> {
                    if (located.fragment() instanceof ComponentFragment component) { result.addAll(component.layout().diagnostics()); }
                }, leaf -> result.addAll(leaf.layout().diagnostics()));
            }
        }
        contentsPages.forEach(page -> result.addAll(page.diagnostics()));
        decorations.forEach(placed -> result.addAll(placed.layout().diagnostics()));
        footnotes.forEach(page -> result.addAll(page.diagnostics()));
        resolvedReferences.forEach(reference -> result.addAll(reference.diagnostics()));
        referenceDiagnostics.stream().filter(d -> d.region().startsWith("INLINE_FIELD:")).forEach(d -> result.add(d.diagnostic()));
        return List.copyOf(result);
    }
}
