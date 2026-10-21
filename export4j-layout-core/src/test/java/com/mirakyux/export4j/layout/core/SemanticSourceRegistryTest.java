// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticSourceRegistryTest {
    private static FontGroup fonts;
    private static Table source;
    private static TableFragment measured;

    @BeforeAll static void fixture() throws IOException {
        try (var input = SemanticSourceRegistryTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
        source = Table.builder("source").row("A").build();
        var document = new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(200, 200), PageMargins.uniform(0)), List.of(source));
        measured = (TableFragment) new DocumentLayouter().layout(document, fonts).pages().getFirst().elements().getFirst();
    }

    @Test void sourceIsRegisteredOnceWhileEveryFragmentMustBeOrderedAndComplete() {
        var registry = new SemanticSourceRegistry(List.of("host"));
        assertTrue(registry.accept("host", placement(source, 0, 3)));
        assertFalse(registry.accept("host", placement(source, 1, 3)));
        assertThrows(IllegalArgumentException.class, registry::sources);
        assertFalse(registry.accept("host", placement(source, 2, 3)));
        assertEquals(List.of(source), registry.sources());
        assertThrows(IllegalArgumentException.class, () -> registry.accept("host", placement(source, 0, 3)));
    }

    @Test void repeatedMissingReorderedAndOutOfRangeFragmentsFail() {
        for (int index : List.of(0, 2)) {
            var registry = new SemanticSourceRegistry(List.of());
            registry.accept("host", placement(source, 0, 3));
            assertThrows(IllegalArgumentException.class, () -> registry.accept("host", placement(source, index, 3)));
        }
        var registry = new SemanticSourceRegistry(List.of());
        assertThrows(IllegalArgumentException.class, () -> registry.accept("host", placement(source, 1, 3)));
        assertTrue(registry.accept("host", placement(source, 0, 3)));
        assertFalse(registry.accept("host", placement(source, 1, 3)));
        assertFalse(registry.accept("host", placement(source, 2, 3)));
        assertEquals(List.of(source), registry.sources());
        assertThrows(IllegalArgumentException.class, () -> placement(source, -1, 3));
        assertThrows(IllegalArgumentException.class, () -> placement(source, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> placement(source, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> placement(source, 0, 100_001));
    }

    @Test void topLevelCollisionsAndChangesToHostSnapshotCountOrFontsFail() {
        var reserved = new SemanticSourceRegistry(List.of("source"));
        assertThrows(IllegalArgumentException.class, () -> reserved.accept("host", placement(source, 0, 1)));
        var registry = new SemanticSourceRegistry(List.of());
        registry.accept("host", placement(source, 0, 2));
        var equalSnapshot = Table.builder("source").row("A").build();
        assertThrows(IllegalArgumentException.class, () -> registry.accept("host", placement(equalSnapshot, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> registry.accept("another-host", placement(source, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> registry.accept("host", placement(source, 1, 3)));
        var roleChange = new SemanticPlacement(0, source, measured, fonts, Map.of("new-role", fonts), 1, 2);
        assertThrows(IllegalArgumentException.class, () -> registry.accept("host", roleChange));
        registry.accept("host", placement(source, 1, 2));
        assertEquals(List.of(source), registry.sources());
    }

    @Test void compositionPreservesContinuationMetadataAndLegacyConstructorIsAtomic() {
        var second = placement(source, 1, 2);
        var child = new ComponentLayout(200, 100, 1, List.of(), List.of(), List.of(second));
        var builder = new DisplayListBuilder(new PageSize(240, 140));
        var placements = new ArrayList<SemanticPlacement>();
        builder.save().transform(AffineTransform.translation(10, 20));
        ComponentSemantics.append(builder, placements, child);
        builder.restore();
        assertEquals(1, placements.getFirst().fragmentIndex());
        assertEquals(2, placements.getFirst().fragmentCount());
        var atomic = new SemanticPlacement(0, source, measured, fonts, Map.of());
        assertEquals(0, atomic.fragmentIndex()); assertEquals(1, atomic.fragmentCount());
    }

    @Test void descendantsReserveIdsAcrossHostsAndRealContinuationsDoNotRegisterThemAgain() {
        var nested = Table.builder("nested").row("INNER").build();
        var tree = Table.builder("tree").row(TableCell.builder().blocks(List.of(
                new Paragraph("leaf", "TEXT", ParagraphFormat.of(12)), nested)).build()).build();
        for (String id : List.of("leaf", "nested")) {
            var reserved = new SemanticSourceRegistry(List.of(id));
            assertThrows(IllegalArgumentException.class, () -> reserved.accept("host", placement(tree, 0, 1)));
        }
        var registry = new SemanticSourceRegistry(List.of("host", "other-host"));
        assertTrue(registry.accept("host", placement(tree, 0, 2)));
        assertFalse(registry.accept("host", placement(tree, 1, 2)));
        var conflictingRoot = Table.builder("leaf").row("OTHER").build();
        assertThrows(IllegalArgumentException.class, () -> registry.accept("other-host", placement(conflictingRoot, 0, 1)));
        var conflictingChild = Table.builder("other-tree").row(TableCell.builder()
                .paragraph("nested", "OTHER").build()).build();
        assertThrows(IllegalArgumentException.class, () -> registry.accept("other-host", placement(conflictingChild, 0, 1)));
        assertEquals(List.of(tree), registry.sources());
    }

    private static SemanticPlacement placement(Table table, int index, int count) {
        var fragment = table == source ? measured : (TableFragment) new DocumentLayouter().layout(
                new Document(DocumentMetadata.EMPTY,
                        new PageSettings(new PageSize(200, 200), PageMargins.uniform(0)), List.of(table)), fonts)
                .pages().getFirst().elements().getFirst();
        return new SemanticPlacement(0, table, fragment, fonts, Map.of(), index, count);
    }
}
