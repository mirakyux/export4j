// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossReferenceEditTest {
    @Test void globalTextEditChangesLiteralPrefixAndKeepsReferenceRelationship() throws Exception {
        var format = ParagraphFormat.builder().fontSize(10).lineHeight(18).spaceBefore(3).build();
        var reference = new CrossReference("reference", "ORIGINAL 😀=", "heading", CrossReference.Kind.PAGE, format);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(new Heading("heading", "ORIGINAL", 1), reference), 5)));
        var found = DocumentEditor.edit(source).find("ORIGINAL");
        assertEquals(List.of("heading", "reference"), found.matches().stream().map(m -> m.location().blockId()).toList());
        assertTrue(found.report().issues().isEmpty());
        var edited = DocumentEditor.edit(source).replaceText("ORIGINAL", "EDITED").execute();
        var current = (CrossReference) edited.document().sections().getFirst().blocks().get(1);
        assertEquals(new CrossReference("reference", "EDITED 😀=", "heading", CrossReference.Kind.PAGE, format), current);
        assertTrue(edited.report().issues().isEmpty()); assertEquals(2, edited.changes().size());
        assertEquals("ORIGINAL 😀=", reference.prefix());
    }
}
