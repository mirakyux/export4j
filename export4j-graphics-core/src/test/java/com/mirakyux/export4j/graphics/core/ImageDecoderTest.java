// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageDecoderTest {
    @Test
    void pngRetainsStraightAlphaAndDefensivelyCopiesArrays() throws IOException {
        var input = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        input.setRGB(0, 0, 0x80ff0022);
        input.setRGB(1, 0, 0x0000ffff);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(input, "png", bytes);
        var decoded = ImageDecoder.decode(bytes.toByteArray(), 1024, 2);
        assertEquals(128, Byte.toUnsignedInt(decoded.alpha()[0]));
        assertEquals(255, Byte.toUnsignedInt(decoded.pixels()[0]));
        decoded.alpha()[0] = 0;
        assertEquals(128, Byte.toUnsignedInt(decoded.alpha()[0]));
        assertThrows(IOException.class, () -> ImageDecoder.decode(bytes.toByteArray(), 1024, 1));
        assertThrows(IllegalArgumentException.class, () -> ImageDecoder.decode(bytes.toByteArray(), 1, 2));
    }

    @Test
    void jpegDecodesOpaquePixelsAndInvalidDataFails() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        var decoded = ImageDecoder.decode(bytes.toByteArray(), 4096, 6);
        assertEquals(3, decoded.width());
        assertEquals(2, decoded.height());
        assertEquals(0, decoded.alpha().length);
        assertThrows(IOException.class, () -> ImageDecoder.decode(new byte[] {1, 2, 3}, 100, 100));
    }
}
