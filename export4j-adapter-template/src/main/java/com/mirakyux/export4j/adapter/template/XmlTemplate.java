// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExternalDestination;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageOverlay;
import com.mirakyux.export4j.model.PageRegionStyle;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.adapter.template.TemplateException.Code;

/** Immutable offline XML template compiler. Each compile owns its parser and state; instances may be shared.
 * Compiles the documented version-1 subset, with no scripts, namespaces, DTDs or implicit I/O.
 * Bounded recursive includes use registered source snapshots, composed unique prefixes and no resource loading.
 * Lengths use points; page settings additionally accept explicit pt/mm/cm/in suffixes.
 * Images are immutable resources supplied explicitly by the caller.
 * Cells contain either inline content or ordered Paragraph/Table blocks, with at most 16 table levels.
 * Block cells may explicitly continue by complete lines; mixed content, nested dynamic syntax and block headers are rejected.
 * Themes resolve complete defaults and static p/span style names during compilation, before data binding.
 * One optional first, empty page element overrides caller page settings with static point/pt/mm/cm/in lengths.
 * It is restricted to the main source; fragments, branches and repeated content cannot declare page settings.
 * Optional main-source header/footer declarations follow page and precede body content. They contain fixed
 * paragraphs/tables with explicit images and PAGE/PAGES fields; paragraphs use independent region defaults.
 * Static root sections inherit root settings/regions, start on new pages and may override/clear bands.
 * Use DocumentTemplate.renderSections for section declarations; body-only render never drops them.
 * Templates with bands require DocumentTemplate.renderWithPageRegions(); render() refuses to discard them.
 * Numbered levels are static integers from 1 through 9, defaulting to 1; all branches are checked.
 * Parent-level completeness is validated after binding by component layout or DOCX export, not by this compiler.
 * Compilation throws {@link TemplateException}; rendering uses {@link DocumentTemplate.BindingException}.
 * Both operations observe the caller's synchronous ExecutionContext. No font or backend is retained.
 */
public final class XmlTemplate {
    /** Maximum active include chain; wrappers do not contribute to final XML tree depth. */
    public static final int MAX_INCLUDE_DEPTH = 16;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,255}");
    private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z_0-9-]{0,127}");
    private static final Pattern FRAGMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z_0-9-]{0,63}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*");
    private static final Pattern LENGTH = Pattern.compile("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(pt|mm|cm|in)?");
    private static final String LINK_REFERENCE_PREFIX = "https://export4j.invalid/.template-link/";
    private static final Set<String> BLOCK_ELEMENTS = Set.of("p", "heading", "bullet", "numbered", "page-break", "table", "rows");
    private static final Set<String> PARAGRAPH_ATTRIBUTES = Set.of("id", "size", "line-height", "align",
            "keep-together", "space-before", "space-after");
    private static final Set<String> INLINE_STYLE_ATTRIBUTES = Set.of("role", "color", "size", "underline", "strike");
    private static final Set<String> SPAN_ATTRIBUTES = Set.of("style", "role", "color", "size", "underline", "strike");
    private final PageSettings pageSettings;
    private final DocumentTheme theme;
    private final ParagraphFormat bodyFormat;
    private final CellFormat cellFormat;
    private final TextDefaults bodyText;
    private final TextDefaults cellText;
    private final Map<Integer, TextDefaults> headingText;
    private final Map<String, ImageResource> images;
    private final Map<String, String> fragments;
    private final long fragmentInputCharacters;
    private final Limits limits;
    private final DocumentTemplate.Limits bindingLimits;

    private XmlTemplate(Builder builder) {
        pageSettings = builder.pageSettings;
        theme = builder.theme;
        bodyFormat = builder.bodyFormat;
        cellFormat = builder.cellFormat;
        bodyText = builder.bodyText == null ? defaults(theme.bodyText()) : builder.bodyText;
        cellText = builder.cellText == null ? defaults(theme.cellText()) : builder.cellText;
        var headings = new HashMap<Integer, TextDefaults>();
        for (int level = 1; level <= 6; level++) {
            headings.put(level, builder.headingText.getOrDefault(level, defaults(theme.headingText(level))));
        }
        headingText = Map.copyOf(headings);
        images = Map.copyOf(builder.images);
        fragments = Map.copyOf(builder.fragments);
        long inputCharacters = 0;
        for (var source : fragments.values()) { inputCharacters += source.length(); }
        fragmentInputCharacters = inputCharacters;
        limits = builder.limits;
        bindingLimits = builder.bindingLimits;
    }

    private static TextDefaults defaults(com.mirakyux.export4j.model.TextAppearance value) {
        return new TextDefaults(value.fontRole(), value.rgb());
    }

    /** Independent source and expansion budgets. Input counts main and all registered sources in UTF-16 units.
     * Parsing accumulates elements and decoded text code points across main and each referenced source once;
     * expansion independently applies the same element/text limits to every occurrence, including include nodes.
     * Text includes structural whitespace. Roots count as elements; final tree root depth is 1.
     * Fragment wrappers disappear on expansion, and attributes are input-bounded.
     */
    public record Limits(int maxInputCharacters, int maxElements, int maxDepth, int maxTextCodePoints) {
        public static final Limits DEFAULT = new Limits(1_000_000, 20_000, 32, 1_000_000);
        public Limits {
            if (maxInputCharacters < 1 || maxElements < 1 || maxDepth < 1 || maxDepth > 64 || maxTextCodePoints < 1) {
                throw new IllegalArgumentException("invalid XML template limits");
            }
        }
    }

    /** Complete default font-role name and 24-bit RGB, independent of paragraph/cell geometry.
     * Empty role selects the renderer's explicit default font group. Other names use the same static
     * identifier grammar as XML role attributes; no font resource or I/O is retained. Immutable and
     * thread-safe. Null/invalid names and RGB outside 0x000000..0xFFFFFF are rejected.
     */
    public record TextDefaults(String fontRole, int rgb) {
        public static final TextDefaults DEFAULT = new TextDefaults("", 0);
        public TextDefaults {
            Objects.requireNonNull(fontRole, "fontRole");
            if (!fontRole.isEmpty() && !IDENTIFIER.matcher(fontRole).matches() || rgb < 0 || rgb > 0xffffff) {
                throw new IllegalArgumentException("invalid XML text defaults");
            }
        }
        public static Builder builder() { return new Builder(); }
        public Builder toBuilder() { return new Builder().fontRole(fontRole).rgb(rgb); }

        /** Mutable, task-confined configuration; build takes an immutable value snapshot. */
        public static final class Builder {
            private String fontRole = "";
            private int rgb;
            private Builder() { }
            public Builder fontRole(String value) { new TextDefaults(value, rgb); fontRole = value; return this; }
            public Builder rgb(int value) { new TextDefaults(fontRole, value); rgb = value; return this; }
            public TextDefaults build() { return new TextDefaults(fontRole, rgb); }
        }
    }

    /** Compiles a complete source snapshot. Unknown syntax and all errors fail before returning a template. */
    public DocumentTemplate compile(String source) {
        Objects.requireNonNull(source, "source");
        ExecutionContext.checkpoint();
        if (source.length() + fragmentInputCharacters > limits.maxInputCharacters()) {
            throw failure(Code.BUDGET_EXCEEDED, 1, 1);
        }
        return new Compiler().compile(new Expansion().expand(source));
    }

    /** Reads at most the UTF-16 input allowance remaining after all registered sources, plus one unit;
     * the borrowed reader is never closed. Registration alone exceeding the allowance fails before reading.
     * Reader I/O errors propagate unchanged. Caller-supplied readers are trusted code and must cooperate with cancellation.
     */
    public DocumentTemplate compile(Reader source) throws IOException {
        Objects.requireNonNull(source, "source");
        ExecutionContext.checkpoint();
        long remaining = limits.maxInputCharacters() - fragmentInputCharacters;
        if (remaining < 0) { throw failure(Code.BUDGET_EXCEEDED, 1, 1); }
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        while (true) {
            ExecutionContext.checkpoint();
            int wanted = (int) Math.min(buffer.length, remaining - text.length() + 1);
            int count = source.read(buffer, 0, wanted);
            if (count < 0) { break; }
            if (count == 0) {
                int value = source.read();
                if (value < 0) { break; }
                if (text.length() == remaining) { throw failure(Code.BUDGET_EXCEEDED, 1, 1); }
                text.append((char) value);
            } else {
                if (count > remaining - text.length()) { throw failure(Code.BUDGET_EXCEEDED, 1, 1); }
                text.append(buffer, 0, count);
            }
        }
        return compile(text.toString());
    }

    private sealed interface Part permits Element, Text { }
    private record Text(String value) implements Part { }
    private record Element(String name, Map<String, String> attributes, List<Part> content,
            int line, int column, String fragmentName, String namespace) implements Part { }
    private record Fragment(Element root, Set<String> ids) { }
    private record ParsedTable(String id, List<ColumnWidth> columns, List<TableRow> headers,
            List<TableRow> rows, TableFormat format) {
        private ParsedTable {
            columns = List.copyOf(columns); headers = List.copyOf(headers); rows = List.copyOf(rows);
        }
        private Table value() {
            var all = new ArrayList<>(headers); all.addAll(rows);
            return new Table(id, columns, all, headers.size(), format);
        }
    }
    private static final class Draft {
        private final String name;
        private final Map<String, String> attributes;
        private final List<Part> content = new ArrayList<>();
        private final int line;
        private final int column;
        private final String fragmentName;
        private Draft(String name, Map<String, String> attributes, int line, int column, String fragmentName) {
            this.name = name; this.attributes = attributes; this.line = line; this.column = column;
            this.fragmentName = fragmentName;
        }
        private Element finish() {
            return new Element(name, Map.copyOf(attributes), List.copyOf(content), line, column, fragmentName, "");
        }
    }

    private static final class SourceBudget {
        private long elements;
        private long text;
    }

    private Element read(String source, String fragmentName, SourceBudget budget, Element include) {
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("external XML resources are forbidden");
        });
        javax.xml.stream.XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(source));
            var stack = new ArrayDeque<Draft>();
            Element root = null;
            while (reader.hasNext()) {
                ExecutionContext.checkpoint();
                int event = reader.next();
                var location = reader.getLocation();
                int line = location.getLineNumber(), column = location.getColumnNumber();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        int depth = stack.size() + 1;
                        // Main non-include nodes survive expansion: preserve their first located depth failure.
                        // An empty include can disappear; all input trees still have an independent hard cap.
                        boolean mainDepthExceeded = fragmentName.isEmpty() && !reader.getLocalName().equals("include")
                                && depth > limits.maxDepth();
                        if (++budget.elements > limits.maxElements() || depth > 65 || mainDepthExceeded) {
                            throw sourceError(Code.BUDGET_EXCEEDED, line, column, fragmentName, include);
                        }
                        if (reader.getNamespaceCount() > 0 || reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty()) {
                            throw sourceError(Code.FORBIDDEN_XML, line, column, fragmentName, include);
                        }
                        Map<String, String> attributes = new HashMap<>();
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String namespace = reader.getAttributeNamespace(i);
                            if (namespace != null && !namespace.isEmpty()) {
                                throw sourceError(Code.FORBIDDEN_XML, line, column, fragmentName, include);
                            }
                            attributes.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        stack.push(new Draft(reader.getLocalName(), attributes, line, column, fragmentName));
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        var value = stack.pop().finish();
                        if (stack.isEmpty()) { root = value; } else { stack.peek().content.add(value); }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        String value = reader.getText();
                        budget.text += value.codePointCount(0, value.length());
                        if (budget.text > limits.maxTextCodePoints()) {
                            throw sourceError(Code.BUDGET_EXCEEDED, line, column, fragmentName, include);
                        }
                        if (!stack.isEmpty()) { stack.peek().content.add(new Text(value)); }
                    }
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.PROCESSING_INSTRUCTION ->
                        throw sourceError(Code.FORBIDDEN_XML, line, column, fragmentName, include);
                    default -> { /* Comments and document boundaries have no semantic content. */ }
                }
            }
            if (root == null) { throw sourceError(Code.INVALID_STRUCTURE, 1, 1, fragmentName, include); }
            return root;
        } catch (XMLStreamException invalid) {
            var location = invalid.getLocation();
            // Provider messages may contain document text or resource URLs. Expose only a stable, located rejection.
            throw sourceError(Code.MALFORMED_XML, location == null ? 1 : location.getLineNumber(),
                    location == null ? 1 : location.getColumnNumber(), fragmentName, include);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (XMLStreamException ignored) { /* Owns only an in-memory StringReader. */ }
            }
        }
    }

    /** Resolves only static main-source block lists, before IDs or binding nodes are allocated. */
    private final class Expansion {
        private final SourceBudget sourceBudget = new SourceBudget();
        private final Map<String, Fragment> parsed = new HashMap<>();
        private final Set<String> prefixes = new HashSet<>();
        private final ArrayDeque<String> loading = new ArrayDeque<>();
        private final ArrayDeque<String> expanding = new ArrayDeque<>();
        private long elements;
        private long text;

        private Element expand(String source) {
            var root = read(source, "", sourceBudget, null);
            requireName(root, "document");
            attributes(root, Set.of("version", "title", "author"));
            if (!required(root, "version").equals("1")) { throw error(root, Code.INVALID_VALUE); }
            pagePlacement(root, true);
            return element(root, 1, true, false, "", Set.of(), null);
        }

        private Element element(Element source, int depth, boolean blockList, boolean conditional,
                String namespace, Set<String> localIds, Element include) {
            countElement(source, depth, include);
            List<Part> content = new ArrayList<>();
            for (var part : source.content()) {
                ExecutionContext.checkpoint();
                if (part instanceof Text literal) {
                    text += literal.value().codePointCount(0, literal.value().length());
                    if (text > limits.maxTextCodePoints()) { throw expansionError(source, include); }
                    content.add(literal);
                } else {
                    var child = (Element) part;
                    if (child.name().equals("include")) {
                        if (!blockList) { throw error(child, Code.UNSUPPORTED_ELEMENT); }
                        content.addAll(include(child, depth + 1, namespace));
                    } else {
                        boolean childList = blockList && Set.of("each", "section", "header", "footer").contains(child.name())
                                || conditional && (child.name().equals("then") || child.name().equals("else"));
                        boolean childConditional = blockList && child.name().equals("if");
                        content.add(element(child, depth + 1, childList, childConditional, namespace, localIds, include));
                    }
                }
            }
            return new Element(source.name(), qualifiedAttributes(source, namespace, localIds), List.copyOf(content),
                    source.line(), source.column(), source.fragmentName(), namespace);
        }

        private List<Part> include(Element call, int depth, String parentNamespace) {
            // Charge the include's work, without adding its removed wrapper to final-tree depth.
            countElement(call, 1, null);
            attributes(call, Set.of("ref", "prefix"));
            empty(call);
            String name = fragmentName(call, "ref");
            String prefix = fragmentName(call, "prefix");
            if (!parentNamespace.isEmpty()) { prefix = qualifiedId(call, parentNamespace, prefix); }
            if (!prefixes.add(prefix)) { throw error(call, Code.INVALID_VALUE); }
            if (expanding.size() >= MAX_INCLUDE_DEPTH) { throw error(call, Code.BUDGET_EXCEEDED); }
            var fragment = fragment(name, call);
            expanding.push(name);
            try {
                List<Part> result = new ArrayList<>();
                for (var part : fragment.root().content()) {
                    ExecutionContext.checkpoint();
                    if (part instanceof Text literal) {
                        if (!literal.value().isBlank()) { throw error(fragment.root(), Code.INVALID_STRUCTURE); }
                        text += literal.value().codePointCount(0, literal.value().length());
                        if (text > limits.maxTextCodePoints()) { throw error(call, Code.BUDGET_EXCEEDED); }
                        result.add(literal);
                    } else {
                        var child = (Element) part;
                        if (child.name().equals("include")) { result.addAll(include(child, depth, prefix)); }
                        else { result.add(element(child, depth, child.name().equals("each"), child.name().equals("if"),
                                prefix, fragment.ids(), call)); }
                    }
                }
                return result;
            } finally { expanding.pop(); }
        }

        private Fragment fragment(String name, Element call) {
            var fragment = parsed.get(name);
            if (fragment != null) { return fragment; }
            if (loading.contains(name)) { throw error(call, Code.INVALID_STRUCTURE); }
            if (loading.size() >= MAX_INCLUDE_DEPTH) { throw error(call, Code.BUDGET_EXCEEDED); }
            loading.push(name);
            try {
                String source = fragments.get(name);
                if (source == null) { throw error(call, Code.UNKNOWN_RESOURCE); }
                var root = read(source, name, sourceBudget, call);
                requireName(root, "fragment");
                attributes(root, Set.of("version"));
                if (!required(root, "version").equals("1")) { throw error(root, Code.INVALID_VALUE); }
                pagePlacement(root, false);
                fragment = new Fragment(root, fragmentIds(root));
                parsed.put(name, fragment);
                return fragment;
            } finally { loading.pop(); }
        }

        private record IdScan(Element element, boolean blockList, boolean conditional) { }

        private Set<String> fragmentIds(Element root) {
            Set<String> result = new HashSet<>();
            var pending = new ArrayDeque<IdScan>();
            pending.push(new IdScan(root, true, false));
            while (!pending.isEmpty()) {
                ExecutionContext.checkpoint();
                var scan = pending.pop();
                var current = scan.element();
                if (current.name().equals("include")) {
                    attributes(current, Set.of("ref", "prefix")); empty(current);
                    String prefix = fragmentName(current, "prefix");
                    var child = fragment(fragmentName(current, "ref"), current);
                    for (String id : child.ids()) {
                        ExecutionContext.checkpoint();
                        if (!result.add(qualifiedId(current, prefix, id))) { throw error(current, Code.INVALID_VALUE); }
                        if (result.size() > limits.maxElements()) { throw error(current, Code.BUDGET_EXCEEDED); }
                    }
                    continue;
                }
                if (BLOCK_ELEMENTS.contains(current.name()) && current.attributes().containsKey("id")) {
                    String id = current.attributes().get("id");
                    if (!ID.matcher(id).matches() || !result.add(id)) { throw error(current, Code.INVALID_VALUE); }
                    if (result.size() > limits.maxElements()) { throw error(current, Code.BUDGET_EXCEEDED); }
                }
                if (current.name().equals("numbered") && current.attributes().containsKey("list-id")) {
                    literalId(current, "list-id");
                }
                for (int i = current.content().size() - 1; i >= 0; i--) {
                    if (current.content().get(i) instanceof Element child) {
                        if (child.name().equals("include") && !scan.blockList()) { throw error(child, Code.UNSUPPORTED_ELEMENT); }
                        boolean childList = scan.blockList() && Set.of("each", "section", "header", "footer").contains(child.name())
                                || scan.conditional() && (child.name().equals("then") || child.name().equals("else"));
                        pending.push(new IdScan(child, childList, scan.blockList() && child.name().equals("if")));
                    }
                }
            }
            return Set.copyOf(result);
        }

        private Map<String, String> qualifiedAttributes(Element source, String namespace, Set<String> localIds) {
            if (namespace.isEmpty()) { return source.attributes(); }
            var attributes = new HashMap<>(source.attributes());
            if (BLOCK_ELEMENTS.contains(source.name()) && attributes.containsKey("id")) {
                attributes.put("id", qualifiedId(source, namespace, attributes.get("id")));
            }
            if (source.name().equals("numbered") && attributes.containsKey("list-id")) {
                attributes.put("list-id", qualifiedId(source, namespace, attributes.get("list-id")));
            }
            if ((source.name().equals("page-reference") || source.name().equals("number-reference"))
                    && attributes.containsKey("target") && localIds.contains(attributes.get("target"))) {
                attributes.put("target", qualifiedId(source, namespace, attributes.get("target")));
            }
            return Map.copyOf(attributes);
        }

        private void countElement(Element source, int depth, Element include) {
            ExecutionContext.checkpoint();
            if (++elements > limits.maxElements() || depth > limits.maxDepth()) {
                throw expansionError(source, include);
            }
        }

        private TemplateException expansionError(Element source, Element include) {
            return error(include == null ? source : include, Code.BUDGET_EXCEEDED);
        }
    }

    private static String qualifiedId(Element source, String namespace, String id) {
        String qualified = namespace + "-" + id;
        if (!ID.matcher(qualified).matches()) { throw error(source, Code.INVALID_VALUE); }
        return qualified;
    }

    private static String fragmentName(Element element, String attribute) {
        String name = required(element, attribute);
        if (!FRAGMENT_NAME.matcher(name).matches()) { throw error(element, Code.INVALID_VALUE); }
        return name;
    }

    private static TemplateException sourceError(Code code, int line, int column, String fragmentName, Element include) {
        if (code == Code.BUDGET_EXCEEDED && include != null) { return error(include, code); }
        return new TemplateException(code, line, column, fragmentName);
    }

    private final class Compiler {
        private final Map<String, String> hyperlinkTemplates = new HashMap<>();
        private final Set<String> ids = new HashSet<>();
        private final Set<String> explicitIds = new HashSet<>();
        private final Map<String, String> listOwners = new HashMap<>();
        private int nextId;

        private DocumentTemplate compile(Element root) {
            requireName(root, "document");
            attributes(root, Set.of("version", "title", "author"));
            if (!required(root, "version").equals("1")) { throw error(root, Code.INVALID_VALUE); }
            reserveIds(root);
            var metadata = new DocumentMetadata(expression(root, value(root, "title", "")), expression(root, value(root, "author", "")));
            var content = children(root);
            var settings = pageSettings;
            if (!content.isEmpty() && content.getFirst().name().equals("page")) {
                settings = page(content.getFirst());
                content = content.subList(1, content.size());
            }
            var prefix = regionPrefix(root, content, 0, PageRegions.EMPTY, settings);
            var regions = prefix.regions();
            var body = content.subList(prefix.end(), content.size());
            if (body.stream().anyMatch(element -> element.name().equals("section"))) {
                var sections = new ArrayList<DocumentTemplate.SectionNode>();
                var sectionIds = new HashSet<String>();
                for (var element : body) {
                    requireName(element, "section");
                    if (!element.fragmentName().isEmpty()) { throw error(element, Code.UNSUPPORTED_ELEMENT); }
                    attributes(element, Set.of("id", "first-page-number"));
                    String sectionId = literalId(element, "id");
                    if (!sectionIds.add(sectionId)) { throw error(element, Code.INVALID_VALUE); }
                    int first = element.attributes().containsKey("first-page-number") ? integer(element, "first-page-number") : 0;
                    if (first < 0) { throw error(element, Code.INVALID_VALUE); }
                    var children = children(element);
                    var sectionSettings = settings;
                    int index = 0;
                    if (!children.isEmpty() && children.getFirst().name().equals("page")) {
                        sectionSettings = page(children.getFirst(), settings); index++;
                    }
                    var selected = regionPrefix(element, children, index, regions, sectionSettings);
                    sections.add(new DocumentTemplate.SectionNode(sectionId, sectionSettings,
                            nodes(children.subList(selected.end(), children.size())), first, selected.regions()));
                }
                return new DocumentTemplate(metadata, settings, List.of(), bindingLimits, regions, sections, hyperlinkTemplates);
            }
            return new DocumentTemplate(metadata, settings, nodes(body), bindingLimits, regions, List.of(), hyperlinkTemplates);
        }

        private record RegionPrefix(PageRegions regions, int end) { }

        private RegionPrefix regionPrefix(Element owner, List<Element> elements, int start, PageRegions inherited, PageSettings settings) {
            var result = inherited.toBuilder();
            var declared = new HashSet<String>();
            int index = start;
            while (index < elements.size()) {
                var element = elements.get(index);
                if (element.name().equals("background") || element.name().equals("foreground")) {
                    if (!declared.add(element.name())) { throw error(element, Code.INVALID_STRUCTURE); }
                    var overlays = overlays(element, settings);
                    if (element.name().equals("background")) { result.background(overlays); } else { result.foreground(overlays); }
                    index++; continue;
                }
                if (!element.name().equals("header") && !element.name().equals("footer")) { break; }
                String selected = value(element, "variant", "default");
                if (!Set.of("default", "first", "even").contains(selected)) { throw error(element, Code.INVALID_VALUE); }
                if (!declared.add(element.name() + ":" + selected)) { throw error(element, Code.INVALID_STRUCTURE); }
                boolean header = element.name().equals("header");
                var band = region(element, header ? theme.headerStyle() : theme.footerStyle());
                if (selected.equals("default")) {
                    if (header) { result.header(band); } else { result.footer(band); }
                } else {
                    var variant = selected.equals("first") ? PageRegions.Variant.FIRST : PageRegions.Variant.EVEN;
                    if (header) { result.header(variant, band); } else { result.footer(variant, band); }
                }
                index++;
            }
            var selected = validated(owner, result::build);
            for (var box : selected.background()) { validated(owner, () -> { box.requireContained(settings.size()); return box; }); }
            for (var box : selected.foreground()) { validated(owner, () -> { box.requireContained(settings.size()); return box; }); }
            return new RegionPrefix(selected, index);
        }

        private List<PageOverlay> overlays(Element element, PageSettings settings) {
            if (!element.fragmentName().isEmpty()) { throw error(element, Code.UNSUPPORTED_ELEMENT); }
            attributes(element, Set.of());
            var result = new ArrayList<PageOverlay>();
            var defaults = element.name().equals("background") ? theme.backgroundStyle() : theme.foregroundStyle();
            for (var box : children(element)) {
                requireName(box, "box");
                if (!box.fragmentName().isEmpty()) { throw error(box, Code.UNSUPPORTED_ELEMENT); }
                attributes(box, Set.of("x", "y", "width", "height"));
                for (var key : List.of("x", "y", "width", "height")) {
                    if (required(box, key).contains("${")) { throw error(box, Code.INVALID_EXPRESSION); }
                }
                result.add(validated(box, () -> {
                    var value = new PageOverlay(length(box, "x", 0), length(box, "y", 0), length(box, "width", 0),
                            new PageRegion(length(box, "height", 0), 0, regionBlocks(box, defaults)));
                    value.requireContained(settings.size()); return value;
                }));
                if (result.size() > 64) { throw error(box, Code.BUDGET_EXCEEDED); }
            }
            return List.copyOf(result);
        }

        private PageRegion region(Element element, PageRegionStyle defaults) {
            if (!element.fragmentName().isEmpty()) { throw error(element, Code.UNSUPPORTED_ELEMENT); }
            attributes(element, Set.of("height", "gap", "variant"));
            required(element, "height");
            for (var value : element.attributes().values()) {
                if (value.contains("${")) { throw error(element, Code.INVALID_EXPRESSION); }
            }
            return validated(element, () -> new PageRegion(length(element, "height", 0), length(element, "gap", 0), regionBlocks(element, defaults)));
        }

        private List<Block> regionBlocks(Element element, PageRegionStyle defaults) {
            var blocks = new ArrayList<Block>();
            for (var child : children(element)) {
                if (child.name().equals("p")) {
                    blocks.add(validated(child, () -> block(child, defaults.format(),
                            new TextDefaults(defaults.fontRole(), defaults.rgb()))));
                } else if (child.name().equals("table")) {
                    blocks.add(validated(child, Code.INVALID_STRUCTURE, () -> tableParts(child, 1).value()));
                } else { throw error(child, Code.UNSUPPORTED_ELEMENT); }
            }
            return List.copyOf(blocks);
        }

        private PageSettings page(Element element) { return page(element, pageSettings); }

        private PageSettings page(Element element, PageSettings inherited) {
            if (!element.fragmentName().isEmpty()) { throw error(element, Code.UNSUPPORTED_ELEMENT); }
            attributes(element, Set.of("size", "width", "height", "orientation", "margin",
                    "margin-top", "margin-right", "margin-bottom", "margin-left"));
            empty(element);
            for (var value : element.attributes().values()) {
                if (value.contains("${")) { throw error(element, Code.INVALID_EXPRESSION); }
            }
            boolean width = element.attributes().containsKey("width");
            boolean height = element.attributes().containsKey("height");
            if (width != height || element.attributes().containsKey("size") && width) {
                throw error(element, Code.INVALID_STRUCTURE);
            }
            return validated(element, () -> {
                var size = inherited.size();
                if (element.attributes().containsKey("size")) {
                    size = switch (element.attributes().get("size")) {
                        case "A4" -> PageSize.A4;
                        case "LETTER" -> PageSize.LETTER;
                        default -> throw error(element, Code.INVALID_VALUE);
                    };
                } else if (width) {
                    size = new PageSize(length(element, "width", size.width()), length(element, "height", size.height()));
                }
                if (element.attributes().containsKey("orientation")) {
                    double shorter = Math.min(size.width(), size.height());
                    double longer = Math.max(size.width(), size.height());
                    size = switch (element.attributes().get("orientation")) {
                        case "PORTRAIT" -> new PageSize(shorter, longer);
                        case "LANDSCAPE" -> new PageSize(longer, shorter);
                        default -> throw error(element, Code.INVALID_VALUE);
                    };
                }
                var margins = inherited.margins();
                if (element.attributes().containsKey("margin")) {
                    margins = PageMargins.uniform(length(element, "margin", 0));
                }
                return new PageSettings(size, new PageMargins(length(element, "margin-top", margins.top()),
                        length(element, "margin-right", margins.right()), length(element, "margin-bottom", margins.bottom()),
                        length(element, "margin-left", margins.left())));
            });
        }

        private void reserveIds(Element root) {
            var pending = new ArrayDeque<Element>();
            pending.push(root);
            while (!pending.isEmpty()) {
                ExecutionContext.checkpoint();
                var element = pending.pop();
                if (BLOCK_ELEMENTS.contains(element.name())
                        && element.attributes().containsKey("id")) {
                    String id = element.attributes().get("id");
                    if (!ID.matcher(id).matches() || !explicitIds.add(id)) { throw error(element, Code.INVALID_VALUE); }
                }
                if (element.name().equals("numbered") && element.attributes().containsKey("list-id")) {
                    String listId = element.attributes().get("list-id");
                    // Leave invalid/missing list IDs to the existing block validator.
                    if (ID.matcher(listId).matches()) {
                        String owner = listOwners.putIfAbsent(listId, element.namespace());
                        if (owner != null && !owner.equals(element.namespace())) { throw error(element, Code.INVALID_VALUE); }
                    }
                }
                for (int i = element.content().size() - 1; i >= 0; i--) {
                    if (element.content().get(i) instanceof Element child) { pending.push(child); }
                }
            }
        }

        private List<DocumentTemplate.Node> nodes(Element parent) {
            return nodes(children(parent));
        }

        private List<DocumentTemplate.Node> nodes(List<Element> elements) {
            List<DocumentTemplate.Node> result = new ArrayList<>();
            for (var element : elements) {
                ExecutionContext.checkpoint();
                result.add(node(element));
            }
            return List.copyOf(result);
        }

        private DocumentTemplate.Node node(Element element) {
            try {
                return switch (element.name()) {
                    case "p", "heading", "bullet", "numbered", "page-break" -> new DocumentTemplate.ValueBlock(block(element));
                    case "if" -> conditional(element);
                    case "each" -> {
                        attributes(element, Set.of("items", "as"));
                        yield new DocumentTemplate.Each(path(element, "items"), identifier(element, "as"), nodes(element));
                    }
                    case "table", "rows" -> table(element);
                    default -> throw error(element, Code.UNSUPPORTED_ELEMENT);
                };
            } catch (TemplateException failure) {
                throw failure;
            } catch (IllegalArgumentException invalid) {
                throw error(element, Code.INVALID_VALUE);
            }
        }

        private DocumentTemplate.When conditional(Element element) {
            attributes(element, Set.of("test"));
            String test = path(element, "test");
            var branches = children(element);
            if (branches.isEmpty() || branches.size() > 2) { throw error(element, Code.INVALID_STRUCTURE); }
            var yes = branches.getFirst();
            requireName(yes, "then"); attributes(yes, Set.of());
            var yesNodes = nodes(yes);
            List<DocumentTemplate.Node> no = List.of();
            if (branches.size() == 2) {
                var other = branches.getLast(); requireName(other, "else"); attributes(other, Set.of()); no = nodes(other);
            }
            return new DocumentTemplate.When(test, yesNodes, no);
        }

        private Block block(Element element) {
            return block(element, bodyFormat, bodyText);
        }

        private Block block(Element element, ParagraphFormat defaults, TextDefaults textDefaults) {
            var allowed = new HashSet<>(PARAGRAPH_ATTRIBUTES);
            allowed.addAll(Set.of("role", "color"));
            if (element.name().equals("p")) { allowed.add("style"); }
            if (element.name().equals("heading") || element.name().equals("numbered")) { allowed.add("level"); }
            if (element.name().equals("numbered")) { allowed.addAll(Set.of("list-id", "restart")); }
            attributes(element, element.name().equals("page-break") ? Set.of("id") : allowed);
            String id = id(element);
            if (element.name().equals("page-break")) { empty(element); return new PageBreak(id); }
            int level = element.name().equals("heading") ? integer(element, "level")
                    : element.name().equals("numbered") && element.attributes().containsKey("level") ? integer(element, "level") : 1;
            if (element.name().equals("numbered") && (level < 1 || level > 9)) { throw error(element, Code.INVALID_VALUE); }
            var base = element.name().equals("heading") ? theme.headingFormat(level) : defaults;
            if (element.name().equals("p") && element.attributes().containsKey("style")) {
                base = validated(element, () -> theme.paragraphStyle(styleName(element)));
            }
            var format = paragraphFormat(element, base);
            var appearance = appearance(element, format.fontSize(), element.name().equals("heading")
                    ? headingText.getOrDefault(level, TextDefaults.DEFAULT) : textDefaults);
            var content = inline(element, appearance, element.name().equals("p"));
            if (element.name().equals("p")) { return Paragraph.mixed(id, content, format); }
            var runs = content.stream().map(item -> (InlineText) item).toList();
            if (element.name().equals("numbered")) {
                return NumberedItem.rich(id, literalId(element, "list-id"), runs,
                        element.attributes().containsKey("restart") ? integer(element, "restart") : 0, format, level, appearance);
            }
            return element.name().equals("heading") ? Heading.rich(id, runs, level, format)
                    : BulletItem.rich(id, runs, "•", 1, format, appearance);
        }

        private DocumentTemplate.Node table(Element element) {
            var parsed = tableParts(element, 1);
            return validated(element, Code.INVALID_STRUCTURE, () -> element.name().equals("rows")
                    ? new DocumentTemplate.Rows(parsed.id(), path(element, "items"), identifier(element, "as"),
                            parsed.columns(), parsed.headers(), parsed.rows().getFirst(), parsed.format())
                    : new DocumentTemplate.ValueBlock(parsed.value()));
        }

        private ParsedTable tableParts(Element element, int tableDepth) {
            if (tableDepth > com.mirakyux.export4j.model.SemanticTree.MAX_TABLE_DEPTH) { throw error(element, Code.INVALID_STRUCTURE); }
            boolean dynamic = element.name().equals("rows");
            attributes(element, dynamic ? Set.of("id", "row-split", "items", "as") : Set.of("id", "row-split"));
            String id = id(element);
            var children = children(element);
            if (children.isEmpty()) { throw error(element, Code.INVALID_STRUCTURE); }
            var columnList = children.getFirst(); requireName(columnList, "columns"); attributes(columnList, Set.of());
            List<ColumnWidth> columns = new ArrayList<>();
            for (var column : children(columnList)) {
                requireName(column, "column"); attributes(column, Set.of("weight", "width")); empty(column);
                if (column.attributes().size() != 1) { throw error(column, Code.INVALID_STRUCTURE); }
                columns.add(validated(column, () -> column.attributes().containsKey("width")
                        ? new ColumnWidth.Fixed(number(column, "width", 0)) : new ColumnWidth.Fraction(number(column, "weight", 0))));
            }
            if (columns.isEmpty()) { throw error(columnList, Code.INVALID_STRUCTURE); }
            List<TableRow> headers = new ArrayList<>(), rows = new ArrayList<>();
            for (int i = 1; i < children.size(); i++) {
                var row = children.get(i);
                if (row.name().equals("header") && rows.isEmpty()) { headers.add(row(row, columns.size(), tableDepth, true)); }
                else if (row.name().equals("row")) { rows.add(row(row, columns.size(), tableDepth, false)); }
                else { throw error(row, Code.INVALID_STRUCTURE); }
            }
            if (rows.isEmpty() || dynamic && rows.size() != 1) { throw error(element, Code.INVALID_STRUCTURE); }
            var defaults = theme.tableFormat();
            var format = defaults.toBuilder().allowRowSplit(flag(element, "row-split", defaults.allowRowSplit())).build();
            if (dynamic && rows.getFirst().cells().stream().anyMatch(cell -> cell.rowSpan() > 1)) {
                throw error(element, Code.INVALID_VALUE);
            }
            return new ParsedTable(id, columns, headers, rows, format);
        }

        private TableRow row(Element element, int count, int tableDepth, boolean header) {
            attributes(element, Set.of());
            List<TableCell> cells = new ArrayList<>();
            int covered = 0;
            for (var cell : children(element)) {
                requireName(cell, "cell"); attributes(cell, Set.of("size", "line-height", "align", "column-span", "row-span", "role", "color"));
                var value = validated(cell, () -> {
                    int span = cell.attributes().containsKey("column-span") ? positiveInteger(cell, "column-span") : 1;
                    int rowSpan = cell.attributes().containsKey("row-span") ? positiveInteger(cell, "row-span") : 1;
                    double size = number(cell, "size", cellFormat.fontSize());
                    double height = number(cell, "line-height", cell.attributes().containsKey("size") ? size * 1.5 : cellFormat.lineHeight());
                    var format = new CellFormat(size, height, alignment(cell, cellFormat.alignment()), cellFormat.lineBreakPolicy());
                    var builder = TableCell.builder().format(format).columnSpan(span).rowSpan(rowSpan);
                    var appearance = appearance(cell, size, cellText);
                    boolean blockContent = cell.content().stream().anyMatch(part -> part instanceof Element child
                            && (child.name().equals("p") || child.name().equals("table")));
                    if (!blockContent) { return builder.content(inline(cell, appearance, true)).build(); }
                    if (header) { throw error(cell, Code.INVALID_STRUCTURE); }
                    return builder.blocks(cellBlocks(cell, tableDepth, format,
                            new TextDefaults(appearance.fontRole(), appearance.rgb()))).build();
                });
                if (value.columnSpan() > count - covered) { throw error(element, Code.INVALID_STRUCTURE); }
                covered += value.columnSpan();
                cells.add(value);
            }
            return new TableRow(cells);
        }

        private List<Block> cellBlocks(Element cell, int tableDepth, CellFormat format, TextDefaults appearance) {
            var defaults = new ParagraphFormat(format.fontSize(), format.lineHeight(), 0, 0,
                    format.alignment(), 0, 0, false).withLineBreakPolicy(format.lineBreakPolicy());
            List<Block> blocks = new ArrayList<>();
            for (var child : children(cell)) {
                ExecutionContext.checkpoint();
                switch (child.name()) {
                    case "p" -> blocks.add(validated(child, () -> block(child, defaults, appearance)));
                    case "table" -> blocks.add(validated(child, Code.INVALID_STRUCTURE,
                            () -> tableParts(child, tableDepth + 1).value()));
                    default -> throw error(child, Code.UNSUPPORTED_ELEMENT);
                }
            }
            return List.copyOf(blocks);
        }

        private ParagraphFormat paragraphFormat(Element element, ParagraphFormat defaults) {
            double size = number(element, "size", defaults.fontSize());
            return defaults.toBuilder().fontSize(size)
                    .lineHeight(number(element, "line-height", element.attributes().containsKey("size") ? size * 1.5 : defaults.lineHeight()))
                    .alignment(alignment(element, defaults.alignment())).keepTogether(flag(element, "keep-together", defaults.keepTogether()))
                    .spaceBefore(number(element, "space-before", defaults.spaceBefore()))
                    .spaceAfter(number(element, "space-after", defaults.spaceAfter())).build();
        }

        private InlineStyle appearance(Element element, double size, TextDefaults defaults) {
            return inlineStyle(element, new InlineStyle(defaults.fontRole(), size, defaults.rgb(), 0, false, false));
        }

        private List<InlineContent> inline(Element parent, InlineStyle defaults, boolean allowObjects) {
            return inline(parent, defaults, allowObjects, "");
        }

        private List<InlineContent> inline(Element parent, InlineStyle defaults, boolean allowObjects, String hyperlink) {
            List<InlineContent> result = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (var part : parent.content()) {
                ExecutionContext.checkpoint();
                if (part instanceof Text literal) { text.append(literal.value()); continue; }
                flush(parent, text, result, defaults, hyperlink);
                var child = (Element) part;
                switch (child.name()) {
                    case "span" -> {
                        attributes(child, SPAN_ATTRIBUTES);
                        result.add(validated(child, () -> new InlineText(expression(child, plain(child)), spanStyle(child, defaults), hyperlink)));
                    }
                    case "image" -> {
                        if (!allowObjects) { throw error(child, Code.UNSUPPORTED_ELEMENT); }
                        attributes(child, Set.of("ref", "width", "height", "alt")); empty(child);
                        String key = identifier(child, "ref");
                        var resource = images.get(key);
                        if (resource == null) { throw error(child, Code.UNKNOWN_RESOURCE); }
                        result.add(validated(child, () -> new InlineImage(resource, number(child, "width", 0), number(child, "height", 0), 0,
                                expression(child, value(child, "alt", "")), hyperlink)));
                    }
                    case "link" -> {
                        if (!allowObjects || !hyperlink.isEmpty()) { throw error(child, Code.UNSUPPORTED_ELEMENT); }
                        attributes(child, Set.of("href"));
                        String destination = required(child, "href");
                        if (destination.contains("${")) {
                            String template = expression(child, destination);
                            destination = LINK_REFERENCE_PREFIX + hyperlinkTemplates.size();
                            hyperlinkTemplates.put(destination, template);
                        } else {
                            if (destination.startsWith(LINK_REFERENCE_PREFIX)) { throw error(child, Code.INVALID_VALUE); }
                            String literal = destination;
                            validated(child, () -> new ExternalDestination(literal));
                        }
                        if (child.content().isEmpty()) { throw error(child, Code.INVALID_STRUCTURE); }
                        result.addAll(inline(child, defaults, true, destination));
                    }
                    case "page-number", "page-count", "page-reference", "number-reference" -> {
                        if (!allowObjects || !hyperlink.isEmpty()) { throw error(child, Code.UNSUPPORTED_ELEMENT); }
                        result.add(field(child, defaults));
                    }
                    default -> throw error(child, Code.UNSUPPORTED_ELEMENT);
                }
            }
            flush(parent, text, result, defaults, hyperlink);
            if (result.isEmpty()) { result.add(new InlineText("", defaults, hyperlink)); }
            return List.copyOf(result);
        }

        private InlineField field(Element element, InlineStyle defaults) {
            var kind = switch (element.name()) {
                case "page-number" -> InlineField.Kind.PAGE;
                case "page-count" -> InlineField.Kind.PAGES;
                case "page-reference" -> InlineField.Kind.REFERENCE_PAGE;
                case "number-reference" -> InlineField.Kind.REFERENCE_NUMBER;
                default -> throw error(element, Code.UNSUPPORTED_ELEMENT);
            };
            boolean targeted = kind == InlineField.Kind.REFERENCE_PAGE || kind == InlineField.Kind.REFERENCE_NUMBER;
            var allowed = new HashSet<>(INLINE_STYLE_ATTRIBUTES);
            allowed.add("width");
            if (targeted) { allowed.add("target"); }
            attributes(element, allowed); empty(element);
            String target = targeted ? required(element, "target") : "";
            String targetValue = target.contains("${") ? expression(element, target) : targeted ? literalId(element, "target") : "";
            return validated(element, () -> new InlineField(kind, targetValue,
                    number(element, "width", 0), inlineStyle(element, defaults)));
        }

        private InlineStyle spanStyle(Element element, InlineStyle inherited) {
            var defaults = element.attributes().containsKey("style")
                    ? validated(element, () -> theme.inlineStyle(styleName(element))) : inherited;
            return inlineStyle(element, defaults);
        }

        private InlineStyle inlineStyle(Element element, InlineStyle defaults) {
            String role = value(element, "role", defaults.fontRole());
            if (element.attributes().containsKey("role") && !role.isEmpty() && !IDENTIFIER.matcher(role).matches()) {
                throw error(element, Code.INVALID_VALUE);
            }
            return new InlineStyle(role, number(element, "size", defaults.fontSize()), color(element, defaults.rgb()),
                    defaults.baselineShift(), flag(element, "underline", defaults.underline()),
                    flag(element, "strike", defaults.strikeThrough()));
        }

        private String styleName(Element element) {
            String name = value(element, "style", "");
            if (name.contains("${")) { throw error(element, Code.INVALID_EXPRESSION); }
            return name;
        }

        private void flush(Element element, StringBuilder text, List<InlineContent> content, InlineStyle style, String hyperlink) {
            if (!text.isEmpty()) { content.add(new InlineText(expression(element, text.toString()), style, hyperlink)); text.setLength(0); }
        }

        private String id(Element element) {
            String value = element.attributes().get("id");
            if (value == null) {
                String prefix = element.namespace().isEmpty() ? "" : element.namespace() + "-";
                do { value = prefix + "xml-" + ++nextId; } while (explicitIds.contains(value) || ids.contains(value));
            }
            if (!ID.matcher(value).matches() || !ids.add(value)) { throw error(element, Code.INVALID_VALUE); }
            return value;
        }
    }

    /** Checks original source position before include expansion can move or remove preceding elements. */
    private static void pagePlacement(Element root, boolean main) {
        Element first = null;
        for (var part : root.content()) {
            if (part instanceof Element element) { first = element; break; }
        }
        var allowedPages = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Element, Boolean>());
        if (main && first != null) { allowedPages.add(first); }
        if (main) {
            for (var part : root.content()) {
                if (part instanceof Element section && section.name().equals("section")) {
                    for (var child : section.content()) {
                        if (child instanceof Element element) { allowedPages.add(element); break; }
                    }
                }
            }
        }
        var pending = new ArrayDeque<Element>();
        pending.push(root);
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint();
            var element = pending.pop();
            if (element.name().equals("page") && !allowedPages.contains(element)) {
                throw error(element, Code.INVALID_STRUCTURE);
            }
            for (int i = element.content().size() - 1; i >= 0; i--) {
                if (element.content().get(i) instanceof Element child) { pending.push(child); }
            }
        }
    }

    private static List<Element> children(Element element) {
        List<Element> children = new ArrayList<>();
        for (var part : element.content()) {
            if (part instanceof Element child) { children.add(child); }
            else if (!((Text) part).value().isBlank()) { throw error(element, Code.INVALID_STRUCTURE); }
        }
        return children;
    }

    private static String plain(Element element) {
        StringBuilder result = new StringBuilder();
        for (var part : element.content()) {
            if (!(part instanceof Text text)) { throw error((Element) part, Code.UNSUPPORTED_ELEMENT); }
            result.append(text.value());
        }
        return result.toString();
    }

    private static void empty(Element element) {
        if (!element.content().isEmpty()) { throw error(element, Code.INVALID_STRUCTURE); }
    }
    private static void requireName(Element element, String name) {
        if (!element.name().equals(name)) { throw error(element, Code.INVALID_STRUCTURE); }
    }
    private static void attributes(Element element, Set<String> allowed) {
        if (!allowed.containsAll(element.attributes().keySet())) { throw error(element, Code.UNSUPPORTED_ATTRIBUTE); }
    }
    private static String value(Element element, String name, String fallback) { return element.attributes().getOrDefault(name, fallback); }
    private static String required(Element element, String name) {
        var value = element.attributes().get(name);
        if (value == null || value.isEmpty()) { throw error(element, Code.INVALID_STRUCTURE); }
        return value;
    }
    private static String identifier(Element element, String name) {
        var value = required(element, name);
        if (!IDENTIFIER.matcher(value).matches()) { throw error(element, Code.INVALID_VALUE); }
        return value;
    }
    private static String literalId(Element element, String name) {
        var value = required(element, name);
        if (!ID.matcher(value).matches()) { throw error(element, Code.INVALID_VALUE); }
        return value;
    }
    private static String path(Element element, String name) { return checkedPath(element, required(element, name)); }
    private static String checkedPath(Element element, String path) {
        if (path.length() > 256 || !PATH.matcher(path).matches()) { throw error(element, Code.INVALID_EXPRESSION); }
        return path;
    }
    private static String expression(Element element, String text) {
        for (int cursor = 0; cursor < text.length();) {
            ExecutionContext.checkpoint();
            int start = text.indexOf("${", cursor);
            if (start < 0) { break; }
            int end = text.indexOf('}', start + 2);
            if (end < 0 || end - start - 2 > 256) { throw error(element, Code.INVALID_EXPRESSION); }
            checkedPath(element, text.substring(start + 2, end));
            cursor = end + 1;
        }
        return text;
    }
    private static double number(Element element, String name, double fallback) {
        String text = element.attributes().get(name);
        if (text == null) { return fallback; }
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) { throw error(element, Code.INVALID_VALUE); }
            return value;
        } catch (NumberFormatException invalid) { throw error(element, Code.INVALID_VALUE); }
    }
    private static double length(Element element, String name, double fallback) {
        String text = element.attributes().get(name);
        if (text == null) { return fallback; }
        var match = LENGTH.matcher(text);
        if (!match.matches()) { throw error(element, Code.INVALID_VALUE); }
        try {
            double value = Double.parseDouble(match.group(1));
            String unit = match.group(2);
            double points = value * (unit == null ? 1 : switch (unit) {
                case "pt" -> 1;
                case "mm" -> 72 / 25.4;
                case "cm" -> 72 / 2.54;
                case "in" -> 72;
                default -> throw new AssertionError("unreachable length unit");
            });
            if (!Double.isFinite(points)) { throw error(element, Code.INVALID_VALUE); }
            return points;
        } catch (NumberFormatException invalid) { throw error(element, Code.INVALID_VALUE); }
    }
    private static int integer(Element element, String name) {
        try { return Integer.parseInt(required(element, name)); }
        catch (NumberFormatException invalid) { throw error(element, Code.INVALID_VALUE); }
    }
    private static int positiveInteger(Element element, String name) {
        String value = element.attributes().getOrDefault(name, "");
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') { throw error(element, Code.INVALID_VALUE); }
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) { throw error(element, Code.INVALID_VALUE); }
            return parsed;
        } catch (NumberFormatException invalid) { throw error(element, Code.INVALID_VALUE); }
    }
    private static boolean flag(Element element, String name, boolean fallback) {
        String value = element.attributes().get(name);
        if (value == null) { return fallback; }
        if (value.equals("true")) { return true; }
        if (value.equals("false")) { return false; }
        throw error(element, Code.INVALID_VALUE);
    }
    private static ParagraphAlignment alignment(Element element, ParagraphAlignment fallback) {
        String value = element.attributes().get("align");
        if (value == null) { return fallback; }
        try { return ParagraphAlignment.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw error(element, Code.INVALID_VALUE); }
    }
    private static int color(Element element, int fallback) {
        String value = element.attributes().get("color");
        if (value == null) { return fallback; }
        if (!value.matches("#[0-9a-fA-F]{6}")) { throw error(element, Code.INVALID_VALUE); }
        return Integer.parseInt(value.substring(1), 16);
    }
    private static TemplateException error(Element element, Code code) {
        return new TemplateException(code, element.line(), element.column(), element.fragmentName());
    }
    private static <T> T validated(Element element, Supplier<T> operation) {
        return validated(element, Code.INVALID_VALUE, operation);
    }
    private static <T> T validated(Element element, Code invalidCode, Supplier<T> operation) {
        try { return operation.get(); }
        catch (TemplateException failure) { throw failure; }
        catch (IllegalArgumentException invalid) { throw error(element, invalidCode); }
    }
    private static TemplateException failure(Code code, int line, int column) { return new TemplateException(code, line, column); }

    /** Starts task-confined configuration. Defaults are A4, 12/18 pt body/cells, strict limits and no resources. */
    public static Builder builder() { return new Builder(); }

    /** Mutable configuration; build snapshots it and resources cannot be selected by paths or URLs. */
    public static final class Builder {
        private PageSettings pageSettings = PageSettings.A4;
        private DocumentTheme theme = DocumentTheme.builder().build();
        private ParagraphFormat bodyFormat = ParagraphFormat.of(12);
        private CellFormat cellFormat = CellFormat.of(12);
        private TextDefaults bodyText;
        private TextDefaults cellText;
        private final Map<Integer, TextDefaults> headingText = new HashMap<>();
        private final Map<String, ImageResource> images = new HashMap<>();
        private final Map<String, String> fragments = new HashMap<>();
        private Limits limits = Limits.DEFAULT;
        private DocumentTemplate.Limits bindingLimits = DocumentTemplate.Limits.DEFAULT;
        private Builder() { }
        /** Replaces fallback page settings. A main-source first page element overrides only declared values;
         * its uniform margin is applied before per-edge margins, and orientation reorders the resulting size.
         * Null is rejected. Built compilers snapshot the immutable value; omitting page preserves it exactly. */
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        /** Initializes complete body, cell, heading, table and named-style defaults for future compilers.
         * Explicit TextDefaults remain complete overrides in either setter order; undeclared domains inherit theme appearance. The immutable theme owns no resources;
         * null is rejected, and built compilers and compiled templates do not track subsequent builder edits. */
        public Builder theme(DocumentTheme value) {
            theme = Objects.requireNonNull(value, "theme");
            bodyFormat = value.bodyFormat(); cellFormat = value.cellFormat(); return this;
        }
        /** Replaces only top-level body/list defaults; cell, heading and named-style defaults stay unchanged. */
        public Builder bodyFormat(ParagraphFormat value) { bodyFormat = Objects.requireNonNull(value, "bodyFormat"); return this; }
        /** Replaces only table-cell defaults, including inner-table cells; body and theme styles stay unchanged. */
        public Builder cellFormat(CellFormat value) { cellFormat = Objects.requireNonNull(value, "cellFormat"); return this; }
        /** Sets only body/list font-role and RGB defaults; theme and geometry setters do not change them. */
        public Builder bodyText(TextDefaults value) { bodyText = Objects.requireNonNull(value, "bodyText"); return this; }
        /** Configures body/list text defaults synchronously and snapshots the callback result. */
        public Builder bodyText(Consumer<TextDefaults.Builder> configure) {
            var builder = (bodyText == null ? defaults(theme.bodyText()) : bodyText).toBuilder(); Objects.requireNonNull(configure, "configure").accept(builder);
            return bodyText(builder.build());
        }
        /** Sets only cell text defaults, including inner tables; outer cell overrides do not reach inner tables. */
        public Builder cellText(TextDefaults value) { cellText = Objects.requireNonNull(value, "cellText"); return this; }
        /** Restores the body's complete appearance to the current/future theme. */
        public Builder inheritBodyText() { bodyText = null; return this; }
        /** Restores cell appearance to the current/future theme, including inner-table cells. */
        public Builder inheritCellText() { cellText = null; return this; }
        /** Restores one heading level's appearance to the current/future theme. */
        public Builder inheritHeadingText(int level) {
            if (level < 1 || level > 6) { throw new IllegalArgumentException("heading level must be 1..6"); }
            headingText.remove(level); return this;
        }
        public Builder cellText(Consumer<TextDefaults.Builder> configure) {
            var builder = (cellText == null ? defaults(theme.cellText()) : cellText).toBuilder(); Objects.requireNonNull(configure, "configure").accept(builder);
            return cellText(builder.build());
        }
        /** Sets font-role/RGB for one heading level (1..6), independent of all paragraph formats. */
        public Builder headingText(int level, TextDefaults value) {
            if (level < 1 || level > 6) { throw new IllegalArgumentException("heading level must be 1..6"); }
            headingText.put(level, Objects.requireNonNull(value, "headingText")); return this;
        }
        public Builder headingText(int level, Consumer<TextDefaults.Builder> configure) {
            if (level < 1 || level > 6) { throw new IllegalArgumentException("heading level must be 1..6"); }
            var builder = headingText.getOrDefault(level, defaults(theme.headingText(level))).toBuilder();
            Objects.requireNonNull(configure, "configure").accept(builder); return headingText(level, builder.build());
        }
        public Builder limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder bindingLimits(DocumentTemplate.Limits value) { bindingLimits = Objects.requireNonNull(value, "bindingLimits"); return this; }
        /** Registers a name in the immutable resource allowlist. The compiler performs no decoding or I/O. */
        public Builder image(String name, ImageResource resource) {
            if (!IDENTIFIER.matcher(Objects.requireNonNull(name, "name")).matches()) { throw new IllegalArgumentException("invalid resource name"); }
            images.put(name, Objects.requireNonNull(resource, "resource")); return this;
        }
        /** Registers an immutable offline fragment source; no path resolution or I/O is performed.
         * Names match {@code [A-Za-z_][A-Za-z_0-9-]{0,63}}. Duplicate names and more than 128 entries
         * are rejected with IllegalArgumentException. Sources require a version-1 fragment root and may
         * include registered fragments within the bounded, acyclic include chain. Only referenced sources are parsed; all sources count toward input.
         * Null names/sources are rejected. build snapshots the registry; this builder is task-confined.
         */
        public Builder fragment(String name, String source) {
            if (!FRAGMENT_NAME.matcher(Objects.requireNonNull(name, "name")).matches()) {
                throw new IllegalArgumentException("invalid fragment name");
            }
            Objects.requireNonNull(source, "source");
            if (fragments.containsKey(name) || fragments.size() == 128) {
                throw new IllegalArgumentException("duplicate fragment name or fragment registration limit exceeded");
            }
            fragments.put(name, source); return this;
        }
        public XmlTemplate build() { return new XmlTemplate(this); }
    }
}
