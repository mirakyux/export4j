// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Task-confined literal replacement in page Tj/TJ operators, without page reflow.
 * Explicit replacement fonts are embedded. Passive Span marked content can be retained while editing
 * outside it; matches inside remain unsupported. Form invocations are edited through private
 * copies along the changed invocation path. Same-line matches across supported font, colour,
 * spacing and positioning changes require explicit style/spacing degradation policies.
 * This edits content; it does not sanitize other copies or document history.
 */
public final class PdfTextEditor {
    private record Replacement(int page, String search, String replacement, PdfFont font) { }
    private record Candidate(List<Object> tokens, PDResources resources, int changes) { }
    private static final Set<String> UNSUPPORTED = Set.of("MP", "DP", "'", "\"");
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private final List<Replacement> edits = new ArrayList<>();
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfTextEditor(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    /** Zero-based page; matches can cross supported Tj/TJ, font, colour and same-line positioning
     * changes. Ambiguous geometry, text-object boundaries and unsupported operators end a sequence.
     * Style/spacing changes require explicit loss policy acceptance. Empty replacement is allowed.
     * Every supported invocation on the selected page is considered, including nested Forms. Changed
     * invocation paths receive private stream/resource copies; other pages and unmatched instances retain
     * their source objects. The result counts successful literal matches across actual invocations.
     */
    public PdfTextEditor replace(int page, String search, String replacement, PdfFont font) {
        if (page < 0 || text(search).isEmpty()) { throw new IllegalArgumentException("invalid text replacement selection"); }
        text(replacement); Objects.requireNonNull(font, "font");
        if (edits.size() >= limits.maxObjects() || search.length() > limits.maxTextCharacters() || replacement.length() > limits.maxTextCharacters()) { throw new IllegalArgumentException("PDF replacement request budget exceeded"); }
        edits.add(new Replacement(page, search, replacement, font)); return this;
    }
    public PdfTextEditor policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfTextEditor execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    public PreparedPdf prepare() throws IOException {
        var operations = List.copyOf(edits); var strategy = policy; var control = execution;
        return ExecutionContext.execute(control, () -> {
            var budget = adapter.preflight(false);
            try (var document = adapter.openForEdit(input, budget)) {
                var state = new State(document, strategy);
                if (document.isEncrypted()) { state.loss("ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "OUTPUT_IS_UNENCRYPTED", -1); }
                for (var edit : operations) {
                    ExecutionContext.checkpoint();
                    if (edit.page() >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, edit.page()); }
                    state.edit(document.getPage(edit.page()), edit);
                    // A later replacement reads this candidate as a new source. Complete its
                    // explicit embedded programs before that read; a pending subset has no bytes yet.
                    for (var font : state.usedFonts) { if (font.willBeSubset()) { font.subset(); } }
                    state.usedFonts.clear(); state.fonts.clear();
                }
                adapter.preflight(false).inspect(document);
                return PreparedPdf.encode(document, state.changes, state.issues, limits, control);
            }
        });
    }
    private final class State {
        final PDDocument document; final ProcessingPolicy strategy;
        final List<ProcessingIssue> issues = new ArrayList<>();
        final Map<PdfFont, PDType0Font> fonts = new IdentityHashMap<>();
        final Set<PDType0Font> usedFonts = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final PdfTextForms forms = new PdfTextForms(limits);
        long textUnits; long fontBytes; int changes;
        State(PDDocument document, ProcessingPolicy strategy) { this.document = document; this.strategy = strategy; fontBytes = input.byteSize(); }
        void count(long value) throws IOException {
            ExecutionContext.checkpoint(); if (value > limits.maxTextCharacters() - textUnits) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, -1); } textUnits += value;
        }
        void loss(String feature, ProcessingPolicy.Treatment treatment, String effect, int page) {
            PdfImageSupport.loss(strategy, issues, "PDF", feature, "REPLACE_TEXT", treatment, effect, page);
        }
        void edit(PDPage page, Replacement edit) throws IOException {
            String resourceIssue = new PdfRasterResources(limits).check(page);
            if (resourceIssue != null) { loss("TEXT_RESOURCE", ProcessingPolicy.Treatment.SKIP_BLOCK, "PAGE_TEXT_UNCHANGED", edit.page()); return; }
            var tokens = forms.page(page);
            if (!structure(page.getResources(), tokens, new TextState(), 0)) { loss("TEXT_STRUCTURE", ProcessingPolicy.Treatment.SKIP_BLOCK, "PAGE_TEXT_UNCHANGED", edit.page()); return; }
            var candidate = rewrite(page.getResources(), tokens, edit, new TextState(), 0);
            if (candidate.changes() > 0) {
                page.setContents(encoded(candidate.tokens())); page.setResources(candidate.resources());
                changes = Math.addExact(changes, candidate.changes());
            }
        }
        Candidate rewrite(PDResources sourceResources, List<Object> tokens, Replacement edit, TextState inherited, int enclosingMarkedDepth) throws IOException {
            var output = new ArrayList<Object>(); var operands = new ArrayList<COSBase>(); var stack = new ArrayDeque<TextState>();
            var state = inherited.copy(); boolean inText = false; int streamChanges = 0, markedDepth = enclosingMarkedDepth;
            boolean markedMatchReported = false;
            PDResources resources = copy(sourceResources);
            for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
                var token = tokens.get(tokenIndex);
                ExecutionContext.checkpoint();
                if (!(token instanceof Operator operator)) { operands.add((COSBase) token); continue; }
                forms.operator(true);
                String name = operator.getName();
                if (name.equals("BDC") || name.equals("BMC")) {
                    markedDepth++;
                    if (!markedMatchReported && markedText(sourceResources, operands, name).contains(edit.search())) {
                        loss("MARKED_TEXT", ProcessingPolicy.Treatment.SKIP_MATCH, "MARKED_CONTENT_UNCHANGED", edit.page()); markedMatchReported = true;
                    }
                } else if (name.equals("EMC")) { markedDepth--; }
                else if (name.equals("q")) { stack.push(state.copy()); }
                else if (name.equals("Q")) { if (stack.isEmpty()) { throw PdfObjects.invalid(); } state = stack.pop(); }
                else if (name.equals("BT")) { if (inText) { throw PdfObjects.invalid(); } inText = true; }
                else if (name.equals("ET")) { if (!inText) { throw PdfObjects.invalid(); } inText = false; }
                else if (name.equals("Tf")) {
                    if (operands.size() != 2 || !(operands.get(0) instanceof COSName fontName)) { throw PdfObjects.invalid(); }
                    state.name = fontName; state.size = number(operands, 1); if (state.size <= 0) { throw PdfObjects.invalid(); }
                    PdfTextForms.requireFont(sourceResources, fontName); state.font = sourceResources.getFont(fontName);
                } else if (Set.of("Tc", "Tw", "Tz", "Tr", "Ts").contains(name)) {
                    if (operands.size() != 1) { throw PdfObjects.invalid(); } state.values.put(name, number(operands, 0));
                } else if (name.equals("gs")) {
                    if (operands.size() != 1 || !(operands.getFirst() instanceof COSName gs)) { throw PdfObjects.invalid(); }
                    var setting = sourceResources.getExtGState(gs).getFontSetting();
                    if (setting != null) { state.font = setting.getFont(); state.size = setting.getFontSize(); state.name = null; }
                }
                state.position(name, operands);
                if (name.equals("Do")) {
                    var form = forms.form(sourceResources, operands);
                    if (form != null) {
                        var candidate = rewrite(forms.resources(form, sourceResources), forms.tokens(form), edit, state, markedDepth);
                        if (candidate.changes() > 0) {
                            var cloned = cloneForm(form, candidate);
                            output.add(resources.add(cloned)); output.add(operator);
                            streamChanges = Math.addExact(streamChanges, candidate.changes()); operands.clear(); continue;
                        }
                    }
                } else if (name.equals("Tj") || name.equals("TJ")) {
                    if (!inText || state.font == null || operands.size() != 1) { throw PdfObjects.invalid(); }
                    var font = state.font;
                    if (font == null || !font.isEmbedded()) { throw PdfObjects.invalid(); }
                    if (markedDepth == 0 && advancedBoundary(tokens, tokenIndex)) {
                        var group = advanced(tokens, tokenIndex, operands.getFirst(), state, sourceResources);
                        int replaced = replaceAdvanced(group, edit, resources, output);
                        if (replaced == 0) { output.addAll(group.originals()); }
                        streamChanges = Math.addExact(streamChanges, replaced);
                        state = group.endState(); operands.clear(); tokenIndex = group.lastIndex(); continue;
                    }
                    var group = shows(tokens, tokenIndex, operands.getFirst(), font);
                    var shown = group.shown();
                    if (shown.text().contains(edit.search())) {
                        if (markedDepth > 0) {
                            if (!markedMatchReported) { loss("MARKED_TEXT", ProcessingPolicy.Treatment.SKIP_MATCH, "MARKED_CONTENT_UNCHANGED", edit.page()); markedMatchReported = true; }
                        } else if (font.isVertical() || !state.simple() || !PdfPageEditor.simpleText(shown.text()) || !PdfPageEditor.simpleText(edit.replacement())) {
                            loss("TEXT_SHAPING", ProcessingPolicy.Treatment.SKIP_MATCH, "SHOW_SEQUENCE_UNCHANGED", edit.page());
                        } else {
                            int replaced = replaceShown(shown, edit, state, resources, output);
                            if (replaced > 0) { streamChanges = Math.addExact(streamChanges, replaced); state.advance(shown); operands.clear(); tokenIndex = group.lastIndex(); continue; }
                        }
                    }
                    output.addAll(group.originals()); state.advance(shown); operands.clear(); tokenIndex = group.lastIndex(); continue;
                }
                output.addAll(operands); output.add(operator); operands.clear();
            }
            if (inText || !stack.isEmpty() || !operands.isEmpty() || markedDepth != enclosingMarkedDepth) { throw PdfObjects.invalid(); }
            return new Candidate(List.copyOf(output), resources, streamChanges);
        }
        PDStream encoded(List<Object> tokens) throws IOException {
            var stream = new PDStream(document); var data = new PdfImageSupport.Bytes(limits.maxDecodedStreamBytes()); new ContentStreamWriter(data).writeTokens(tokens);
            try (var target = stream.createOutputStream(COSName.FLATE_DECODE)) { target.write(data.data()); }
            return stream;
        }
        PDFormXObject cloneForm(PDFormXObject source, Candidate candidate) throws IOException {
            var clone = new PDFormXObject(encoded(candidate.tokens()).getCOSObject());
            for (var entry : source.getCOSObject().entrySet()) {
                if (!Set.of(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.RESOURCES).contains(entry.getKey())) { clone.getCOSObject().setItem(entry.getKey(), entry.getValue()); }
            }
            clone.setResources(candidate.resources()); return clone;
        }
        /** Validate the whole marked structure before any edit or resource mutation. */
        boolean structure(PDResources resources, List<Object> tokens, TextState inherited, int invocationDepth) throws IOException {
            int depth = 0; boolean supported = true, inText = false; var operands = new ArrayList<COSBase>();
            var state = inherited.copy(); var stack = new ArrayDeque<TextState>();
            for (var token : tokens) {
                ExecutionContext.checkpoint();
                if (!(token instanceof Operator operator)) { operands.add((COSBase) token); continue; }
                forms.operator(false);
                String name = operator.getName();
                if (UNSUPPORTED.contains(name)) { supported = false; }
                if (name.equals("gs")) {
                    if (!forms.graphicsStates.supported(resources, operands)) { supported = false; }
                    var setting = resources.getExtGState((COSName) operands.getFirst()).getFontSetting();
                    if (setting != null) { state.font = setting.getFont(); state.size = setting.getFontSize(); }
                }
                if (name.equals("Do")) {
                    if (inText) { throw PdfObjects.invalid(); }
                    var form = forms.form(resources, operands);
                    if (form != null) {
                        boolean formSupported = forms.supported(form);
                        int childDepth = invocationDepth + stack.size() + 1;
                        forms.enter(form, childDepth);
                        try { formSupported &= structure(forms.resources(form, resources), forms.tokens(form), state, childDepth); }
                        finally { forms.leave(form); }
                        supported &= formSupported;
                    }
                } else if (name.equals("Tf")) {
                    if (operands.size() != 2 || !(operands.getFirst() instanceof COSName fontName)) { throw PdfObjects.invalid(); }
                    PdfTextForms.requireFont(resources, fontName); state.name = fontName; state.font = resources.getFont(fontName);
                    state.size = number(operands, 1); if (state.size <= 0) { throw PdfObjects.invalid(); }
                } else if (name.equals("q")) {
                    if (!operands.isEmpty()) { throw PdfObjects.invalid(); }
                    if (invocationDepth + stack.size() + 1 > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
                    stack.push(state.copy());
                } else if (name.equals("Q")) {
                    if (!operands.isEmpty() || stack.isEmpty()) { throw PdfObjects.invalid(); } state = stack.pop();
                } else if (name.equals("BT")) {
                    if (!operands.isEmpty() || inText) { throw PdfObjects.invalid(); } inText = true;
                } else if (name.equals("ET")) {
                    if (!operands.isEmpty() || !inText) { throw PdfObjects.invalid(); } inText = false;
                } else if (name.equals("Tj") || name.equals("TJ")) {
                    if (!inText || state.font == null || operands.size() != 1) { throw PdfObjects.invalid(); }
                } else if (name.equals("cm")) {
                    if (operands.size() != 6) { throw PdfObjects.invalid(); } for (int i = 0; i < 6; i++) { number(operands, i); }
                }
                if (name.equals("BMC") || name.equals("BDC")) {
                    if (++depth > limits.maxDepth()) { throw new IllegalArgumentException("PDF marked content nesting budget exceeded"); }
                    var text = markedText(resources, operands, name);
                    if (text == null) { supported = false; } else { count(text.length()); }
                } else if (name.equals("EMC")) {
                    if (!operands.isEmpty() || --depth < 0) { throw PdfObjects.invalid(); }
                }
                operands.clear();
            }
            if (depth != 0 || inText || !stack.isEmpty() || !operands.isEmpty()) { throw PdfObjects.invalid(); }
            return supported;
        }
        /** null means a valid but unsupported tag/property. Malformed syntax never enters policy fallback. */
        String markedText(PDResources resources, List<COSBase> operands, String operator) throws IOException {
            int count = operator.equals("BMC") ? 1 : 2;
            if (operands.size() != count || !(operands.getFirst() instanceof COSName tag)) { throw PdfObjects.invalid(); }
            if (count == 1) { return tag.getName().equals("Span") ? "" : null; }
            COSBase value = operands.get(1);
            if (value instanceof COSName name) {
                var properties = resources == null ? null : resources.getCOSObject().getCOSDictionary(COSName.PROPERTIES);
                value = properties == null ? null : properties.getDictionaryObject(name);
            }
            if (!(value instanceof COSDictionary properties)) { throw PdfObjects.invalid(); }
            var actual = properties.getDictionaryObject(COSName.ACTUAL_TEXT);
            if (actual != null && !(actual instanceof COSString)) { throw PdfObjects.invalid(); }
            if (!tag.getName().equals("Span") || properties.size() != 1 || !(actual instanceof COSString text)) { return null; }
            return text.getString();
        }
        ShowGroup shows(List<Object> tokens, int index, COSBase first, PDFont font) throws IOException {
            var text = new StringBuilder(); var atoms = new ArrayList<Atom>(); var originals = new ArrayList<Object>();
            COSBase value = first;
            while (true) {
                var operator = (Operator) tokens.get(index); var shown = decode(value, font, operator.getName().equals("TJ"));
                int offset = text.length(); text.append(shown.text());
                for (var atom : shown.atoms()) { atoms.add(new Atom(atom.value(), offset + atom.start(), offset + atom.end(), atom.advance())); }
                originals.add(value); originals.add(operator);
                if (index + 2 >= tokens.size() || !(tokens.get(index + 1) instanceof COSBase next)
                        || !(tokens.get(index + 2) instanceof Operator following)
                        || !(following.getName().equals("Tj") || following.getName().equals("TJ"))) { break; }
                value = next; index += 2;
            }
            return new ShowGroup(new Shown(text.toString(), List.copyOf(atoms)), index, List.copyOf(originals));
        }
        boolean advancedBoundary(List<Object> tokens, int index) {
            boolean boundary = false;
            for (int i = index + 1; i < tokens.size(); i++) {
                if (!(tokens.get(i) instanceof Operator op)) { continue; }
                String name = op.getName();
                if (name.equals("Tj") || name.equals("TJ")) { if (boundary) { return true; } }
                else if (RUN_OPERATORS.contains(name)) { boundary = true; }
                else { return false; }
            }
            return false;
        }
        AdvancedGroup advanced(List<Object> tokens, int index, COSBase first, TextState initial, PDResources resources) throws IOException {
            var originals = new ArrayList<Object>(); var segments = new ArrayList<Segment>(); var text = new StringBuilder();
            var current = initial.copy(); var accepted = current.copy(); var pending = new ArrayList<Object>(); var values = new ArrayList<COSBase>();
            int last = index, boundaryVersion = 0; double previousEnd = 0; boolean positioned = false;
            for (int i = index; i < tokens.size(); i++) {
                ExecutionContext.checkpoint();
                Object token = tokens.get(i);
                if (!(token instanceof Operator op)) { values.add((COSBase) token); pending.add(token); continue; }
                String name = op.getName();
                if (name.equals("Tj") || name.equals("TJ")) {
                    COSBase operand = i == index ? first : values.size() == 1 ? values.getFirst() : null;
                    if (operand == null || current.font == null || !current.font.isEmbedded()) { throw PdfObjects.invalid(); }
                    double x = initial.relativeX(current);
                    if (positioned && (!initial.sameBaseline(current) || x < previousEnd - .01 || x - previousEnd > Math.max(initial.size, current.size) * .5)) { break; }
                    var shown = decode(operand, current.font, name.equals("TJ"));
                    if (i == index) { originals.add(first); } else { originals.addAll(pending); }
                    int operatorOffset = originals.size(); originals.add(op);
                    segments.add(new Segment(shown, current.copy(), text.length(), x, operatorOffset, boundaryVersion)); text.append(shown.text());
                    current.advance(shown); previousEnd = initial.relativeX(current); positioned = true;
                    accepted = current.copy(); last = i; pending.clear(); values.clear(); continue;
                }
                if (!RUN_OPERATORS.contains(name)) { break; }
                if (name.equals("Tf")) {
                    if (values.size() != 2 || !(values.getFirst() instanceof COSName fontName)) { throw PdfObjects.invalid(); }
                    PdfTextForms.requireFont(resources, fontName); current.name = fontName; current.font = resources.getFont(fontName); current.size = number(values, 1);
                    if (current.size <= 0) { throw PdfObjects.invalid(); }
                } else if (Set.of("Tc", "Tw", "Tz", "Tr", "Ts").contains(name)) {
                    if (values.size() != 1) { throw PdfObjects.invalid(); } current.values.put(name, number(values, 0));
                }
                current.position(name, values); boundaryVersion++; pending.add(op); values.clear();
            }
            return new AdvancedGroup(text.toString(), List.copyOf(segments), List.copyOf(originals), last, accepted);
        }
        int replaceAdvanced(AdvancedGroup group, Replacement edit, PDResources resources, List<Object> output) throws IOException {
            var glyphs = new ArrayList<RunAtom>();
            var editable = new boolean[group.segments().size()];
            var bySegment = new ArrayList<List<RunAtom>>(); var firstAt = new java.util.HashMap<Integer, Integer>(); var lastAt = new java.util.HashMap<Integer, Integer>();
            for (int si = 0; si < group.segments().size(); si++) {
                ExecutionContext.checkpoint(); var members = new ArrayList<RunAtom>(); bySegment.add(members);
                var segment = group.segments().get(si); double x = segment.x();
                editable[si] = segment.state().editable() && PdfPageEditor.simpleText(segment.shown().text());
                for (int ai = 0; ai < segment.shown().atoms().size(); ai++) {
                    var atom = segment.shown().atoms().get(ai); double next = x + segment.state().advance(atom);
                    var value = new RunAtom(si, ai, segment.offset() + atom.start(), segment.offset() + atom.end(), x, next);
                    if (value.end() > value.start()) { firstAt.put(value.start(), glyphs.size()); lastAt.put(value.end(), glyphs.size()); }
                    glyphs.add(value); members.add(value); x = next;
                }
            }
            var deleted = new java.util.HashSet<RunAtom>(); var insertions = new java.util.HashMap<RunAtom, byte[]>();
            int count = 0, offset = 0;
            while ((offset = group.text().indexOf(edit.search(), offset)) >= 0) {
                count(edit.replacement().length()); int end = offset + edit.search().length();
                int begin = offset; offset = end; var firstIndex = firstAt.get(begin); var lastIndex = lastAt.get(end);
                if (firstIndex == null || lastIndex == null || lastIndex < firstIndex) {
                    loss("TEXT_BOUNDARY", ProcessingPolicy.Treatment.SKIP_MATCH, "MATCH_SPLITS_ENCODED_GLYPH", edit.page()); continue;
                }
                var selected = glyphs.subList(firstIndex, lastIndex + 1);
                var first = selected.getFirst(); var last = selected.getLast(); var style = group.segments().get(first.segment()).state();
                if (!Double.isFinite(first.left()) || !Double.isFinite(last.right())) {
                    loss("TEXT_POSITION", ProcessingPolicy.Treatment.SKIP_MATCH, "AMBIGUOUS_TEXT_MATRIX_UNCHANGED", edit.page()); continue;
                }
                if (selected.stream().anyMatch(atom -> !editable[atom.segment()])
                        || !PdfPageEditor.simpleText(edit.search()) || !PdfPageEditor.simpleText(edit.replacement())) {
                    loss("TEXT_SHAPING", ProcessingPolicy.Treatment.SKIP_MATCH, "SHOW_SEQUENCE_UNCHANGED", edit.page()); continue;
                }
                var replacement = font(edit.font()); byte[] bytes;
                try { bytes = replacement.encode(edit.replacement()); } catch (IllegalArgumentException failure) { throw new IOException("explicit replacement font lacks required glyphs", failure); }
                double width = replacement.getStringWidth(edit.replacement()) / 1000 * style.size * style.scale();
                if (width > last.right() - first.left() + .001 || last.right() < first.left()) {
                    loss("TEXT_CAPACITY", ProcessingPolicy.Treatment.SKIP_MATCH, "MATCH_UNCHANGED", edit.page()); continue;
                }
                loss("TEXT_FONT", ProcessingPolicy.Treatment.DEGRADE, "MATCH_USES_EXPLICIT_FONT", edit.page());
                if (group.segments().get(first.segment()).boundaryVersion() != group.segments().get(last.segment()).boundaryVersion()) {
                    loss("TEXT_STYLE", ProcessingPolicy.Treatment.DEGRADE, "MATCH_USES_FIRST_GLYPH_STYLE_AND_BASELINE", edit.page());
                }
                if (selected.stream().anyMatch(atom -> {
                    var segment = group.segments().get(atom.segment()); var value = segment.shown().atoms().get(atom.atom());
                    return value.value() instanceof COSNumber && value.advance() != 0 || !segment.state().simple();
                })) { loss("TEXT_SPACING", ProcessingPolicy.Treatment.DEGRADE, "MATCH_INTERNAL_SPACING_REPLACED", edit.page()); }
                deleted.addAll(selected); insertions.put(first, bytes); usedFonts.add(replacement);
                if (replacement.willBeSubset()) { edit.replacement().codePoints().forEach(replacement::addToSubset); }
                count++;
            }
            if (count == 0) { return 0; }
            var replacements = new java.util.HashMap<Integer, List<Object>>();
            for (int si = 0; si < group.segments().size(); si++) {
                ExecutionContext.checkpoint();
                var segment = group.segments().get(si); var rewritten = new ArrayList<Object>();
                var array = new COSArray();
                for (var atom : bySegment.get(si)) {
                    ExecutionContext.checkpoint();
                    var original = segment.shown().atoms().get(atom.atom());
                    if (!deleted.contains(atom)) { array.add(original.value()); continue; }
                    if (insertions.containsKey(atom)) {
                        if (array.size() > 0) { rewritten.add(array); rewritten.add(Operator.getOperator("TJ")); array = new COSArray(); }
                        var style = segment.state(); var explicit = font(edit.font());
                        rewritten.add(resources.add(explicit)); rewritten.add(new COSFloat((float) style.size)); rewritten.add(Operator.getOperator("Tf"));
                        for (String key : List.of("Tc", "Tw")) { rewritten.add(COSFloat.ZERO); rewritten.add(Operator.getOperator(key)); }
                        var inserted = new COSArray(); inserted.add(new COSString(insertions.get(atom)));
                        inserted.add(new COSFloat((float) (explicit.getStringWidth(edit.replacement()) - (atom.right() - atom.left()) * 1000 / style.size / style.scale())));
                        rewritten.add(inserted); rewritten.add(Operator.getOperator("TJ"));
                        rewritten.add(resources.add(style.font)); rewritten.add(new COSFloat((float) style.size)); rewritten.add(Operator.getOperator("Tf"));
                        for (String key : List.of("Tc", "Tw")) { rewritten.add(new COSFloat(style.values.getOrDefault(key, 0d).floatValue())); rewritten.add(Operator.getOperator(key)); }
                    } else { array.add(new COSFloat((float) (-(atom.right() - atom.left()) * 1000 / segment.state().size / segment.state().scale()))); }
                }
                if (array.size() > 0) { rewritten.add(array); rewritten.add(Operator.getOperator("TJ")); }
                replacements.put(segment.operatorOffset() - 1, rewritten);
            }
            for (int i = 0; i < group.originals().size(); i++) {
                var replacement = replacements.get(i);
                if (replacement != null) { output.addAll(replacement); i++; } else { output.add(group.originals().get(i)); }
            }
            return count;
        }
        PDType0Font font(PdfFont source) throws IOException {
            var existing = fonts.get(source); if (existing != null) { return existing; }
            int length = source.bytes().length;
            if (length > limits.maxDecodedStreamBytes()) { throw new PdfOperationException(PdfOperationException.Code.DECODED_STREAM_LIMIT, 0, -1); }
            if (length > limits.maxTotalInputBytes() - fontBytes) { throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT, 0, -1); }
            fontBytes += length;
            var loaded = PDType0Font.load(document, new ByteArrayInputStream(source.bytes()), source.subsetAllowed()); fonts.put(source, loaded); return loaded;
        }
        int replaceShown(Shown shown, Replacement edit, TextState state, PDResources resources, List<Object> output) throws IOException {
            var result = new ArrayList<Object>(); int offset = 0; int consumed = 0; int changed = 0; int scan = 0;
            while ((offset = shown.text().indexOf(edit.search(), offset)) >= 0) {
                count(edit.replacement().length()); int end = offset + edit.search().length();
                int first = -1; int last = -1;
                for (int index = scan; index < shown.atoms().size(); index++) {
                    var atom = shown.atoms().get(index);
                    if (atom.start() >= end && atom.end() > end) { break; }
                    scan = index + 1;
                    if (atom.end() > atom.start() && atom.start() == offset) { first = index; }
                    if (atom.end() > atom.start() && atom.end() == end) { last = index; break; }
                }
                offset = end;
                if (first < 0 || last < first) { loss("TEXT_BOUNDARY", ProcessingPolicy.Treatment.SKIP_MATCH, "MATCH_SPLITS_ENCODED_GLYPH", edit.page()); continue; }
                var selected = shown.atoms().subList(first, last + 1);
                double advance = selected.stream().mapToDouble(Atom::advance).sum();
                var replacementFont = font(edit.font()); byte[] encoded;
                try { encoded = replacementFont.encode(edit.replacement()); }
                catch (IllegalArgumentException exception) { throw new IOException("explicit replacement font lacks required glyphs", exception); }
                double width = replacementFont.getStringWidth(edit.replacement());
                if (width > advance + .001 || advance < 0) { loss("TEXT_CAPACITY", ProcessingPolicy.Treatment.SKIP_MATCH, "MATCH_UNCHANGED", edit.page()); continue; }
                loss("TEXT_FONT", ProcessingPolicy.Treatment.DEGRADE, "MATCH_USES_EXPLICIT_FONT", edit.page());
                if (selected.stream().anyMatch(atom -> atom.value() instanceof COSNumber && atom.advance() != 0)) { loss("TEXT_SPACING", ProcessingPolicy.Treatment.DEGRADE, "MATCH_INTERNAL_KERNING_REPLACED", edit.page()); }
                append(result, shown.atoms().subList(consumed, first));
                COSName name = resources.add(replacementFont); usedFonts.add(replacementFont);
                if (replacementFont.willBeSubset()) { edit.replacement().codePoints().forEach(replacementFont::addToSubset); }
                result.add(name); result.add(new COSFloat((float) state.size)); result.add(Operator.getOperator("Tf"));
                var array = new COSArray(); array.add(new COSString(encoded)); array.add(new COSFloat((float) (width - advance)));
                result.add(array); result.add(Operator.getOperator("TJ"));
                result.add(resources.add(state.font)); result.add(new COSFloat((float) state.size)); result.add(Operator.getOperator("Tf"));
                consumed = last + 1; changed++;
            }
            if (changed > 0) { append(result, shown.atoms().subList(consumed, shown.atoms().size())); output.addAll(result); }
            return changed;
        }
        Shown decode(COSBase value, PDFont font, boolean array) throws IOException {
            var text = new StringBuilder(); var atoms = new ArrayList<Atom>();
            List<? extends COSBase> values = array && value instanceof COSArray items ? items.toList() : List.of(value);
            if (array && !(value instanceof COSArray)) { throw PdfObjects.invalid(); }
            for (var part : values) {
                if (part instanceof COSString string) {
                    byte[] bytes = string.getBytes();
                    try (var input = new ByteArrayInputStream(bytes)) {
                        while (input.available() > 0) {
                            int byteStart = bytes.length - input.available(); int start = text.length();
                            int code = font.readCode(input); PdfRasterResources.requireGlyph(font, code);
                            String unicode = font.toUnicode(code); count(1);
                            if (unicode == null || unicode.isEmpty()) { throw PdfObjects.invalid(); }
                            text.append(text(unicode)); double advance = font.getWidth(code);
                            if (!Double.isFinite(advance)) { throw PdfObjects.invalid(); }
                            atoms.add(new Atom(new COSString(java.util.Arrays.copyOfRange(bytes, byteStart, bytes.length - input.available())), start, text.length(), advance));
                        }
                    }
                } else if (array && part instanceof COSNumber number) {
                    double amount = number.floatValue(); if (!Double.isFinite(amount)) { throw PdfObjects.invalid(); }
                    atoms.add(new Atom(number, text.length(), text.length(), -amount));
                } else { throw PdfObjects.invalid(); }
            }
            count(text.length()); return new Shown(text.toString(), List.copyOf(atoms));
        }
    }
    private static void append(List<Object> output, List<Atom> atoms) {
        if (atoms.isEmpty()) { return; } var values = new COSArray(); for (var atom : atoms) { values.add(atom.value()); } output.add(values); output.add(Operator.getOperator("TJ"));
    }
    private record Atom(COSBase value, int start, int end, double advance) { }
    private record Shown(String text, List<Atom> atoms) { }
    private record ShowGroup(Shown shown, int lastIndex, List<Object> originals) { }
    private static final Set<String> RUN_OPERATORS = Set.of("Tf", "Tc", "Tw", "Tz", "Tr", "Ts", "TL", "Tm", "Td", "TD", "g", "G", "rg", "RG", "k", "K");
    private record Segment(Shown shown, TextState state, int offset, double x, int operatorOffset, int boundaryVersion) { }
    private record AdvancedGroup(String text, List<Segment> segments, List<Object> originals, int lastIndex, TextState endState) { }
    private record RunAtom(int segment, int atom, int start, int end, double left, double right) { }
    private static final class TextState {
        COSName name; PDFont font; double size; final Map<String, Double> values = new java.util.HashMap<>();
        double a = 1, b, c, d = 1, x, y, lx, ly, leading;
        TextState copy() { var copy = new TextState(); copy.name = name; copy.font = font; copy.size = size; copy.values.putAll(values); copy.a = a; copy.b = b; copy.c = c; copy.d = d; copy.x = x; copy.y = y; copy.lx = lx; copy.ly = ly; copy.leading = leading; return copy; }
        boolean simple() { return values.entrySet().stream().allMatch(entry -> entry.getValue() == (entry.getKey().equals("Tz") ? 100 : 0)); }
        double scale() { return values.getOrDefault("Tz", 100d) / 100; }
        boolean editable() { return !font.isVertical() && scale() > 0 && values.getOrDefault("Tr", 0d) >= 0 && values.getOrDefault("Tr", 0d) < 3; }
        double advance(Atom atom) {
            if (atom.value() instanceof COSNumber) { return atom.advance() / 1000 * size * scale(); }
            byte[] bytes = ((COSString) atom.value()).getBytes();
            return (atom.advance() / 1000 * size + values.getOrDefault("Tc", 0d) + (bytes.length == 1 && bytes[0] == 32 ? values.getOrDefault("Tw", 0d) : 0)) * scale();
        }
        void advance(Shown shown) { for (var atom : shown.atoms()) { double distance = advance(atom); x += distance * a; y += distance * b; } }
        double relativeX(TextState other) { double norm = a * a + b * b; return norm == 0 ? Double.NaN : ((other.x - x) * a + (other.y - y) * b) / norm; }
        boolean sameBaseline(TextState other) { return Math.abs(a - other.a) < 1e-6 && Math.abs(b - other.b) < 1e-6 && Math.abs(c - other.c) < 1e-6 && Math.abs(d - other.d) < 1e-6 && Math.abs((other.y - y) * a - (other.x - x) * b) < .01; }
        void position(String operator, List<COSBase> operands) throws IOException {
            if (operator.equals("BT")) { a = d = 1; b = c = x = y = lx = ly = 0; }
            else if (operator.equals("Tm")) {
                if (operands.size() != 6) { throw PdfObjects.invalid(); }
                a = number(operands, 0); b = number(operands, 1); c = number(operands, 2); d = number(operands, 3); x = lx = number(operands, 4); y = ly = number(operands, 5);
            } else if (operator.equals("Td") || operator.equals("TD") || operator.equals("T*")) {
                if (!operator.equals("T*") && operands.size() != 2) { throw PdfObjects.invalid(); }
                double tx = operator.equals("T*") ? 0 : number(operands, 0), ty = operator.equals("T*") ? -leading : number(operands, 1);
                if (operator.equals("TD")) { leading = -ty; } lx += tx * a + ty * c; ly += tx * b + ty * d; x = lx; y = ly;
            } else if (operator.equals("TL")) { if (operands.size() != 1) { throw PdfObjects.invalid(); } leading = number(operands, 0); }
        }
    }
    private static double number(List<COSBase> operands, int index) throws IOException {
        if (!(operands.get(index) instanceof COSNumber number) || !Double.isFinite(number.floatValue())) { throw PdfObjects.invalid(); } return number.floatValue();
    }

    private static PDResources copy(PDResources original) {
        var values = original == null ? new COSDictionary() : new COSDictionary(original.getCOSObject());
        for (var kind : List.of(COSName.FONT, COSName.XOBJECT)) {
            var group = values.getCOSDictionary(kind); if (group != null) { values.setItem(kind, new COSDictionary(group)); }
        }
        return new PDResources(values);
    }
    private static String text(String value) {
        Objects.requireNonNull(value, "text");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isHighSurrogate(c)) { if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) { throw new IllegalArgumentException("invalid Unicode"); } }
            else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) { throw new IllegalArgumentException("invalid text code point"); }
        }
        return value;
    }
}
