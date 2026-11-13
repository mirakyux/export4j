// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Family aliases must retain their distinct semantic identities without weakening style validation. */
class DocxFontAliasTest {
    private static final DocxFontFamilies FAMILY = new DocxFontFamilies("Latin Body", "CJK Body");
    private static final String W = DocxSemanticXml.W;

    @Test void aliasesOfTheSameFamilyAndVariantSurviveStrictReadbackAlongsideCanonicalRoles() throws IOException {
        var adapter = DocxAdapter.builder().defaultFonts(FAMILY)
                .fontRole("family-0", FAMILY, false, false)
                .fontRole("family-1", FAMILY, false, false)
                .fontRole("family-0-bold", FAMILY, true, false)
                .fontRole("family-0-italic", FAMILY, false, true)
                .fontRole("family-0-both", FAMILY, true, true).build();
        var source = document("", "bold", "italic", "boldItalic", "family-0", "family-1",
                "family-0-bold", "family-0-italic", "family-0-both");
        byte[] encoded = write(adapter, source);
        var imported = adapter.read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty());
        assertEquals(source.blocks(), imported.document().sections().getFirst().blocks());
        var styles = DocxAdapter.parse(adapter.unzip(new ByteArrayInputStream(encoded)).get("word/styles.xml"));
        var alias = style(styles, "family-0-both");
        var properties = DocxAdapter.child(alias, "rPr");
        assertEquals(FAMILY, DocxFontProfiles.read(DocxAdapter.child(properties, "rFonts")));
        assertTrue(DocxAdapter.child(properties, "b") != null);
        assertTrue(DocxAdapter.child(properties, "i") != null);
    }

    @Test void aliasOnlyConfigurationCanStrictlyReadItsOutputWithoutInventingCanonicalMappings() throws IOException {
        var adapter = DocxAdapter.builder().fontRole("first", FAMILY, false, false)
                .fontRole("second", FAMILY, false, false).build();
        var source = document("first", "second");
        var imported = adapter.read(new ByteArrayInputStream(write(adapter, source)), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty());
        assertEquals(source.blocks(), imported.document().sections().getFirst().blocks());
    }

    @Test void profileConflictsAreRejectedInEitherRegistrationOrderAndAliasDuplicatesDoNotReplace() {
        assertThrows(IllegalArgumentException.class, () -> DocxAdapter.builder().defaultFonts(FAMILY)
                .fontRole("bold", FAMILY, true, false).build());
        assertThrows(IllegalArgumentException.class, () -> DocxAdapter.builder().fontRole("bold", FAMILY, true, false)
                .defaultFonts(FAMILY).build());
        var roles = new DocxAdapter.FontRoles("named", "named-bold", "named-italic", "named-both");
        assertThrows(IllegalArgumentException.class, () -> DocxAdapter.builder().fontRole("named", FAMILY, false, false)
                .fontFamily(FAMILY, roles).build());
        var builder = DocxAdapter.builder().fontRole("alias", FAMILY, false, false);
        assertThrows(IllegalArgumentException.class, () -> builder.fontRole("alias", FAMILY, true, false));
        assertFalse(builder.build().mappedFontRole("not-registered"));
    }

    @Test void builtAdaptersSnapshotAliasesAndAliasIdentifiersCannotCorruptGeneratedStyles() {
        var builder = DocxAdapter.builder().fontRole("first", FAMILY, false, false);
        var first = builder.build(); var second = builder.fontRole("second", FAMILY, false, false).build();
        assertTrue(first.mappedFontRole("first")); assertFalse(first.mappedFontRole("second"));
        assertTrue(second.mappedFontRole("second"));
        for (var invalid : List.of("", " ", "line\nfeed", "\ud800", "Heading1", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> DocxAdapter.builder().fontRole(invalid, FAMILY, false, false));
        }
    }

    @Test void mismatchedAliasStyleCannotPassTheDeclaredOutputFastPath() throws IOException {
        var adapter = DocxAdapter.builder().defaultFonts(FAMILY).fontRole("alias", FAMILY, true, false).build();
        byte[] encoded = write(adapter, document("alias"));
        for (boolean changeFamily : List.of(false, true)) {
            var parts = adapter.unzip(new ByteArrayInputStream(encoded));
            var styles = DocxAdapter.parse(parts.get("word/styles.xml"));
            var properties = DocxAdapter.child(style(styles, "alias"), "rPr");
            if (changeFamily) {
                DocxAdapter.child(properties, "rFonts").setAttributeNS(W, "w:eastAsia", "Unregistered Family");
            } else {
                DocxAdapter.child(properties, "b").setAttributeNS(W, "w:val", "0");
            }
            parts.put("word/styles.xml", DocxSemanticXml.encode(styles, DocxLimits.DEFAULT.maxEntryBytes()));
            byte[] tampered = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
            assertThrows(IOException.class, () -> adapter.read(new ByteArrayInputStream(tampered), DocxAdapter.Policy.STRICT));
        }
    }

    private static Document document(String... roles) {
        var builder = Document.builder();
        for (int index = 0; index < roles.length; index++) {
            builder.add(new Paragraph("p" + index, List.of(new InlineText("Text " + index,
                    InlineStyle.of(12).toBuilder().fontRole(roles[index]).build())), ParagraphFormat.of(12)));
        }
        return builder.build();
    }

    private static byte[] write(DocxAdapter adapter, Document source) throws IOException {
        var output = new ByteArrayOutputStream(); adapter.write(source, output); return output.toByteArray();
    }

    private static Element style(Element root, String id) {
        return DocxSemanticXml.all(root, W, "style").stream()
                .filter(style -> style.getAttributeNS(W, "styleId").equals(id)).findFirst().orElseThrow();
    }
}
