// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextBlockAppearanceTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static final TextAppearance BLUE = new TextAppearance("brand", 0x224488);
    private static final TextAppearance RED = new TextAppearance("regular", 0x884422);

    @Test void compatibilityConstructorsRetainOriginalExplicitDefaultAppearance() {
        assertEquals(TextAppearance.DEFAULT, new Caption("caption", "Figure", "Text", FORMAT).appearance());
        assertEquals(TextAppearance.DEFAULT,
                new CrossReference("reference", "See ", "caption", CrossReference.Kind.NUMBER, FORMAT).appearance());
    }

    @Test void bindingPreservesAppearanceWhileRelocatingReferencesAndReplacingData() {
        var caption = new Caption("caption", "Figure", "${item}", FORMAT, BLUE);
        var reference = new CrossReference("reference", "See ${item}", "caption", CrossReference.Kind.NUMBER, FORMAT, RED);
        var template = DocumentTemplate.builder().each("items", "item", nodes -> nodes.block(caption).block(reference)).build();
        var blocks = template.render(Map.of("items", List.of("A", "B"))).blocks();
        assertEquals(BLUE, ((Caption) blocks.getFirst()).appearance());
        assertEquals(RED, ((CrossReference) blocks.get(1)).appearance());
        assertEquals("caption@0", ((CrossReference) blocks.get(1)).targetId());
        assertEquals("caption@1", ((CrossReference) blocks.get(3)).targetId());
        assertEquals("A", ((Caption) blocks.getFirst()).text());
        assertEquals("See B", ((CrossReference) blocks.get(3)).prefix());
    }

    @Test void textAndParagraphEditsPreserveWholeAppearanceWithoutEnablingCaptionRunStyles() throws Exception {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(
                new Caption("caption", "Figure", "old caption", FORMAT, BLUE),
                new CrossReference("reference", "old reference ", "caption", CrossReference.Kind.NUMBER, FORMAT, RED)));
        var changedFormat = ParagraphFormat.of(14);
        var result = DocumentEditor.edit(source).replaceText("old", "new")
                .paragraphFormat("caption", changedFormat).execute().document();
        var caption = (Caption) result.blocks().getFirst();
        var reference = (CrossReference) result.blocks().getLast();
        assertEquals("new caption", caption.text()); assertEquals(changedFormat, caption.format());
        assertEquals(BLUE, caption.appearance()); assertEquals(RED, reference.appearance());
        assertEquals("new reference ", reference.prefix());
        assertThrows(ProcessingException.class, () -> DocumentEditor.edit(result)
                .inlineStyle("caption", 0, 3, InlineStyle.of(12)).execute());
        assertEquals(BLUE, ((Caption) source.blocks().getFirst()).appearance());
    }
}
