// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManuWeaveFontsTest {
    @TempDir Path temporary;

    @Test void sourceSerifAndSansFamiliesChooseDistinctRealVariantsAndDiscloseSubstitution() throws Exception {
        var fonts = new ManuWeaveFonts();
        var serif = fonts.selection("Times New Roman", "", true, true, false);
        var sans = fonts.selection("Arial", "", true, true, false);
        assertEquals("LiberationSerif-BoldItalic", serif.actual());
        assertEquals("f17db8af71e24d2066b587546021d4f0b296be389512b658dec3c09affeb11a7",
                serif.fonts().fonts().getFirst().data().id());
        assertEquals("LiberationSans-BoldItalic", sans.actual());
        assertNotEquals(serif.fonts().fonts().getFirst().data().id(), sans.fonts().fonts().getFirst().data().id());
        assertTrue(serif.substituted());
        assertFalse(serif.external());
        assertFalse(fonts.selection("Liberation Serif", "", false, false, false).substituted());
        assertTrue(fonts.selection("Computer Modern Roman", "", false, false, false).actual().startsWith("LiberationSerif"));
        assertTrue(fonts.selection("Linux Biolinum", "", false, false, false).actual().startsWith("LiberationSans"));
    }

    @Test void cjkUsesAnExplicitRegularFallbackWithoutFakingBoldOrItalic() throws Exception {
        var fonts = new ManuWeaveFonts();
        var selection = fonts.selection("Times New Roman", "宋体", true, true, true);
        assertEquals("LiberationSerif-BoldItalic;LXGWWenKaiTC-Regular", selection.actual());
        assertEquals(2, selection.fonts().fonts().size());
        assertTrue(selection.substituted());
        assertTrue(selection.fonts().fonts().getLast().glyph('中').isPresent());
        assertEquals("4fcc5aec11cbbf737b0cfab7b63796f7f280087a7a656b2f13342cd5e5318d95",
                selection.fonts().fonts().getLast().data().id());
    }

    @Test void externalMapUsesRelativeExplicitBytesAndDoesNotSilentlyFillMissingVariants() throws Exception {
        copyFont("LiberationSerif-Regular.ttf", "serif.ttf");
        var values = new Properties();
        values.setProperty("family.Times New Roman.regular", "serif.ttf");
        var fonts = new ManuWeaveFonts(map(values));
        var selection = fonts.selection("Times New Roman", "", false, false, false);
        assertTrue(selection.external());
        assertTrue(selection.substituted());
        assertEquals("LiberationSerif", selection.actual());
        assertFalse(selection.actual().contains(temporary.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> fonts.group("Times New Roman", "", true, false, false));
    }

    @Test void explicitVariantMustMatchPhysicalFontAndUnknownMapKeysFail() throws Exception {
        copyFont("LiberationSerif-Regular.ttf", "regular.ttf");
        var values = new Properties();
        values.setProperty("family.Times New Roman.bold", "regular.ttf");
        Path wrongVariant = map(values);
        assertTrue(assertThrows(IOException.class, () -> new ManuWeaveFonts(wrongVariant))
                .getMessage().contains("physical style"));
        values.clear(); values.setProperty("system-font", "Times New Roman");
        Path unknown = map(values);
        assertThrows(IOException.class, () -> new ManuWeaveFonts(unknown));
    }

    private void copyFont(String resource, String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/fonts/" + resource)) {
            assertNotNull(input);
            Files.copy(input, temporary.resolve(name));
        }
    }
    private Path map(Properties values) throws IOException {
        Path result = temporary.resolve("fonts.xml");
        try (var output = Files.newOutputStream(result)) { values.storeToXML(output, "explicit test font map"); }
        return result;
    }
}
