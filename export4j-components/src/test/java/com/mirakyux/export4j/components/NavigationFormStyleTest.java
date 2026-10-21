// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.text.ParagraphStyle;

class NavigationFormStyleTest {
    private static final Navigation.Target TARGET = new Navigation.BlockTarget("body", new Rect(3, 4, 90, 20));
    private static final RgbColor INK = new RgbColor(0.2, 0.4, 0.6);
    private static FontGroup fonts;

    @BeforeAll
    static void fonts() throws IOException {
        try (var input = NavigationFormStyleTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void oldConstructorsAndEmptyBuildersKeepLegacyStyles() {
        var fields = List.<Navigation.Field>of(new Navigation.TextField(TARGET, "text", "Ada", false),
                new Navigation.TextField(TARGET, "fontText", "Ada", false, List.of()),
                new Navigation.ChoiceField(TARGET, "choice", List.of("A", "B"), "B", List.of()),
                new Navigation.CheckBox(TARGET, "check", true),
                new Navigation.RadioGroup(TARGET, "radio", List.of("A", "B"), "B"),
                Navigation.TextField.builder().target(TARGET).name("builtText").build(),
                Navigation.ChoiceField.builder().target(TARGET).name("builtChoice").options(List.of("A")).value("A").build(),
                Navigation.CheckBox.builder().target(TARGET).name("builtCheck").build(),
                Navigation.RadioGroup.builder().target(TARGET).name("builtRadio").options(List.of("A", "B")).value("A").build());
        fields.forEach(field -> assertSame(FormStyle.DEFAULT, field.style()));
    }

    @Test
    void styleCallbacksSeedTheCurrentStyleAndSnapshotImmediatelyForAllFourFields() {
        var base = FormStyle.builder().color(INK).background(RgbColor.WHITE).build();
        var textHeld = new AtomicReference<FormStyle.Builder>();
        var choiceHeld = new AtomicReference<FormStyle.Builder>();
        var checkHeld = new AtomicReference<FormStyle.Builder>();
        var radioHeld = new AtomicReference<FormStyle.Builder>();
        var text = Navigation.TextField.builder().target(TARGET).name("text").style(base)
                .style(style -> { textHeld.set(style); style.fontSize(13).alignment(FormStyle.Alignment.RIGHT); });
        var choice = Navigation.ChoiceField.builder().target(TARGET).name("choice").options(List.of("A", "B")).value("B")
                .style(base).style(style -> { choiceHeld.set(style); style.fontSize(11).alignment(FormStyle.Alignment.CENTER); });
        var check = Navigation.CheckBox.builder().target(TARGET).name("check").checked(true).style(base)
                .style(style -> { checkHeld.set(style); style.noBackground().border(0, RgbColor.BLACK); });
        var radio = Navigation.RadioGroup.builder().target(TARGET).name("radio").options(List.of("A", "B")).value("B")
                .style(base).style(style -> { radioHeld.set(style); style.adaptiveBorder(2, INK); });
        for (var held : List.of(textHeld, choiceHeld, checkHeld, radioHeld)) {
            held.get().color(RgbColor.BLACK).noBorder().noBackground();
        }
        var first = text.build();
        var fields = List.<Navigation.Field>of(first, choice.build(), check.build(), radio.build());
        fields.forEach(field -> assertEquals(INK, field.style().color()));
        assertEquals(13, first.style().fontSize());
        assertEquals(FormStyle.Alignment.RIGHT, first.style().alignment());
        assertEquals(FormStyle.Alignment.CENTER, fields.get(1).style().alignment());
        assertFalse(fields.get(2).style().backgroundEnabled());
        assertEquals(FormStyle.BorderMode.FIXED, fields.get(2).style().borderMode());
        assertEquals(0, fields.get(2).style().borderWidth());
        assertEquals(FormStyle.BorderMode.ADAPTIVE, fields.get(3).style().borderMode());
        assertEquals(2, fields.get(3).style().borderWidth());
        text.style(style -> style.color(RgbColor.BLACK));
        assertEquals(INK, first.style().color());
        assertEquals(RgbColor.BLACK, text.build().style().color());
        assertEquals(13, text.build().style().fontSize());
        assertThrows(UnsupportedOperationException.class, () -> fields.getFirst().style().explicitProperties().clear());
    }

    @Test
    void styleReplacementDiscardsTheEarlierExplicitProperties() {
        var first = FormStyle.builder().fontSize(14).color(INK).border(2, INK).build();
        var replacement = FormStyle.builder().noBackground().build();
        var field = Navigation.TextField.builder().target(TARGET).name("text").style(first).style(replacement).build();
        assertEquals(replacement, field.style());
        assertFalse(field.style().isExplicit(FormStyle.Property.FONT_SIZE));
        assertFalse(field.style().isExplicit(FormStyle.Property.BORDER));
        assertFalse(field.style().isExplicit(FormStyle.Property.COLOR));
    }

    @Test
    void fixedBorderIsNotComparedAgainstTheTemporaryValidationRectangle() {
        var style = FormStyle.builder().border(4, INK).build();
        var text = new Navigation.TextField(TARGET, "text", "Ada", false, List.of(), style);
        var choice = new Navigation.ChoiceField(TARGET, "choice", List.of("A", "B"), "A", List.of(), style);
        var check = new Navigation.CheckBox(TARGET, "check", true, style);
        var radio = new Navigation.RadioGroup(TARGET, "radio", List.of("A", "B"), "A", style);
        var source = Document.builder().paragraph("body", "A").build();
        var result = DocumentEngine.builder(fonts).navigation(new Navigation(List.of(), TableOfContents.NONE, List.of(),
                List.of(text, choice, check, radio))).build().layout(source);
        result.display().extras().fields().forEach(field -> assertEquals(style, field.style()));
    }

    @Test
    void finalFieldsRetainTheirOwnStylesAcrossContentsOffsetAndFirstFragmentResolution() {
        var textStyle = FormStyle.builder().fontSize(13).color(INK).noBackground()
                .border(0, RgbColor.BLACK).alignment(FormStyle.Alignment.RIGHT).build();
        var choiceStyle = FormStyle.builder().fontSize(11).alignment(FormStyle.Alignment.CENTER).build();
        var markStyle = FormStyle.builder().color(RgbColor.BLACK).noBackground().noBorder().build();
        var frontTarget = new Navigation.PageTarget(0, new Position.Absolute(new Rect(20, 110, 120, 20)));
        var navigation = Navigation.builder().bookmark("Body", "body", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10)))
                .field(Navigation.TextField.builder().target(TARGET).name("text").value("Ada").style(textStyle).build())
                .field(Navigation.ChoiceField.builder().target(TARGET).name("choice").options(List.of("A", "B")).value("B").style(choiceStyle).build())
                .field(Navigation.CheckBox.builder().target(TARGET).name("check").checked(true).style(markStyle).build())
                .field(Navigation.RadioGroup.builder().target(frontTarget).name("radio").options(List.of("A", "B")).value("B").style(markStyle).build())
                .build();
        var source = Document.builder().pageSettings(PageSettings.builder().size(240, 160).margins(20).build())
                .bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .paragraph("body", String.join("\n", java.util.Collections.nCopies(20, "ROW"))).build();
        var result = DocumentEngine.builder(fonts).navigation(navigation).build().layout(source);
        assertTrue(result.bodyPageOffset() > 0);
        assertTrue(result.body().pages().size() > 1);
        var fields = result.display().extras().fields();
        assertEquals(4, fields.size());
        var origin = result.body().pages().getFirst().elements().getFirst().origin();
        var bounds = new Rect(origin.x() + 3, origin.y() + 4, 90, 20);
        for (int index = 0; index < 3; index++) {
            assertEquals(result.bodyPageOffset(), fields.get(index).pageIndex());
            assertEquals(bounds, fields.get(index).bounds());
        }
        assertEquals(textStyle, fields.get(0).style());
        assertEquals(choiceStyle, fields.get(1).style());
        assertEquals(markStyle, fields.get(2).style());
        assertEquals(markStyle, fields.get(3).style());
        assertEquals(0, fields.get(3).pageIndex());
        assertEquals(new Rect(20, 110, 120, 20), fields.get(3).bounds());
        assertEquals("Ada", ((DocumentExtras.TextField) fields.getFirst()).value());
        assertEquals("B", ((DocumentExtras.ChoiceField) fields.get(1)).value());
        assertTrue(((DocumentExtras.CheckBox) fields.get(2)).checked());
        assertEquals("B", ((DocumentExtras.RadioGroup) fields.get(3)).value());
    }

    @Test
    void markStylesRejectTextPropertiesAndEveryBuilderRejectsNullConfiguration() {
        var textProperty = FormStyle.builder().alignment(FormStyle.Alignment.LEFT).build();
        assertThrows(IllegalArgumentException.class, () -> Navigation.CheckBox.builder().target(TARGET).name("check").style(textProperty).build());
        assertThrows(IllegalArgumentException.class, () -> Navigation.RadioGroup.builder().target(TARGET).name("radio")
                .options(List.of("A", "B")).value("A").style(style -> style.fontSize(10)).build());
        assertThrows(NullPointerException.class, () -> Navigation.TextField.builder().style((FormStyle) null));
        assertThrows(NullPointerException.class, () -> Navigation.ChoiceField.builder().style((FormStyle) null));
        assertThrows(NullPointerException.class, () -> Navigation.CheckBox.builder().style((FormStyle) null));
        assertThrows(NullPointerException.class, () -> Navigation.RadioGroup.builder().style((FormStyle) null));
        assertThrows(NullPointerException.class, () -> Navigation.TextField.builder().style((java.util.function.Consumer<FormStyle.Builder>) null));
        assertThrows(NullPointerException.class, () -> Navigation.ChoiceField.builder().style((java.util.function.Consumer<FormStyle.Builder>) null));
        assertThrows(NullPointerException.class, () -> Navigation.CheckBox.builder().style((java.util.function.Consumer<FormStyle.Builder>) null));
        assertThrows(NullPointerException.class, () -> Navigation.RadioGroup.builder().style((java.util.function.Consumer<FormStyle.Builder>) null));
    }
}
