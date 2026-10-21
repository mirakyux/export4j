// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListMarkerStyleTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(10);
    private static final InlineStyle MARKER = new InlineStyle("marker", 14, 0x224488, -2, true, true);
    private static final InlineText BODY = new InlineText("BODY", new InlineStyle("body", 10, 0x882244, 0, false, false), "https://example.com");

    @Test void everyLegacyConstructorAndFactoryKeepsTheParagraphDerivedMarkerDefault() {
        var expected = InlineStyle.of(FORMAT.fontSize());
        var bullet = new BulletItem("b", "BODY", "*", 1, FORMAT);
        var bulletRuns = new BulletItem("b", "BODY", "*", 1, FORMAT, List.of(BODY));
        var bulletRich = BulletItem.rich("b", List.of(BODY), "*", 1, FORMAT);
        assertEquals(expected, bullet.markerStyle());
        assertEquals(expected, bulletRuns.markerStyle());
        assertEquals(expected, bulletRich.markerStyle());
        assertEquals(bulletRuns, bulletRich);
        var numbered = new NumberedItem("n", "list", "BODY", 0, FORMAT);
        var numberedLevel = new NumberedItem("n", "list", "BODY", 0, FORMAT, 2);
        var numberedRuns = new NumberedItem("n", "list", "BODY", 0, FORMAT, 2, List.of(BODY));
        var numberedRich = NumberedItem.rich("n", "list", List.of(BODY), 0, FORMAT, 2);
        for (var item : List.of(numbered, numberedLevel, numberedRuns, numberedRich)) {
            assertEquals(expected, item.markerStyle());
        }
        assertEquals(numberedRuns, numberedRich);
        assertEquals(bullet, new BulletItem("b", "BODY", "*", 1, FORMAT, List.of(), expected));
        assertEquals(numbered, new NumberedItem("n", "list", "BODY", 0, FORMAT, 1, List.of(), expected));
    }

    @Test void explicitMarkerStyleIsIndependentOfTheFirstBodyRunAndItsExternalLink() {
        var bullet = BulletItem.rich("b", List.of(BODY), "*", 3, FORMAT, MARKER);
        var numbered = NumberedItem.rich("n", "list", List.of(BODY), 7, FORMAT, 2, MARKER);
        assertEquals(MARKER, bullet.markerStyle());
        assertEquals(MARKER, numbered.markerStyle());
        assertEquals(List.of(BODY), bullet.paragraph().inlines());
        assertEquals(List.of(BODY), numbered.paragraph().inlines());
        assertEquals("BODY", bullet.text());
        assertEquals("BODY", numbered.text());
        assertEquals(7, numbered.restartAt());
        assertEquals(3, bullet.level());
        assertEquals(2, numbered.level());
    }

    @Test void freshBuildersDeriveDefaultMarkersFromTheFinalFormat() {
        var bullet = BulletItem.builder().id("b").text("BODY").marker("*").format(FORMAT)
                .format(format -> format.fontSize(16).lineHeight(24)).build();
        var numbered = NumberedItem.builder().id("n").listId("list").text("BODY").format(FORMAT)
                .format(format -> format.fontSize(16).lineHeight(24)).level(2).restartAt(4).build();
        assertEquals(InlineStyle.of(16), bullet.markerStyle());
        assertEquals(InlineStyle.of(16), numbered.markerStyle());
        assertEquals(4, numbered.restartAt());
        assertEquals(2, numbered.level());
    }

    @Test void explicitMarkerValuesRemainCompleteWhenTheParagraphFormatChanges() {
        var bullet = BulletItem.builder().id("b").text("BODY").marker("*").format(FORMAT).markerStyle(MARKER)
                .format(ParagraphFormat.of(20)).build();
        var numbered = NumberedItem.builder().id("n").listId("list").text("BODY").format(FORMAT).markerStyle(MARKER)
                .format(ParagraphFormat.of(20)).build();
        assertEquals(MARKER, bullet.markerStyle());
        assertEquals(MARKER, numbered.markerStyle());
        assertEquals(20, bullet.format().fontSize());
        assertEquals(20, numbered.format().fontSize());
    }

    @Test void markerCallbacksSnapshotTheCurrentDefaultOrPreviouslyExplicitStyle() {
        var captured = new AtomicReference<InlineStyle.Builder>();
        var numbered = NumberedItem.builder().id("n").listId("list").text("BODY").format(FORMAT).markerStyle(style -> {
            captured.set(style); style.fontRole("marker").rgb(0x224488);
        }).format(ParagraphFormat.of(20)).build();
        assertEquals(new InlineStyle("marker", 10, 0x224488, 0, false, false), numbered.markerStyle());
        captured.get().fontSize(30).rgb(0xff0000);
        assertEquals(10, numbered.markerStyle().fontSize());
        assertEquals(0x224488, numbered.markerStyle().rgb());
        var bullet = BulletItem.builder().id("b").text("BODY").marker("*").markerStyle(MARKER)
                .markerStyle(style -> style.strikeThrough(false)).build();
        assertEquals(new InlineStyle("marker", 14, 0x224488, -2, true, false), bullet.markerStyle());
    }

    @Test void toBuilderKeepsTheOriginalCompleteStyleUntilExplicitlyReset() {
        var bullet = new BulletItem("b", "BODY", "*", 1, FORMAT);
        var numbered = new NumberedItem("n", "list", "BODY", 0, FORMAT);
        assertEquals(bullet, bullet.toBuilder().build());
        assertEquals(numbered, numbered.toBuilder().build());
        assertEquals(InlineStyle.of(10), bullet.toBuilder().format(ParagraphFormat.of(20)).build().markerStyle());
        assertEquals(InlineStyle.of(10), numbered.toBuilder().format(ParagraphFormat.of(20)).build().markerStyle());
        assertEquals(InlineStyle.of(20), bullet.toBuilder().markerStyle(MARKER).defaultMarkerStyle()
                .format(ParagraphFormat.of(20)).build().markerStyle());
        assertEquals(InlineStyle.of(20), numbered.toBuilder().markerStyle(MARKER).defaultMarkerStyle()
                .format(ParagraphFormat.of(20)).build().markerStyle());
        assertEquals(InlineStyle.of(10), bullet.markerStyle());
        assertEquals(InlineStyle.of(10), numbered.markerStyle());
    }

    @Test void bodyInputsAreSnapshottedAndTextAndInlinesReplaceEachOther() {
        var source = new ArrayList<>(List.of(BODY));
        var bulletBuilder = BulletItem.builder().id("b").marker("*").inlines(source).markerStyle(MARKER);
        var numberedBuilder = NumberedItem.builder().id("n").listId("list").inlines(source).markerStyle(MARKER);
        source.clear();
        var bullet = bulletBuilder.build();
        var numbered = numberedBuilder.build();
        assertEquals(List.of(BODY), bullet.inlines());
        assertEquals(List.of(BODY), numbered.inlines());
        assertThrows(UnsupportedOperationException.class, () -> bullet.inlines().clear());
        assertThrows(UnsupportedOperationException.class, () -> numbered.inlines().clear());
        assertEquals(List.of(), bulletBuilder.text("PLAIN").build().inlines());
        assertEquals("PLAIN", numberedBuilder.text("PLAIN").build().text());
        assertEquals(List.of(BODY), bulletBuilder.inlines(List.of(BODY)).build().inlines());
        assertEquals("BODY", numberedBuilder.inlines(List.of(BODY)).build().text());
        assertEquals(MARKER, bullet.markerStyle());
        assertEquals(MARKER, numbered.markerStyle());
    }

    @Test void nullStylesAndInvalidLegacyStructureStillFailAtConstruction() {
        assertThrows(NullPointerException.class, () -> new BulletItem("b", "BODY", "*", 1, FORMAT, List.of(), null));
        assertThrows(NullPointerException.class, () -> new NumberedItem("n", "list", "BODY", 0, FORMAT, 1, List.of(), null));
        assertThrows(NullPointerException.class, () -> BulletItem.builder().markerStyle((InlineStyle) null));
        assertThrows(NullPointerException.class, () -> NumberedItem.builder().markerStyle((InlineStyle) null));
        assertThrows(IllegalArgumentException.class, () -> BulletItem.builder().id("b").text("BODY").marker(" ").markerStyle(MARKER).build());
        assertThrows(IllegalArgumentException.class, () -> NumberedItem.builder().id("n").listId("list").text("BODY").restartAt(-1).markerStyle(MARKER).build());
        assertThrows(IllegalArgumentException.class, () -> BulletItem.builder().id("b").text("BODY").marker("*").level(10).markerStyle(MARKER).build());
        assertThrows(IllegalArgumentException.class, () -> NumberedItem.builder().id("n").listId("list").text("BODY").level(0).markerStyle(MARKER).build());
    }

    @Test void eachBindingAndPrefixedCopiesPreserveMarkerValuesAndBodyStyles() {
        var run = new InlineText("${item.name}", BODY.style(), BODY.hyperlink());
        var bullet = BulletItem.rich("fragment.b", List.of(run), "*", 2, FORMAT, MARKER);
        var numbered = NumberedItem.rich("fragment.n", "fragment.list", List.of(run), 0, FORMAT, 1, MARKER);
        var copy = numbered.toBuilder().id("other.n").listId("other.list").build();
        var template = DocumentTemplate.builder().each("items", "item", nodes -> nodes.block(bullet).block(numbered).block(copy)).build();
        var data = Map.of("items", List.of(Map.of("name", "Alpha"), Map.of("name", "Beta")));
        var result = template.render(data);
        assertEquals(List.of("fragment.b@0", "fragment.n@0", "other.n@0", "fragment.b@1", "fragment.n@1", "other.n@1"),
                result.blocks().stream().map(Block::id).toList());
        for (var block : result.blocks()) {
            if (block instanceof BulletItem item) {
                assertEquals(MARKER, item.markerStyle()); assertEquals(BODY.style(), item.inlines().getFirst().style());
            } else if (block instanceof NumberedItem item) {
                assertEquals(MARKER, item.markerStyle()); assertEquals(BODY.hyperlink(), item.inlines().getFirst().hyperlink());
            }
        }
        assertEquals("fragment.list", ((NumberedItem) result.blocks().get(1)).listId());
        assertEquals("other.list", ((NumberedItem) result.blocks().get(2)).listId());
        assertEquals("Alpha", ((NumberedItem) result.blocks().get(1)).text());
        assertEquals("Beta", ((NumberedItem) result.blocks().get(4)).text());
        assertEquals("${item.name}", numbered.text());
        assertEquals(result, template.render(data));
    }

    @Test void markerMetadataDoesNotBypassOrConsumeTemplateBodyAndExpansionBudgets() {
        var prototype = NumberedItem.builder().id("n").listId("list").text("BODY").format(FORMAT).markerStyle(MARKER).build();
        var node = new DocumentTemplate.ValueBlock(prototype);
        var exact = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(node), new DocumentTemplate.Limits(100, 1, 1, 4, 8));
        assertEquals(MARKER, ((NumberedItem) exact.render(Map.of()).blocks().getFirst()).markerStyle());
        var tooLittleText = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(node), new DocumentTemplate.Limits(100, 1, 1, 3, 8));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> tooLittleText.render(Map.of())).code());
        var tooMany = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4,
                List.of(node, new DocumentTemplate.ValueBlock(prototype.toBuilder().id("second").build())), new DocumentTemplate.Limits(100, 1, 1, 100, 8));
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> tooMany.render(Map.of())).code());
    }
}
