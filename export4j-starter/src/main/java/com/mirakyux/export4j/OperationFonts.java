// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontFaceCache;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.math.MathContent;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.SourceContentParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable font binding for one synchronous operation. No binding survives in the exporter. */
final class OperationFonts {
    private record WordRole(String family, boolean bold, boolean italic) { }
    private final Map<String, List<FontFace>> roles;
    private final Map<String, List<String>> familyRoles;
    private final Map<String, WordRole> wordRoles;
    private final DocxFontFamilies defaultWord;

    private OperationFonts(Map<String, List<FontFace>> roles, Map<String, List<String>> familyRoles,
            Map<String, WordRole> wordRoles, DocxFontFamilies defaultWord) {
        this.roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        this.familyRoles = Collections.unmodifiableMap(new LinkedHashMap<>(familyRoles));
        this.wordRoles = Collections.unmodifiableMap(new LinkedHashMap<>(wordRoles));
        this.defaultWord = defaultWord;
    }

    static OperationFonts bind(List<FontFamily> defaults, List<FontFamily> families,
            Map<String, FontFamily> named, DocxFontFamilies defaultWord,
            boolean systemWordStyles, FontFaceCache physicalCache) throws IOException {
        var loaded = new IdentityHashMap<FontSource, FontFace>();
        var roles = new LinkedHashMap<String, List<FontFace>>();
        var familyRoles = new LinkedHashMap<String, List<String>>();
        var wordRoles = new LinkedHashMap<String, WordRole>();
        if (systemWordStyles) {
            List.of("", "bold", "italic", "boldItalic").forEach(role -> roles.put(role, List.of()));
        }
        for (int index = 0; index < families.size(); index++) {
            var family = families.get(index);
            String base = "family_" + index;
            familyRoles.put(family.name(), variants(base));
            for (var variant : family.variants().entrySet()) {
                ExecutionContext.checkpoint();
                var faces = physicalCache == null ? List.<FontFace>of()
                        : List.of(load(variant.getValue(), physicalCache, loaded));
                if (defaults.contains(family)) {
                    var values = new ArrayList<>(roles.getOrDefault(variant.getKey(), List.of()));
                    if (!values.containsAll(faces)) { values.addAll(faces); }
                    roles.put(variant.getKey(), List.copyOf(values));
                }
                bind(roles, wordRoles, role(base, variant.getKey()), family.name(), variant.getKey(), faces);
            }
        }
        for (var alias : named.entrySet()) {
            var family = alias.getValue();
            familyRoles.put(family.name(), variants(alias.getKey()));
            for (var variant : family.variants().entrySet()) {
                var faces = physicalCache == null ? List.<FontFace>of() : List.of(loaded.get(variant.getValue()));
                bind(roles, wordRoles, role(alias.getKey(), variant.getKey()), family.name(),
                        variant.getKey(), faces);
            }
        }
        named.keySet().forEach(alias -> familyRoles.put(alias, variants(alias)));
        return new OperationFonts(roles, familyRoles, wordRoles, defaultWord);
    }

    private static void bind(Map<String, List<FontFace>> roles, Map<String, WordRole> wordRoles,
            String role, String family, String variant, List<FontFace> faces) {
        roles.put(role, faces);
        wordRoles.put(role, new WordRole(family, variant.equals("bold") || variant.equals("boldItalic"),
                variant.equals("italic") || variant.equals("boldItalic")));
    }

    static List<FontFace> loadRegular(List<FontFamily> defaults, FontFaceCache cache) throws IOException {
        var loaded = new IdentityHashMap<FontSource, FontFace>();
        var regular = new ArrayList<FontFace>();
        for (var family : defaults) {
            var face = load(family.variants().get(""), cache, loaded);
            if (!regular.contains(face)) { regular.add(face); }
        }
        return regular;
    }

    private static FontFace load(FontSource source, FontFaceCache cache, Map<FontSource, FontFace> loaded)
            throws IOException {
        ExecutionContext.checkpoint();
        var face = loaded.get(source);
        if (face == null) {
            face = Objects.requireNonNull(source.load(cache, ExecutionContext::checkpoint), "font source result");
            loaded.put(source, face);
        }
        return face;
    }

    static List<String> variants(String role) {
        return List.of(role, role(role, "bold"), role(role, "italic"), role(role, "boldItalic"));
    }

    private static String role(String base, String variant) { return base + (variant.isEmpty() ? "" : "_" + variant); }
    List<FontFace> regular() { return roles.get(""); }
    void requireRole(String role) {
        if (!roles.containsKey(role)) { throw new IllegalArgumentException("undeclared font role '" + role + "'"); }
    }
    void validate(ContentOptions.Resolved options) {
        ExecutionContext.checkpoint();
        requireRole(options.fontRole(), "body");
        if (options.format() == ContentOptions.Format.MARKDOWN || options.format() == ContentOptions.Format.HTML) {
            requireRole(options.styles().cellText().fontRole(), "table cells");
            for (int level = 1; level <= 6; level++) {
                requireRole(options.styles().heading(level).text().fontRole(), "heading level " + level);
            }
        }
    }
    private void requireRole(String role, String area) {
        if (!roles.containsKey(role)) {
            throw new IllegalArgumentException("undeclared font role '" + role + "' for " + area);
        }
    }
    SourceContentParser checked(SourceContentParser parser) {
        return () -> {
            var borrowed = parser.openSession();
            return new SourceContentParser.Session() {
                private boolean closed;
                @Override public List<Block> parse(String namespace, String source, ContentOptions.Resolved options,
                        SourceContentParser.Remaining remaining) {
                    if (closed) { throw new IllegalStateException("content parser lease is closed"); }
                    validate(options);
                    return borrowed.parse(namespace, source, options, remaining);
                }
                @Override public void close() {
                    if (!closed) { closed = true; borrowed.close(); }
                }
            };
        };
    }
    void content(MathContent.Builder builder) {
        familyRoles.forEach((family, names) -> builder.fontFamily(family, names.get(0), names.get(1), names.get(2), names.get(3)));
    }
    DocumentEngine.Builder engine() {
        var builder = DocumentEngine.builder(FontGroup.strict(regular()));
        roles.forEach((role, faces) -> { if (!role.isEmpty()) { builder.fontRole(role, FontGroup.strict(faces)); } });
        return builder;
    }
    DocxAdapter.Builder word() {
        var builder = DocxAdapter.builder().defaultFonts(defaultWord).lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST);
        wordRoles.forEach((role, profile) -> builder.fontRole(role, DocxFontFamilies.uniform(profile.family()), profile.bold(), profile.italic()));
        return builder;
    }
}
