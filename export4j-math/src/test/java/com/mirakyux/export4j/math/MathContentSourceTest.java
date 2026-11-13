// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SourceContentParser;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detects budget resets between direct requests and Java/XML templates, and escaped closed scopes. */
class MathContentSourceTest {
    @Test void automaticRequestsHaveUniqueIdsAndPreserveDefaults() throws Exception {
        var metadata = new DocumentMetadata("Scoped input", "Explicit author");
        var page = PageSettings.builder().size(500, 700).margins(24).build();
        var options = ContentOptions.builder().rgb(0x234567).fontSize(11).build().resolve(ContentOptions.defaults());
        var importer = ContentImporter.builder().defaults(options).build();
        try (var source = new MathContentSource(importer, options, ImportLimits.DEFAULT, metadata, page)) {
            var first = source.read("one").execute().document();
            var second = source.read("two").execute().document();
            assertEquals(metadata, first.metadata()); assertEquals(page, first.pageSettings());
            assertFalse(first.blocks().getFirst().id().equals(second.blocks().getFirst().id()));
            var combined = new ArrayList<>(first.blocks()); combined.addAll(second.blocks());
            assertEquals(2, new Document(metadata, page, combined).blocks().size());
            assertEquals(0x234567, ((Paragraph) first.blocks().getFirst()).inlines().getFirst().style().rgb());
            var local = source.read("three").options(ContentOptions.builder().rgb(0).build()).execute().document();
            assertEquals(0, ((Paragraph) local.blocks().getFirst()).inlines().getFirst().style().rgb());
        }
    }

    @Test void inputNodesTextAndTableSlotsAccumulateAcrossRequests() throws Exception {
        try (var source = source(new ImportLimits(10, 10, 100, 16, 100, 100, 10, 10_000))) {
            source.read("123456").execute();
            assertThrows(IllegalArgumentException.class, () -> source.read("789012").execute());
        }
        try (var source = source(new ImportLimits(100, 100, 5, 16, 100, 100, 10, 10_000))) {
            var markdown = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN).build();
            source.read("one").options(markdown).execute();
            assertThrows(IllegalArgumentException.class, () -> source.read("two").options(markdown).execute());
        }
        try (var source = source(new ImportLimits(100, 100, 100, 16, 6, 100, 10, 10_000))) {
            source.read("abcd").execute();
            assertThrows(IllegalArgumentException.class, () -> source.read("efgh").execute());
        }
        try (var source = source(new ImportLimits(1_000, 1_000, 1_000, 16, 1_000, 6, 10, 100_000))) {
            String table = "<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>";
            var html = ContentOptions.builder().format(ContentOptions.Format.HTML).build();
            source.read(table).options(html).execute();
            assertThrows(IllegalArgumentException.class, () -> source.read(table).options(html).execute());
        }
    }

    @Test void leaseClosingDoesNotResetDirectOrXmlBudgets() throws Exception {
        try (var source = source(new ImportLimits(10, 10, 100, 16, 100, 100, 10, 10_000))) {
            source.read("1234").execute();
            try (var lease = source.openSession()) { assertEquals(1, lease.parse("lease", "5678", ContentOptions.defaults(), remaining()).size()); }
            assertThrows(IllegalArgumentException.class, () -> source.read("901").execute());
        }
        try (var source = source(new ImportLimits(10, 10, 100, 16, 100, 100, 10, 10_000))) {
            source.read("123456").execute();
            var xml = XmlTemplate.builder().contentParser(source).build().compile("""
                    <document version="1"><content id="xml">789012</content></document>
                    """);
            assertThrows(IllegalArgumentException.class, () -> xml.render(Map.of()));
        }
        try (var source = source(new ImportLimits(10, 10, 100, 16, 100, 100, 10, 10_000))) {
            var first = DocumentTemplate.builder().node(source.source("first", "123456")).build();
            assertEquals(1, first.render(Map.of()).blocks().size());
            var second = DocumentTemplate.builder().node(source.source("second", "789012")).build();
            assertThrows(IllegalArgumentException.class, () -> second.render(Map.of()));
        }
    }

    @Test void everyNamespaceIsUniqueAcrossRequestsAndLeases() throws Exception {
        try (var source = source(ImportLimits.DEFAULT)) {
            var explicit = source.read("math-content-0", "first");
            var automatic = source.read("second");
            assertThrows(IllegalArgumentException.class, () -> source.read("math-content-0", "duplicate"));
            try (var lease = source.openSession()) {
                assertThrows(IllegalArgumentException.class, () -> lease.parse("math-content-0", "duplicate", ContentOptions.defaults(), remaining()));
            }
            assertEquals(1, explicit.execute().document().blocks().size());
            assertEquals(1, automatic.execute().document().blocks().size());
            assertThrows(IllegalStateException.class, explicit::execute);
        }
    }

    @Test void reservedButUnexecutedRequestsShareTheNamespaceLimitWithLeases() {
        try (var source = source(new ImportLimits(100, 100, 2, 16, 100, 100, 10, 10_000))) {
            source.read("reserved-first", "one");
            source.read("reserved-second", "two");
            assertThrows(IllegalArgumentException.class, () -> source.read("three"));
            assertThrows(IllegalArgumentException.class, () -> source.read("reserved-third", "three"));
            try (var lease = source.openSession()) {
                assertThrows(IllegalArgumentException.class,
                        () -> lease.parse("reserved-fourth", "four", ContentOptions.defaults(), remaining()));
            }
        }
    }

    @Test void closedSourcesInvalidateExistingRequestsTemplatesAndLeases() {
        var source = source(ImportLimits.DEFAULT);
        var request = source.read("pending");
        var lease = source.openSession();
        var template = DocumentTemplate.builder().node(source.source("template", "pending")).build();
        source.close(); source.close();
        assertThrows(IllegalStateException.class, () -> source.read("new"));
        assertThrows(IllegalStateException.class, source::openSession);
        assertThrows(IllegalStateException.class, () -> source.source("late", "text"));
        assertThrows(IllegalStateException.class, request::execute);
        assertThrows(IllegalStateException.class, () -> request.options(ContentOptions.builder().rgb(0).build()));
        assertThrows(IllegalStateException.class, () -> request.execution(ExecutionOptions.DEFAULT));
        assertThrows(IllegalStateException.class, () -> lease.parse("old", "text", ContentOptions.defaults(), remaining()));
        assertThrows(IllegalStateException.class, () -> template.render(Map.of()));
        lease.close();
    }

    @Test void closedLeaseAndConcurrentEscapeFailWithoutConsumingOwnerState() throws Exception {
        try (var source = source(ImportLimits.DEFAULT)) {
            var lease = source.openSession(); lease.close();
            assertThrows(IllegalStateException.class, () -> lease.parse("old", "text", ContentOptions.defaults(), remaining()));
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try { source.read("foreign"); } catch (Throwable thrown) { failure.set(thrown); }
            });
            thread.start(); thread.join(5_000);
            assertFalse(thread.isAlive()); assertTrue(failure.get() instanceof IllegalStateException);
            assertEquals(1, source.read("owner").execute().document().blocks().size());
        }
    }

    @Test void closedSourcesAndLeasesAllowIdempotentCleanupWithoutTheCreatingThread() throws Exception {
        var source = source(ImportLimits.DEFAULT);
        var openLease = source.openSession();
        var closedLease = source.openSession(); closedLease.close();
        var request = source.read("pending");
        source.close();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                source.close(); openLease.close(); openLease.close(); closedLease.close();
                assertThrows(IllegalStateException.class, request::execute);
                assertThrows(IllegalStateException.class, source::openSession);
            } catch (Throwable thrown) { failure.set(thrown); }
        });
        thread.start(); thread.join(5_000);
        assertFalse(thread.isAlive());
        assertNull(failure.get());
    }

    @Test void cancellationIsNotConvertedToAnEmptySuccessfulImport() {
        try (var source = source(ImportLimits.DEFAULT)) {
            var request = source.read("text").execution(ExecutionOptions.builder().cancelled(() -> true).build());
            assertThrows(ExecutionException.class, request::execute);
        }
    }

    private static MathContentSource source(ImportLimits limits) {
        return new MathContentSource(ContentImporter.builder().limits(limits).build(), ContentOptions.defaults(), limits,
                DocumentMetadata.EMPTY, PageSettings.A4);
    }

    private static SourceContentParser.Remaining remaining() {
        return new SourceContentParser.Remaining(10_000, 10_000, 10_000, 100_000, 32);
    }
}
