// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

class ContentsStyleTest {
    private static final RgbColor RED = new RgbColor(0.8, 0.1, 0.2);
    private static final RgbColor GREEN = new RgbColor(0.1, 0.6, 0.2);
    private static final RgbColor BLUE = new RgbColor(0.1, 0.2, 0.8);
    private static final ParagraphStyle TITLE = new ParagraphStyle(20, 32, 4, 6, TextAlignment.CENTER, LineBreakPolicy.CJK_STRICT);
    private static final ParagraphStyle LABEL = new ParagraphStyle(11, 26, 2, 3, TextAlignment.LEFT, LineBreakPolicy.CJK_STRICT);

    private static ContentsStyle complete() {
        return ContentsStyle.builder().titleStyle(TITLE).pageLabelStyle(LABEL)
                .titleColor(RED).entryColor(GREEN).pageLabelColor(BLUE).build();
    }

    @Test void defaultsCarryNoDeclarationsAndReturnImmutableEmptyTypography() {
        var value = ContentsStyle.DEFAULT;
        assertEquals(Optional.empty(), value.titleStyle()); assertEquals(Optional.empty(), value.pageLabelStyle());
        assertEquals(RgbColor.BLACK, value.titleColor()); assertEquals(RgbColor.BLACK, value.entryColor());
        assertEquals(RgbColor.BLACK, value.pageLabelColor()); assertTrue(value.explicitProperties().isEmpty());
        for (var property : ContentsStyle.Property.values()) { assertFalse(value.isExplicit(property)); }
        assertThrows(UnsupportedOperationException.class, () -> value.explicitProperties().add(ContentsStyle.Property.TITLE_STYLE));
        assertEquals(value, ContentsStyle.builder().build()); assertEquals(value, value.toBuilder().build());
    }

    @Test void completeDeclarationsKeepFullStylesColorsAndAnImmutableIntentSet() {
        var value = complete();
        assertEquals(Optional.of(TITLE), value.titleStyle()); assertEquals(Optional.of(LABEL), value.pageLabelStyle());
        assertEquals(RED, value.titleColor()); assertEquals(GREEN, value.entryColor()); assertEquals(BLUE, value.pageLabelColor());
        assertEquals(Set.of(ContentsStyle.Property.values()), value.explicitProperties());
        for (var property : ContentsStyle.Property.values()) { assertTrue(value.isExplicit(property)); }
        assertThrows(UnsupportedOperationException.class, () -> value.explicitProperties().remove(ContentsStyle.Property.TITLE_COLOR));
        assertEquals(value, value.toBuilder().build()); assertEquals(value.hashCode(), value.toBuilder().build().hashCode());
    }

    @Test void builtValuesAndToBuilderCopiesDoNotTrackLaterMutations() {
        var builder = ContentsStyle.builder().titleStyle(TITLE).titleColor(RED);
        var first = builder.build(); builder.pageLabelStyle(LABEL).titleColor(BLUE);
        assertEquals(RED, first.titleColor()); assertTrue(first.pageLabelStyle().isEmpty());
        var copy = first.toBuilder().entryColor(GREEN); var second = copy.build(); copy.defaultTitleStyle().defaultEntryColor();
        assertEquals(Optional.of(TITLE), second.titleStyle()); assertEquals(GREEN, second.entryColor());
        assertFalse(first.isExplicit(ContentsStyle.Property.ENTRY_COLOR)); assertEquals(RgbColor.BLACK, first.entryColor());
    }

    @Test void eachDefaultResetRemovesOnlyItsOwnDeclarationAndAllResetsRestoreDefaultEquality() {
        var original = complete();
        var resets = java.util.List.<Consumer<ContentsStyle.Builder>>of(ContentsStyle.Builder::defaultTitleStyle,
                ContentsStyle.Builder::defaultPageLabelStyle, ContentsStyle.Builder::defaultTitleColor,
                ContentsStyle.Builder::defaultEntryColor, ContentsStyle.Builder::defaultPageLabelColor);
        var properties = ContentsStyle.Property.values();
        for (int index = 0; index < properties.length; index++) {
            var builder = original.toBuilder(); resets.get(index).accept(builder); var reset = builder.build();
            assertFalse(reset.isExplicit(properties[index])); assertEquals(4, reset.explicitProperties().size());
            for (var property : properties) { if (property != properties[index]) { assertTrue(reset.isExplicit(property)); } }
        }
        assertEquals(ContentsStyle.DEFAULT, original.toBuilder().defaultTitleStyle().defaultPageLabelStyle()
                .defaultTitleColor().defaultEntryColor().defaultPageLabelColor().build());
        assertEquals(original, complete());
    }

    @Test void explicitDefaultValuesRemainDifferentFromUnspecifiedIntent() {
        for (var value : java.util.List.of(ContentsStyle.builder().titleColor(RgbColor.BLACK).build(),
                ContentsStyle.builder().entryColor(RgbColor.BLACK).build(),
                ContentsStyle.builder().pageLabelColor(RgbColor.BLACK).build(),
                ContentsStyle.builder().titleStyle(ParagraphStyle.of(18)).build(),
                ContentsStyle.builder().pageLabelStyle(ParagraphStyle.of(12).withAlignment(TextAlignment.RIGHT)).build())) {
            assertNotEquals(ContentsStyle.DEFAULT, value); assertEquals(1, value.explicitProperties().size());
            assertEquals(value, value.toBuilder().build());
        }
    }

    @Test void nullDeclarationsPropertiesAndAppearanceInputsFailAtTheBoundary() {
        assertThrows(NullPointerException.class, () -> ContentsStyle.builder().titleStyle(null));
        assertThrows(NullPointerException.class, () -> ContentsStyle.builder().pageLabelStyle(null));
        assertThrows(NullPointerException.class, () -> ContentsStyle.builder().titleColor(null));
        assertThrows(NullPointerException.class, () -> ContentsStyle.builder().entryColor(null));
        assertThrows(NullPointerException.class, () -> ContentsStyle.builder().pageLabelColor(null));
        assertThrows(NullPointerException.class, () -> ContentsStyle.DEFAULT.isExplicit(null));
        assertThrows(NullPointerException.class, () -> new TableOfContents(true, "Contents", ParagraphStyle.of(10), null));
        assertThrows(NullPointerException.class, () -> TableOfContents.builder().appearance((ContentsStyle) null));
        assertThrows(NullPointerException.class, () -> TableOfContents.builder().appearance((Consumer<ContentsStyle.Builder>) null));
    }

    @Test void legacyDerivedStylesResetIndentsAlignmentAndBreakPolicyExactly() {
        var base = new ParagraphStyle(13, 22, 9, 5, TextAlignment.JUSTIFY, LineBreakPolicy.CJK_STRICT);
        var legacy = new TableOfContents(true, "Contents", base);
        var empty = TableOfContents.builder().title("Contents").style(base).appearance(value -> { }).build();
        assertEquals(ContentsStyle.DEFAULT, legacy.appearance()); assertEquals(legacy, empty);
        assertEquals(new ParagraphStyle(19.5, 33, 0, 0, TextAlignment.LEFT, LineBreakPolicy.STANDARD), legacy.titleStyle());
        assertEquals(new ParagraphStyle(13, 22, 0, 0, TextAlignment.RIGHT, LineBreakPolicy.STANDARD), legacy.pageLabelStyle());
        assertEquals(base, legacy.style()); assertEquals(ContentsStyle.DEFAULT, TableOfContents.NONE.appearance());
    }

    @Test void changingTheEntryBaseAfterBuildRecomputesOnlyUnspecifiedTypography() {
        var first = new TableOfContents(true, "Contents", ParagraphStyle.of(10));
        var laterBase = new ParagraphStyle(16, 30, 5, 7, TextAlignment.CENTER, LineBreakPolicy.CJK_STRICT);
        var later = first.toBuilder().style(laterBase).build();
        assertEquals(new ParagraphStyle(24, 45, 0, 0, TextAlignment.LEFT), later.titleStyle());
        assertEquals(new ParagraphStyle(16, 30, 0, 0, TextAlignment.RIGHT), later.pageLabelStyle());
        assertEquals(ContentsStyle.DEFAULT, later.appearance()); assertEquals(ParagraphStyle.of(10), first.style());
        var explicit = first.toBuilder().appearance(value -> value.titleStyle(first.titleStyle()).pageLabelStyle(first.pageLabelStyle())).build();
        var changed = explicit.toBuilder().style(laterBase).build();
        assertEquals(first.titleStyle(), changed.titleStyle()); assertEquals(first.pageLabelStyle(), changed.pageLabelStyle());
        assertTrue(changed.appearance().isExplicit(ContentsStyle.Property.TITLE_STYLE));
        assertTrue(changed.appearance().isExplicit(ContentsStyle.Property.PAGE_LABEL_STYLE));
    }

    @Test void partialDeclarationsAndIndividualResetResumeDerivationFromTheCurrentBase() {
        var base = ParagraphStyle.of(10);
        var value = TableOfContents.builder().title("Contents").style(base).appearance(style -> style.titleStyle(TITLE).entryColor(GREEN)).build();
        assertEquals(TITLE, value.titleStyle()); assertEquals(new ParagraphStyle(10, 15, 0, 0, TextAlignment.RIGHT), value.pageLabelStyle());
        var changed = value.toBuilder().style(ParagraphStyle.of(14)).appearance(style -> style.pageLabelStyle(LABEL).defaultTitleStyle()).build();
        assertEquals(new ParagraphStyle(21, 31.5, 0, 0, TextAlignment.LEFT), changed.titleStyle()); assertEquals(LABEL, changed.pageLabelStyle());
        assertEquals(GREEN, changed.appearance().entryColor()); assertFalse(changed.appearance().isExplicit(ContentsStyle.Property.TITLE_STYLE));
        assertEquals(TITLE, value.titleStyle());
    }

    @Test void callbacksSnapshotImmediatelySeedExistingDeclarationsAndLeaveParentIntactOnFailure() {
        var escaped = new AtomicReference<ContentsStyle.Builder>();
        var builder = TableOfContents.builder().title("Contents").appearance(value -> { value.titleColor(RED); escaped.set(value); });
        escaped.get().titleColor(BLUE).pageLabelStyle(LABEL);
        var first = builder.build(); assertEquals(RED, first.appearance().titleColor()); assertTrue(first.appearance().pageLabelStyle().isEmpty());
        builder.appearance(value -> value.entryColor(GREEN));
        var second = builder.build(); assertEquals(RED, second.appearance().titleColor()); assertEquals(GREEN, second.appearance().entryColor());
        assertThrows(IllegalStateException.class, () -> builder.appearance(value -> { value.titleColor(BLUE); throw new IllegalStateException("stop"); }));
        assertEquals(second, builder.build()); assertEquals(RgbColor.BLACK, first.appearance().entryColor());
    }
}
