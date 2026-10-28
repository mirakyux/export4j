// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavigationLossTest {
    private static SectionedDocument source() {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(new Paragraph("body", "Visible", ParagraphFormat.of(12))), 0)),
                DocumentNavigation.builder().bookmark("PRIVATE-NAVIGATION", "body", 1).build());
    }
    @Test void unsupportedNavigationNeedsExplicitDiscardAndIgnoreStillReportsActualLoss() throws Exception {
        for (var target : List.of(new HtmlAdapter().conversionTarget(), new MarkdownAdapter().conversionTarget())) {
            assertEquals("NAVIGATION", assertThrows(ProcessingException.class, () -> DocumentConversion.from(source(), List.of()).prepare(target)).issue().feature());
            byte[] warned = null;
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var policy = ProcessingPolicy.builder().feature("NAVIGATION", action, ProcessingPolicy.Treatment.DISCARD).build();
                var prepared = DocumentConversion.from(source(), List.of()).policy(policy).prepare(target);
                var bytes = new ByteArrayOutputStream(); var result = prepared.to(bytes);
                assertEquals(prepared.preview().issues(), result.report().issues());
                assertEquals(1, result.report().losses().size()); assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, result.report().warnings().size());
                assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("PRIVATE-NAVIGATION"));
                if (warned == null) { warned = bytes.toByteArray(); } else { assertArrayEquals(warned, bytes.toByteArray()); }
                assertEquals(source().navigation(), result.document().navigation());
            }
            var wrong = ProcessingPolicy.builder().feature("NAVIGATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build();
            assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(source(), List.of()).policy(wrong).prepare(target));
        }
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(source()).to(new ByteArrayOutputStream()));
    }
    @Test void discardedNavigationStillConsumesSourceTextBudget() {
        var adapter = HtmlAdapter.builder().limits(ExportLimits.builder().maxCodePoints(12).build()).build();
        var policy = ProcessingPolicy.builder().feature("NAVIGATION", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        assertThrows(ExportException.class, () -> DocumentConversion.from(source(), List.of()).policy(policy).prepare(adapter.conversionTarget()));
    }
}
