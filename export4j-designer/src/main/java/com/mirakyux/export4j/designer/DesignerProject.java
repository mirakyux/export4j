// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Immutable project snapshots; binding data remains a separate JSON document. */
record DesignerProject(XmlTemplateBundle bundle, String data) {
    DesignerProject {
        Objects.requireNonNull(bundle, "bundle"); BindingJson.parse(data);
    }
    DesignerProject source(String source, String json) throws Exception {
        var root = DesignerXml.parse(source).getDocumentElement();
        if (!root.getTagName().equals("document")) { throw new IllegalArgumentException("expected document root"); }
        var candidate = bundle.toBuilder().source(source).build();
        candidate.compile();
        return new DesignerProject(candidate, json);
    }
    static final class History {
        private DesignerProject current;
        private final Deque<DesignerProject> undo = new ArrayDeque<>();
        private final Deque<DesignerProject> redo = new ArrayDeque<>();
        History(DesignerProject project) { current = project; }
        DesignerProject current() { return current; }
        void change(DesignerProject candidate) {
            if (candidate == current) { return; }
            undo.push(current); while (undo.size() > 8) { undo.removeLast(); }
            current = candidate; redo.clear();
        }
        boolean canUndo() { return !undo.isEmpty(); }
        boolean canRedo() { return !redo.isEmpty(); }
        void undo() { if (canUndo()) { redo.push(current); current = undo.pop(); } }
        void redo() { if (canRedo()) { undo.push(current); current = redo.pop(); } }
    }
}
