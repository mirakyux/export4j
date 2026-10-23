// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.edit.EditLimits;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Offline, same-format editing without semantic re-encoding of unknown document structures.
 * Instances of Prepared are immutable; requests are task-confined. Does not refresh fields/TOCs or
 * accept/reject revisions. Signed, protected and active packages have no supported editing path.
 */
public final class DocxPackageEditor {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private DocxPackageEditor() { }
    static Request request(DocxAdapter adapter, DocxLimits limits, byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > limits.maxInputBytes()) { throw new IllegalArgumentException("DOCX input byte budget exceeded"); }
        return new Request(adapter, limits, encoded.clone());
    }
    /** A change uses the sequential operation's paragraph text view, in UTF-16 units. */
    public record Change(String part, int paragraphIndex, int startUtf16, int endUtf16) { }
    public record Result(List<Change> changes, List<String> changedParts, int preservedParts, ProcessingReport report, long bytesWritten) {
        public Result { changes = List.copyOf(changes); changedParts = List.copyOf(changedParts); Objects.requireNonNull(report, "report"); }
    }
    private record Replacement(String needle, String value) { }
    /** Literal, case-sensitive replacements never span paragraphs. Defaults edit only word/document.xml;
     * additional explicit header/footer parts can be selected with part(). Unselected parts remain byte-identical.
     */
    public static final class Request {
        private final DocxAdapter adapter;
        private final DocxLimits packageLimits;
        private final byte[] source;
        private final List<Replacement> replacements = new ArrayList<>();
        private final Set<String> selectedParts = new HashSet<>(Set.of("word/document.xml"));
        private EditLimits limits = EditLimits.DEFAULT;
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private int maxOutputBytes = 64 * 1024 * 1024;
        private Request(DocxAdapter adapter, DocxLimits packageLimits, byte[] source) { this.adapter = adapter; this.packageLimits = packageLimits; this.source = source; }
        public Request policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Request limits(EditLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public Request maxOutputBytes(int value) { if (value <= 0) { throw new IllegalArgumentException("invalid DOCX output limit"); } maxOutputBytes = value; return this; }
        /** Selects an existing main/header/footer XML part, never a filesystem path. */
        public Request part(String name) {
            if (!Objects.requireNonNull(name, "part").matches("word/(document|header[0-9]+|footer[0-9]+)\\.xml")) { throw new IllegalArgumentException("unsupported editable DOCX part"); }
            selectedParts.add(name); return this;
        }
        public Request replaceText(String needle, String replacement) {
            text(needle); text(replacement);
            if (needle.isEmpty()) { throw new IllegalArgumentException("empty search text"); }
            if (replacements.size() >= limits.maxOperations()) { throw new IllegalArgumentException("DOCX edit operation budget exceeded"); }
            replacements.add(new Replacement(needle, replacement)); return this;
        }
        /** Validates the whole package first, then encodes the exact edit candidate once. */
        public Prepared prepare() throws IOException {
            var operations = List.copyOf(replacements); var selected = Set.copyOf(selectedParts);
            var state = new State(limits, policy); var control = execution; int outputLimit = maxOutputBytes;
            return ExecutionContext.execute(control, () -> {
                if (operations.size() > state.limits.maxOperations()) { throw new IllegalArgumentException("DOCX edit operation budget exceeded"); }
                var parts = adapter.unzip(new ByteArrayInputStream(source));
                var roots = validate(parts, state);
                for (String part : selected) {
                    if (!parts.containsKey(part)) { throw new IllegalArgumentException("selected DOCX part missing"); }
                    var root = roots.get(part);
                    String expected = part.equals("word/document.xml") ? "document" : part.startsWith("word/header") ? "hdr" : "ftr";
                    if (root == null || !word(root, expected)) { throw new IOException("invalid editable DOCX part root"); }
                }
                var changed = new HashSet<String>(); boolean skipped = false;
                try {
                    for (var operation : operations) {
                        for (String part : parts.keySet()) {
                            if (!selected.contains(part)) { continue; }
                            var paragraphs = roots.get(part).getElementsByTagNameNS(W, "p");
                            for (int index = 0; index < paragraphs.getLength(); index++) {
                                state.work(1);
                                if (replace((Element) paragraphs.item(index), operation, part, index, state)) { changed.add(part); }
                            }
                        }
                    }
                } catch (SkipRequest ignored) {
                    var last = state.issues.getLast(); state.issues.clear(); state.issues.add(last); state.changes.clear(); changed.clear(); skipped = true;
                }
                if (!skipped) {
                    long expanded = 0;
                    for (var entry : parts.entrySet()) {
                        if (changed.contains(entry.getKey())) { entry.setValue(encode(roots.get(entry.getKey()), packageLimits.maxEntryBytes())); }
                        expanded += entry.getValue().length;
                        if (expanded > packageLimits.maxExpandedBytes()) { throw new IOException("edited DOCX expanded byte budget exceeded"); }
                    }
                }
                byte[] encoded = changed.isEmpty() ? source : zip(parts, outputLimit);
                if (encoded.length > outputLimit) { throw new IllegalArgumentException("DOCX output byte budget exceeded"); }
                var changedNames = parts.keySet().stream().filter(changed::contains).toList();
                return new Prepared(encoded, new Result(state.changes, changedNames, parts.size() - changed.size(),
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues), encoded.length), control);
            });
        }
    }
    /** Unchanged entries retain their exact expanded bytes. Changed XML retains DOM structure except
     * selected text and xml:space; XML lexical form and ZIP metadata/compression may differ.
     */
    public static final class Prepared {
        private final byte[] encoded;
        private final Result result;
        private final ExecutionOptions execution;
        private Prepared(byte[] encoded, Result result, ExecutionOptions execution) { this.encoded = encoded; this.result = result; this.execution = execution; }
        public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, result.report().issues()); }
        /** Borrowed stream is neither closed nor flushed; a failed write may leave partial bytes. */
        public Result to(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            return ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(encoded); return result; });
        }
        /** Requires an existing parent and atomic-move support; failures preserve the old destination. */
        public Result to(Path output) throws IOException {
            Path target = Objects.requireNonNull(output, "output").toAbsolutePath();
            Path temporary = Files.createTempFile(target.getParent(), ".export4j-docx-edit-", ".tmp");
            try {
                return ExecutionContext.execute(execution, () -> {
                    Result completed;
                    try (var stream = Files.newOutputStream(temporary)) { completed = to(stream); }
                    ExecutionContext.checkpoint();
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return completed;
                });
            } catch (IOException | RuntimeException | Error failure) {
                try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure;
            }
        }
    }

    private static final class State {
        final EditLimits limits; final ProcessingPolicy policy;
        final List<ProcessingIssue> issues = new ArrayList<>(); final List<Change> changes = new ArrayList<>();
        final Set<Element> fieldParagraphs = new HashSet<>();
        long work; long text; int nodes; int matches;
        State(EditLimits limits, ProcessingPolicy policy) { this.limits = limits; this.policy = policy; }
        void work(long count) {
            ExecutionContext.checkpoint();
            if (count > limits.maxWork() - work) { throw new IllegalArgumentException("DOCX edit work budget exceeded"); } work += count;
        }
        ProcessingPolicy.Treatment issue(String feature, String part, int paragraph, int start, int end, boolean degrade) {
            var rule = policy.resolve("DOCX", feature, "REPLACE_TEXT");
            var effects = switch (rule.treatment()) {
                case DEGRADE -> List.of("REPLACEMENT_USES_FIRST_RUN_STYLE_AND_LINK");
                case SKIP_REQUEST -> List.of("REQUEST_NOT_APPLIED");
                case SKIP_BLOCK, PRESERVE -> List.of("ORIGINAL_PARAGRAPH_PRESERVED");
                default -> List.of("MATCH_NOT_REPLACED");
            };
            var issue = new ProcessingIssue("EDIT", "DOCX", "DOCX", feature, "REPLACE_TEXT",
                    new ProcessingIssue.Location("", "PACKAGE_PART:" + part, "p:" + paragraph, List.of(), start, end),
                    "literal match requires an explicit OOXML editing treatment", rule.action(), rule.treatment(), effects);
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (!(Set.of(ProcessingPolicy.Treatment.SKIP_MATCH, ProcessingPolicy.Treatment.SKIP_BLOCK, ProcessingPolicy.Treatment.SKIP_REQUEST,
                    ProcessingPolicy.Treatment.PRESERVE).contains(rule.treatment()) || degrade && rule.treatment() == ProcessingPolicy.Treatment.DEGRADE)) {
                throw new IllegalArgumentException("DOCX package edit treatment unavailable");
            }
            if (issues.size() >= limits.maxIssues()) { throw new IllegalArgumentException("DOCX editing report budget exceeded"); }
            issues.add(issue);
            if (rule.treatment() == ProcessingPolicy.Treatment.SKIP_REQUEST) { throw new SkipRequest(); }
            return rule.treatment();
        }
    }
    private static final class SkipRequest extends RuntimeException { private static final long serialVersionUID = 1L; }
    private record Span(Element text, Element run, Element hyperlink, int start, int end) { }
    private record Match(int start, int end, List<Span> spans) { }

    private static boolean replace(Element paragraph, Replacement operation, String part, int paragraphIndex, State state) {
        if (operation.needle().equals(operation.value())) { return false; }
        String source = paragraphText(paragraph); state.work(source.length());
        if (source.length() > state.limits.maxTextUtf16()) { throw new IllegalArgumentException("DOCX paragraph text budget exceeded"); }
        var spans = new ArrayList<Span>(); boolean supported = !state.fieldParagraphs.contains(paragraph) && ordinaryAncestors(paragraph) && spans(paragraph, null, spans, 0) == source.length();
        var boundaries = new BitSet(source.length() + 1); boundaries.set(0);
        var matcher = GRAPHEME.matcher(source); while (matcher.find()) { state.work(1); boundaries.set(matcher.end()); }
        var matches = new ArrayList<Match>(); int issuesBefore = state.issues.size();
        for (int start = source.indexOf(operation.needle()); start >= 0; start = source.indexOf(operation.needle(), start + operation.needle().length())) {
            state.work((long) operation.needle().length() + spans.size());
            if (++state.matches > state.limits.maxReplacements()) { throw new IllegalArgumentException("DOCX replacement budget exceeded"); }
            int end = start + operation.needle().length(); int at = start;
            if (splitsSurrogate(source, start) || splitsSurrogate(source, end)) { throw new IllegalArgumentException("replacement splits Unicode code point"); }
            var overlapping = spans.stream().filter(span -> span.start() < end && span.end() > at).toList();
            String feature = !boundaries.get(start) || !boundaries.get(end) ? "GRAPHEME_BOUNDARY" : !supported || overlapping.isEmpty() ? "OOXML_TEXT_STRUCTURE"
                    : overlapping.stream().anyMatch(span -> !sameStyle(overlapping.getFirst(), span)) ? "MIXED_RUN_STYLE" : "";
            if (!feature.isEmpty()) {
                var treatment = state.issue(feature, part, paragraphIndex, start, end, feature.equals("MIXED_RUN_STYLE"));
                if (treatment == ProcessingPolicy.Treatment.SKIP_BLOCK || treatment == ProcessingPolicy.Treatment.PRESERVE) {
                    var issue = state.issues.getLast(); state.issues.subList(issuesBefore, state.issues.size()).clear(); state.issues.add(issue); return false;
                }
                if (treatment == ProcessingPolicy.Treatment.SKIP_MATCH) { continue; }
            }
            matches.add(new Match(start, end, overlapping));
        }
        long resulting = source.length() + (long) matches.size() * (operation.value().length() - operation.needle().length());
        long total = state.text + resulting - source.length();
        if (resulting > state.limits.maxTextUtf16() || total > state.limits.maxTextUtf16()) { throw new IllegalArgumentException("DOCX replacement text budget exceeded"); }
        state.text = total;
        state.work((long) matches.size() * operation.value().length());
        for (int index = matches.size() - 1; index >= 0; index--) {
            var match = matches.get(index);
            for (int item = 0; item < match.spans().size(); item++) {
                var span = match.spans().get(item); String current = span.text().getTextContent();
                int left = Math.max(0, match.start() - span.start()); int right = Math.min(span.end(), match.end()) - span.start();
                span.text().setTextContent(current.substring(0, left) + (item == 0 ? operation.value() : "") + current.substring(right));
                span.text().setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            }
        }
        for (var match : matches) {
            if (state.changes.size() >= state.limits.maxReplacements()) { throw new IllegalArgumentException("DOCX change budget exceeded"); }
            state.changes.add(new Change(part, paragraphIndex, match.start(), match.end()));
        }
        return !matches.isEmpty();
    }
    private static boolean ordinaryAncestors(Element paragraph) {
        for (Node node = paragraph.getParentNode(); node instanceof Element parent; node = parent.getParentNode()) {
            if (!W.equals(parent.getNamespaceURI()) || !Set.of("document", "body", "hdr", "ftr", "tbl", "tr", "tc").contains(parent.getLocalName())) { return false; }
        }
        return true;
    }
    /** -1 means the paragraph contains a protected/unknown boundary, even if some text is ordinary. */
    private static int spans(Element parent, Element hyperlink, List<Span> result, int offset) {
        for (var node : DocxAdapter.children(parent)) {
            if (word(node, "pPr")) { continue; }
            if (word(node, "hyperlink") && hyperlink == null) {
                offset = spans(node, node, result, offset); if (offset < 0) { return -1; } continue;
            }
            if (!word(node, "r")) { return -1; }
            for (var content : DocxAdapter.children(node)) {
                if (word(content, "rPr")) { continue; }
                if (!word(content, "t")) { return -1; }
                int end = offset + content.getTextContent().length(); result.add(new Span(content, node, hyperlink, offset, end)); offset = end;
            }
        }
        return offset;
    }
    private static String paragraphText(Element paragraph) {
        var result = new StringBuilder(); collectText(paragraph, result); return result.toString();
    }
    private static void collectText(Element parent, StringBuilder target) {
        for (var child : DocxAdapter.children(parent)) {
            if (word(child, "p")) { continue; }
            if (word(child, "t") || word(child, "delText")) { target.append(child.getTextContent()); }
            else if (word(child, "tab")) { target.append('\t'); }
            else if (word(child, "br") || word(child, "cr")) { target.append('\n'); }
            else if (!word(child, "pPr") && !word(child, "rPr")) { collectText(child, target); }
        }
    }
    private static boolean sameStyle(Span first, Span second) {
        return equal(DocxAdapter.child(first.run(), "rPr"), DocxAdapter.child(second.run(), "rPr")) && equalHyperlink(first.hyperlink(), second.hyperlink());
    }
    private static boolean equal(Element a, Element b) { return a == null ? b == null : b != null && a.isEqualNode(b); }
    private static boolean equalHyperlink(Element a, Element b) {
        if (a == null || b == null) { return a == b; }
        var left = (Element) a.cloneNode(false); var right = (Element) b.cloneNode(false); return left.isEqualNode(right);
    }
    private static boolean word(Element value, String name) { return W.equals(value.getNamespaceURI()) && name.equals(value.getLocalName()); }
    private static boolean splitsSurrogate(String value, int index) { return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1)) && Character.isLowSurrogate(value.charAt(index)); }
    private static void text(String value) {
        Objects.requireNonNull(value, "text");
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            if (cp >= 0xD800 && cp <= 0xDFFF || cp < 0x20 || cp == 0xFFFE || cp == 0xFFFF || cp == 0xFFFC) {
                throw new IllegalArgumentException("replacement requires ordinary valid XML text");
            }
        }
    }

    private static Map<String, Element> validate(Map<String, byte[]> parts, State state) throws IOException {
        if (!parts.containsKey("[Content_Types].xml") || !parts.containsKey("word/document.xml")) { throw new IOException("DOCX required parts missing"); }
        var roots = new LinkedHashMap<String, Element>();
        for (var entry : parts.entrySet()) {
            String name = entry.getKey(); String lower = name.toLowerCase(java.util.Locale.ROOT); state.work(entry.getValue().length);
            if (lower.contains("vbaproject") || lower.contains("activex") || lower.startsWith("_xmlsignatures/") || lower.startsWith("word/embeddings/")) {
                throw new IOException("active or signed DOCX package editing unsupported");
            }
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                var root = DocxAdapter.parse(entry.getValue()); roots.put(name, root); validateTree(root, state);
                var fields = new ArrayList<Field>(); complexFields(root, fields, null, state);
                if (!fields.isEmpty()) { throw new IOException("unbalanced DOCX field structure"); }
                if (name.equals("[Content_Types].xml")) {
                    for (var type : DocxAdapter.children(root)) {
                        String mime = type.getAttribute("ContentType").toLowerCase(java.util.Locale.ROOT);
                        if (mime.contains("macro") || mime.contains("vba") || mime.contains("activex") || mime.contains("oleobject") || mime.contains("signature")) {
                            throw new IOException("active or signed DOCX content type unsupported");
                        }
                    }
                }
                if (name.endsWith(".rels")) { relationships(name, root, parts); }
            }
        }
        return roots;
    }
    private static void validateTree(Element element, State state) throws IOException {
        state.work(1); if (++state.nodes > state.limits.maxNodes()) { throw new IllegalArgumentException("DOCX XML node budget exceeded"); }
        if (W.equals(element.getNamespaceURI())) {
            String name = element.getLocalName();
            if (Set.of("documentProtection", "writeProtection").contains(name)) { throw new IOException("protected DOCX package editing unsupported"); }
            if (Set.of("altChunk", "object", "control").contains(name)) { throw new IOException("active DOCX content unsupported"); }
            if (Set.of("t", "delText", "instrText").contains(name)) {
                state.text += element.getTextContent().length();
                if (state.text > state.limits.maxTextUtf16()) { throw new IllegalArgumentException("DOCX source text budget exceeded"); }
            }
            if (name.equals("fldSimple")) { field(element.getAttributeNS(W, "instr")); }
        }
        for (var child : DocxAdapter.children(element)) { validateTree(child, state); }
    }
    private static final Set<String> PASSIVE_FIELDS = Set.of("PAGE", "NUMPAGES", "SECTION", "SECTIONPAGES", "PAGEREF", "REF", "NOTEREF", "TOC", "TOA", "TC", "XE", "INDEX", "EQ", "SYMBOL", "SEQ", "IF", "COMPARE", "QUOTE", "SET", "ASK", "FILLIN", "MERGEFIELD", "MERGEREC", "MERGESEQ", "FORMTEXT", "FORMCHECKBOX", "FORMDROPDOWN", "DATE", "TIME", "CREATEDATE", "SAVEDATE", "PRINTDATE", "AUTHOR", "TITLE", "SUBJECT", "KEYWORDS", "COMMENTS", "DOCPROPERTY", "DOCVARIABLE", "STYLEREF", "LISTNUM", "AUTONUM", "AUTONUMLGL", "AUTONUMOUT", "NUMWORDS", "NUMCHARS");
    private static void field(String instruction) throws IOException {
        String command = instruction.strip().split("\\s+", 2)[0].toUpperCase(java.util.Locale.ROOT);
        if (!PASSIVE_FIELDS.contains(command)) { throw new IOException("active or unknown DOCX field instruction unsupported"); }
    }
    private static final class Field { final StringBuilder code = new StringBuilder(); boolean result; }
    private static void complexFields(Element parent, List<Field> stack, Element paragraph, State state) throws IOException {
        if (word(parent, "p")) { paragraph = parent; }
        if (paragraph != null && !stack.isEmpty()) { state.fieldParagraphs.add(paragraph); }
        for (var node : DocxAdapter.children(parent)) {
            state.work(1);
            if (word(node, "fldChar")) {
                if (paragraph != null) { state.fieldParagraphs.add(paragraph); }
                String type = node.getAttributeNS(W, "fldCharType");
                if (type.equals("begin")) { stack.add(new Field()); }
                else if ((type.equals("separate") || type.equals("end")) && !stack.isEmpty()) {
                    var current = stack.getLast();
                    if (type.equals("separate") && current.result) { throw new IOException("duplicate DOCX field separator"); }
                    if (!current.result) { field(current.code.toString()); current.result = true; }
                    if (type.equals("end")) { stack.removeLast(); }
                } else { throw new IOException("invalid DOCX field boundary"); }
            } else if (word(node, "instrText")) {
                if (stack.isEmpty()) { throw new IOException("DOCX field instruction lacks begin marker"); }
                if (stack.getLast().result) { throw new IOException("DOCX field instruction occurs after result separator"); }
                stack.getLast().code.append(node.getTextContent());
            } else { complexFields(node, stack, paragraph, state); }
        }
    }
    private static void relationships(String name, Element root, Map<String, byte[]> parts) throws IOException {
        if (!REL.equals(root.getNamespaceURI()) || !root.getLocalName().equals("Relationships")) { throw new IOException("invalid DOCX relationships root"); }
        var ids = new HashSet<String>();
        String owner = name.equals("_rels/.rels") ? "" : name.replace("/_rels/", "/").substring(0, name.replace("/_rels/", "/").length() - 5);
        String directory = owner.contains("/") ? owner.substring(0, owner.lastIndexOf('/') + 1) : "";
        for (var relation : DocxAdapter.children(root)) {
            if (!REL.equals(relation.getNamespaceURI()) || !relation.getLocalName().equals("Relationship") || relation.getAttribute("Id").isBlank() || !ids.add(relation.getAttribute("Id"))) { throw new IOException("invalid DOCX relationship"); }
            String type = relation.getAttribute("Type").toLowerCase(java.util.Locale.ROOT);
            if (List.of("/vbaproject", "/oleobject", "/control", "/afchunk", "/attachedtemplate", "/package").stream().anyMatch(type::endsWith)
                    || type.contains("/digital-signature/")) {
                throw new IOException("active or signed DOCX relationship unsupported");
            }
            String target = relation.getAttribute("Target"); String mode = relation.getAttribute("TargetMode");
            if (mode.equals("External")) {
                if (!type.endsWith("/hyperlink")) { throw new IOException("external DOCX resources unsupported"); }
                new ExternalDestination(target); continue;
            }
            if (!mode.isEmpty() && !mode.equals("Internal")) { throw new IOException("unknown DOCX relationship mode"); }
            try {
                var uri = URI.create(target);
                if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || target.contains("\\")) { throw new IOException("invalid DOCX internal relationship"); }
                var resolved = URI.create("/" + directory).resolve(uri).normalize().getPath();
                if (resolved == null || resolved.contains("/../") || !parts.containsKey(resolved.substring(1))) { throw new IOException("missing DOCX relationship target"); }
            } catch (IllegalArgumentException exception) { throw new IOException("invalid DOCX relationship target", exception); }
        }
    }
    private static byte[] encode(Element root, int limit) throws IOException {
        try {
            var factory = TransformerFactory.newInstance(); factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer(); transformer.setOutputProperty("encoding", "UTF-8");
            var bytes = new ByteArrayOutputStream(); transformer.transform(new DOMSource(root.getOwnerDocument()), new StreamResult(bounded(bytes, limit)));
            return bytes.toByteArray();
        } catch (TransformerException exception) { throw new IOException("edited DOCX XML encoding failed", exception); }
    }
    private static byte[] zip(Map<String, byte[]> parts, int limit) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bounded(bytes, limit))) {
            for (var entry : parts.entrySet()) { ExecutionContext.checkpoint(); zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static OutputStream bounded(ByteArrayOutputStream bytes, int limit) {
        return new OutputStream() {
            private void reserve(int count) { ExecutionContext.checkpoint(); if (count > limit - bytes.size()) { throw new IllegalArgumentException("DOCX encoding byte budget exceeded"); } }
            @Override public void write(int value) { reserve(1); bytes.write(value); }
            @Override public void write(byte[] value, int start, int count) { Objects.checkFromIndexSize(start, count, value.length); reserve(count); bytes.write(value, start, count); }
        };
    }
}
