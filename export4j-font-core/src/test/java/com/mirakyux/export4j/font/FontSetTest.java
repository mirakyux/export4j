// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontSetTest {
    @TempDir Path directory;

    private static FontFace face(String name) throws IOException {
        try (var input = FontSetTest.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input);
        }
    }

    @Test void fontSetSnapshotsRolesAndRequiresExplicitUniqueVariants() throws IOException {
        var regular = FontGroup.strict(face("LiberationSans-Regular.ttf"));
        var bold = FontGroup.strict(face("LiberationSans-Bold.ttf"));
        var builder = FontSet.builder(regular).bold(bold);
        var first = builder.build(); builder.italic(regular).boldItalic(bold);
        assertEquals(regular, first.regular()); assertEquals(List.of("bold"), first.roles().keySet().stream().toList());
        assertEquals(bold, builder.build().roles().get("boldItalic"));
        assertThrows(UnsupportedOperationException.class, () -> first.roles().put("x", regular));
        assertThrows(IllegalArgumentException.class, () -> builder.role("bold", regular));
        assertThrows(IllegalArgumentException.class, () -> builder.role(" ", regular));
        assertThrows(NullPointerException.class, () -> builder.role("x", null));
    }

    @Test void variadicGroupsSnapshotTheirInput() throws IOException {
        var regular = face("LiberationSans-Regular.ttf"); var bold = face("LiberationSans-Bold.ttf");
        FontFace[] faces = {regular}; var group = FontGroup.strict(faces);
        var builder = FontGroup.builder().fonts(faces); faces[0] = bold;
        assertEquals(List.of(regular), group.fonts()); assertEquals(List.of(regular), builder.build().fonts());
        assertEquals(MissingGlyphPolicy.PLACEHOLDER_WITH_WARNING, FontGroup.placeholder('?', regular).missingGlyphPolicy());
    }

    @Test void fileBuilderLoadingIsBoundedAndLeavesNoOpenFileHandle() throws IOException {
        var path = directory.resolve("font sample.ttf");
        try (var input = FontSetTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) { Files.copy(input, path); }
        var group = FontGroup.builder().fontFile(path.toString()).fontFile(path.toFile()).fontFile(path).build();
        assertEquals(3, group.fonts().size()); assertTrue(group.fonts().getFirst().glyph('A').isPresent());
        var builder = FontGroup.builder().font(group.fonts().getFirst());
        assertThrows(FontLoadException.class, () -> builder.fontFile(path, 32));
        assertEquals(1, builder.build().fonts().size());
        Files.move(path, directory.resolve("released.ttf"));
    }
}
