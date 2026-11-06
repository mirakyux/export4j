// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RgbImageSourceTest {
    @Test void keepsValidatedSourceAndStraightAlphaWithoutBorrowingMutableBytes() throws IOException {
        var raster = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        raster.setRGB(0, 0, 0x80ff0022); raster.setRGB(1, 0, 0x0000ffff);
        var bytes = encoded(raster, "png");
        var source = ImageResource.load(bytes);
        var image = RgbImage.from(source);
        String id = image.id();
        bytes[0] = 0;
        assertSame(source, image.encodedSource().orElseThrow());
        assertEquals(2, image.width()); assertEquals(1, image.height());
        assertArrayEquals(new byte[]{(byte) 255, 0, 0x22, 0, (byte) 255, (byte) 255}, image.pixels());
        assertArrayEquals(new byte[]{(byte) 128, 0}, image.alpha());
        image.pixels()[0] = 0; image.alpha()[0] = 0;
        assertEquals(255, Byte.toUnsignedInt(image.pixels()[0]));
        assertEquals(128, Byte.toUnsignedInt(image.alpha()[0]));
        assertEquals(id, image.id());
        assertEquals(id, RgbImage.from(ImageResource.load(source.bytes())).id());
    }

    @Test void rawIdentityIncludesDimensionsAndAlphaAndKeepsArrayOwnership() {
        byte[] pixels = new byte[]{1, 2, 3, 4, 5, 6};
        var first = new RgbImage(2, 1, pixels);
        var equal = new RgbImage(2, 1, pixels);
        var tall = new RgbImage(1, 2, pixels);
        var translucent = new RgbImage(2, 1, pixels, new byte[]{127, 127});
        String id = first.id(); pixels[0] = 99;
        assertEquals(id, equal.id());
        assertNotEquals(id, tall.id()); assertNotEquals(id, translucent.id());
        assertEquals(1, first.pixels()[0]);
    }

    @Test void cancelledDecodePublishesNoPartialSamplesAndRestoresTheExecutionScope() throws IOException {
        var image = RgbImage.from(ImageResource.load(encoded(new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), "png")));
        var checkpoints = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 5).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> image.pixels()));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals(1200, image.pixels().length);
        assertEquals(400, image.alpha().length);
    }

    @Test @Timeout(30) void concurrentPixelReadsAgreeAndReturnIndependentSnapshots() throws Exception {
        var raster = new BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB);
        raster.setRGB(2, 1, 0xff123456);
        var image = RgbImage.from(ImageResource.load(encoded(raster, "jpeg")));
        var gate = new CyclicBarrier(8);
        var futures = new ArrayList<Future<byte[]>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return image.pixels(); }));
            }
            byte[] first = futures.getFirst().get(15, TimeUnit.SECONDS);
            for (var result : futures) { assertArrayEquals(first, result.get(15, TimeUnit.SECONDS)); }
        }
        assertEquals(0, image.alpha().length);
    }

    private static byte[] encoded(BufferedImage image, String format) throws IOException {
        try {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) { throw new IOException("fixture encoder missing"); }
            return output.toByteArray();
        } finally { image.flush(); }
    }
}
