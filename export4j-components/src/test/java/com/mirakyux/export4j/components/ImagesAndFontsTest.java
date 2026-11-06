// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.FontSet;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagesAndFontsTest {
    @TempDir Path directory;

    private static byte[] png() throws IOException {
        var pixels = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        try { assertTrue(ImageIO.write(pixels, "png", bytes)); return bytes.toByteArray(); }
        finally { pixels.flush(); }
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        boolean closed;
        BorrowedInput(byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }

    @Test void imageFileAndBorrowedStreamInputsAreBoundedAndConsistent() throws IOException {
        var file = directory.resolve("图片 sample.png"); byte[] bytes = png(); Files.write(file, bytes);
        var expected = Images.load(file); var stream = new BorrowedInput(bytes);
        assertEquals(expected, Images.load(file.toString())); assertEquals(expected, Images.load(file.toFile(), bytes.length));
        assertEquals(expected, Images.load(stream)); assertFalse(stream.closed);
        var excessive = new BorrowedInput(new byte[64]);
        assertThrows(IOException.class, () -> Images.load(excessive, 16));
        assertEquals(47, excessive.available()); assertFalse(excessive.closed);
        var malformed = new BorrowedInput(new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> Images.load(malformed)); assertFalse(malformed.closed);
        assertThrows(IllegalArgumentException.class, () -> Images.load(file, 0));
        Files.move(file, directory.resolve("released.png"));
    }

    @Test void explicitImageSizingPreservesAspectRatioAndRejectsInvalidGeometry() throws IOException {
        var resource = Images.load(new ByteArrayInputStream(png()));
        var byWidth = InlineImage.atWidth(resource, 72, "logo");
        assertEquals(72, byWidth.width()); assertEquals(36, byWidth.height());
        var fitted = InlineImage.fit(resource, 72, 20, "logo");
        assertEquals(40, fitted.width()); assertEquals(20, fitted.height());
        assertEquals("logo", fitted.altText());
        assertThrows(IllegalArgumentException.class, () -> InlineImage.atWidth(resource, Double.NaN, "logo"));
        assertThrows(IllegalArgumentException.class, () -> InlineImage.fit(resource, 10, 0, "logo"));
        assertThrows(IllegalArgumentException.class, () -> InlineImage.fit(resource, 14401, 20, "logo"));
    }

    @Test void imageLoadingObservesSynchronousCancellationWithoutClosingBorrowedInput() {
        var cancelled = new AtomicBoolean(true); var input = new BorrowedInput(new byte[32]);
        var options = ExecutionOptions.builder().cancelled(cancelled::get).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> Images.load(input)));
        assertEquals(32, input.available()); assertFalse(input.closed);
    }

    @Test void engineFontSetSelectsItsRealBoldFont() throws IOException {
        FontGroup regular; FontGroup bold;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            regular = FontGroup.strict(TrueTypeFontLoader.load(input));
        }
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Bold.ttf")) {
            bold = FontGroup.strict(TrueTypeFontLoader.load(input));
        }
        var fonts = FontSet.builder(regular).bold(bold).build();
        var engine = DocumentEngine.builder(fonts).build();
        var document = Document.builder().add(Paragraph.mixed("p", List.of(
                new InlineText("Regular ", InlineStyle.of(12)),
                new InlineText("BOLD", new InlineStyle("bold", 12, 0, 0, false, false))), ParagraphFormat.of(12))).build();
        var commands = engine.layout(document).display().pages().getFirst().commands();
        var runs = commands.stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
        assertEquals(bold.fonts().getFirst().data().id(), runs.stream().filter(run -> run.run().text().equals("BOLD")).findFirst().orElseThrow().run().font().id());
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).fontRole("bold", regular));
    }
}
