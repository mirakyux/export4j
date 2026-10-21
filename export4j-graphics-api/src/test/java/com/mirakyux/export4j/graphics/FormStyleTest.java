// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormStyleTest {
    private static final Rect BOUNDS = new Rect(10, 20, 120, 24);
    private static final RgbColor INK = new RgbColor(0.1, 0.2, 0.3);
    private static final RgbColor PAPER = new RgbColor(0.8, 0.9, 1);

    @Test
    void emptyStylePreservesUnspecifiedPropertiesAndCannotBeMutated() {
        var style = FormStyle.builder().build();
        assertEquals(FormStyle.DEFAULT, style);
        assertEquals(FormStyle.DEFAULT.hashCode(), style.hashCode());
        assertEquals(Set.of(), style.explicitProperties());
        for (var property : FormStyle.Property.values()) { assertFalse(style.isExplicit(property)); }
        assertThrows(UnsupportedOperationException.class,
                () -> style.explicitProperties().add(FormStyle.Property.COLOR));
        assertEquals(style, style.toBuilder().build());
    }

    @Test
    void explicitDefaultValuesRemainDifferentFromUnspecifiedValues() {
        var defaults = FormStyle.DEFAULT;
        var explicit = FormStyle.builder().fontSize(10).color(RgbColor.BLACK)
                .noBackground().border(0, RgbColor.BLACK).alignment(FormStyle.Alignment.LEFT).build();
        assertNotEquals(defaults, explicit);
        assertEquals(Set.of(FormStyle.Property.FONT_SIZE, FormStyle.Property.COLOR, FormStyle.Property.BACKGROUND,
                FormStyle.Property.BORDER, FormStyle.Property.ALIGNMENT), explicit.explicitProperties());
        assertEquals(10, explicit.fontSize());
        assertEquals(RgbColor.BLACK, explicit.color());
        assertFalse(explicit.backgroundEnabled());
        assertEquals(FormStyle.BorderMode.FIXED, explicit.borderMode());
        assertEquals(0, explicit.borderWidth());
        assertEquals(RgbColor.BLACK, explicit.borderColor());
        assertEquals(FormStyle.Alignment.LEFT, explicit.alignment());
        assertEquals(explicit, explicit.toBuilder().build());
        assertEquals(explicit.hashCode(), explicit.toBuilder().build().hashCode());
    }

    @Test
    void builderSnapshotsAllPropertiesAndToBuilderChangesOnlyItsOwnValue() {
        var builder = FormStyle.builder().fontSize(13).color(INK).background(PAPER)
                .adaptiveBorder(2, INK).alignment(FormStyle.Alignment.RIGHT);
        var first = builder.build();
        builder.fontSize(7).color(RgbColor.BLACK).noBackground().noBorder().alignment(FormStyle.Alignment.LEFT);
        assertEquals(13, first.fontSize());
        assertEquals(INK, first.color());
        assertTrue(first.backgroundEnabled());
        assertEquals(PAPER, first.background());
        assertEquals(FormStyle.BorderMode.ADAPTIVE, first.borderMode());
        assertEquals(2, first.borderWidth());
        assertEquals(INK, first.borderColor());
        assertEquals(FormStyle.Alignment.RIGHT, first.alignment());
        var changed = first.toBuilder().noBackground().border(0.5, RgbColor.BLACK).build();
        assertEquals(first.explicitProperties(), changed.explicitProperties());
        assertEquals(first.fontSize(), changed.fontSize());
        assertEquals(first.color(), changed.color());
        assertEquals(first.alignment(), changed.alignment());
        assertFalse(changed.backgroundEnabled());
        assertEquals(FormStyle.BorderMode.FIXED, changed.borderMode());
        assertEquals(0.5, changed.borderWidth());
        assertTrue(first.backgroundEnabled());
        assertEquals(FormStyle.BorderMode.ADAPTIVE, first.borderMode());
    }

    @Test
    void disablingBackgroundAndBorderAreExplicitIndependentOverrides() {
        var background = FormStyle.builder().background(PAPER).build();
        assertEquals(Set.of(FormStyle.Property.BACKGROUND), background.explicitProperties());
        assertTrue(background.backgroundEnabled());
        var disabled = background.toBuilder().noBackground().build();
        assertFalse(disabled.backgroundEnabled());
        assertEquals(Set.of(FormStyle.Property.BACKGROUND), disabled.explicitProperties());
        var none = FormStyle.builder().noBorder().build();
        assertEquals(Set.of(FormStyle.Property.BORDER), none.explicitProperties());
        assertEquals(FormStyle.BorderMode.NONE, none.borderMode());
        assertNotEquals(FormStyle.DEFAULT, none);
        assertNotEquals(none, FormStyle.builder().border(0, RgbColor.BLACK).build());
    }

    @Test
    void rejectsInvalidNumbersAndNullPropertiesAtThePublicBoundary() {
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> FormStyle.builder().fontSize(invalid).build());
            assertThrows(IllegalArgumentException.class, () -> FormStyle.builder().adaptiveBorder(invalid, INK).build());
        }
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> FormStyle.builder().border(invalid, INK).build());
        }
        assertThrows(NullPointerException.class, () -> FormStyle.builder().color(null).build());
        assertThrows(NullPointerException.class, () -> FormStyle.builder().background(null).build());
        assertThrows(NullPointerException.class, () -> FormStyle.builder().border(1, null).build());
        assertThrows(NullPointerException.class, () -> FormStyle.builder().adaptiveBorder(1, null).build());
        assertThrows(NullPointerException.class, () -> FormStyle.builder().alignment(null).build());
    }

    @Test
    void everyLegacyOutputConstructorRetainsTheUnspecifiedStyle() {
        var fields = List.<DocumentExtras.FormField>of(
                new DocumentExtras.TextField(0, BOUNDS, "text", "Ada", false),
                new DocumentExtras.TextField(0, BOUNDS, "fontText", "Ada", false, List.of()),
                new DocumentExtras.ChoiceField(0, BOUNDS, "choice", List.of("A", "B"), "B", List.of()),
                new DocumentExtras.CheckBox(0, BOUNDS, "check", true),
                new DocumentExtras.RadioGroup(0, BOUNDS, "radio", List.of("A", "B"), "B"));
        fields.forEach(field -> assertSame(FormStyle.DEFAULT, field.style()));
    }

    @Test
    void resolvedFieldsKeepStyleAndOwnOptionAndFontLists() {
        var style = FormStyle.builder().color(INK).background(PAPER).border(0.8, INK).build();
        var options = new ArrayList<>(List.of("A", "B"));
        var fields = List.<DocumentExtras.FormField>of(
                new DocumentExtras.TextField(0, BOUNDS, "text", "Ada", false, List.of(), style),
                new DocumentExtras.ChoiceField(0, BOUNDS, "choice", options, "B", List.of(), style),
                new DocumentExtras.CheckBox(0, BOUNDS, "check", true, style),
                new DocumentExtras.RadioGroup(0, BOUNDS, "radio", options, "B", style));
        options.clear();
        fields.forEach(field -> assertEquals(style, field.style()));
        var choice = (DocumentExtras.ChoiceField) fields.get(1);
        assertEquals(List.of("A", "B"), choice.options());
        assertThrows(UnsupportedOperationException.class, () -> choice.options().clear());
        assertEquals(List.of("A", "B"), ((DocumentExtras.RadioGroup) fields.get(3)).options());
    }

    @Test
    void markFieldsRejectExplicitTextPropertiesEvenWhenEqualToDefaults() {
        for (var style : List.of(FormStyle.builder().fontSize(10).build(),
                FormStyle.builder().alignment(FormStyle.Alignment.LEFT).build())) {
            assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.CheckBox(0, BOUNDS, "check", false, style));
            assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.RadioGroup(0, BOUNDS, "radio", List.of("A", "B"), "A", style));
        }
        var explicitBlack = FormStyle.builder().color(RgbColor.BLACK).noBackground().noBorder().build();
        assertEquals(explicitBlack, new DocumentExtras.CheckBox(0, BOUNDS, "check", true, explicitBlack).style());
        assertEquals(explicitBlack, new DocumentExtras.RadioGroup(0, BOUNDS, "radio", List.of("A", "B"), "A", explicitBlack).style());
    }

    @Test
    void stylesDoNotRelaxExistingValueValidationAndCannotBeNull() {
        var style = FormStyle.builder().color(INK).build();
        assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.TextField(0, BOUNDS, "text", "中文", false, List.of(), style));
        assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.ChoiceField(0, BOUNDS, "choice", List.of("A", "B"), "C", List.of(), style));
        assertThrows(IllegalArgumentException.class, () -> new DocumentExtras.RadioGroup(0, BOUNDS, "radio", List.of("A", "A"), "A", style));
        assertThrows(NullPointerException.class, () -> new DocumentExtras.TextField(0, BOUNDS, "text", "", false, List.of(), null));
        assertThrows(NullPointerException.class, () -> new DocumentExtras.ChoiceField(0, BOUNDS, "choice", List.of("A"), "A", List.of(), null));
        assertThrows(NullPointerException.class, () -> new DocumentExtras.CheckBox(0, BOUNDS, "check", false, null));
        assertThrows(NullPointerException.class, () -> new DocumentExtras.RadioGroup(0, BOUNDS, "radio", List.of("A", "B"), "A", null));
    }
}
