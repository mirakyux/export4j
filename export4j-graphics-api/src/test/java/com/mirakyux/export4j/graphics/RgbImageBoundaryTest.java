// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RgbImageBoundaryTest {
    private enum Fixture {
        INDEXED_OPAQUE_PNG, INDEXED_ALPHA_PNG, BINARY_PNG,
        GRAY_8_PNG, GRAY_16_PNG, GRAY_JPEG, PREMULTIPLIED_ALPHA_PNG
    }

    private record Samples(int width, int height, byte[] pixels, byte[] alpha) { }

    @ParameterizedTest
    @EnumSource(Fixture.class)
    void expandsEachEncodingLikeAnIndependentWholeRasterJdkRead(Fixture fixture) throws IOException {
        byte[] encoded = fixture(fixture);
        Samples expected = reference(encoded);
        var source = ImageResource.load(encoded);
        var rgbFirst = RgbImage.from(source);
        var alphaFirst = RgbImage.from(source);
        String identity = rgbFirst.id();
        Arrays.fill(encoded, (byte) 0);

        assertEquals(expected.width(), rgbFirst.width());
        assertEquals(expected.height(), rgbFirst.height());
        assertArrayEquals(expected.pixels(), rgbFirst.pixels());
        assertArrayEquals(expected.alpha(), rgbFirst.alpha());
        assertArrayEquals(expected.alpha(), alphaFirst.alpha());
        assertArrayEquals(expected.pixels(), alphaFirst.pixels());

        byte[] returnedPixels = rgbFirst.pixels();
        byte[] returnedAlpha = rgbFirst.alpha();
        Arrays.fill(returnedPixels, (byte) 0);
        Arrays.fill(returnedAlpha, (byte) 0);
        byte[] returnedSource = rgbFirst.encodedSource().orElseThrow().bytes();
        Arrays.fill(returnedSource, (byte) 0);
        assertArrayEquals(expected.pixels(), rgbFirst.pixels());
        assertArrayEquals(expected.alpha(), rgbFirst.alpha());
        assertArrayEquals(expected.pixels(), alphaFirst.pixels());
        assertEquals(identity, rgbFirst.id());
        assertEquals(identity, RgbImage.from(ImageResource.load(source.bytes())).id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledPaletteExpansionRetriesWithEveryRgbAndAlphaSampleIntact(boolean readAlphaFirst) throws IOException {
        byte[] encoded = fixture(Fixture.INDEXED_ALPHA_PNG);
        Samples expected = reference(encoded);
        var image = RgbImage.from(ImageResource.load(encoded));
        var checkpoints = new AtomicInteger();
        var options = ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 6).build();

        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> readAlphaFirst ? image.alpha() : image.pixels()));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertArrayEquals(expected.alpha(), image.alpha());
        assertArrayEquals(expected.pixels(), image.pixels());
    }

    @Test
    @Timeout(45)
    void concurrentAlphaAndRgbInitializationPublishesOneCompletePaletteExpansion() throws Exception {
        byte[] encoded = fixture(Fixture.INDEXED_ALPHA_PNG);
        Samples expected = reference(encoded);
        var image = RgbImage.from(ImageResource.load(encoded));
        var gate = new CyclicBarrier(8);
        var results = new ArrayList<Future<Samples>>();
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (int worker = 0; worker < 8; worker++) {
                boolean readAlphaFirst = (worker & 1) == 0;
                results.add(executor.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    byte[] alpha = readAlphaFirst ? image.alpha() : null;
                    byte[] pixels = image.pixels();
                    if (alpha == null) { alpha = image.alpha(); }
                    return new Samples(image.width(), image.height(), pixels, alpha);
                }));
            }
            Samples first = results.getFirst().get(15, TimeUnit.SECONDS);
            for (var future : results) {
                Samples actual = future.get(15, TimeUnit.SECONDS);
                assertArrayEquals(expected.pixels(), actual.pixels());
                assertArrayEquals(expected.alpha(), actual.alpha());
                if (actual != first) {
                    assertNotSame(first.pixels(), actual.pixels());
                    assertNotSame(first.alpha(), actual.alpha());
                }
            }
            Arrays.fill(first.pixels(), (byte) 0);
            Arrays.fill(first.alpha(), (byte) 0);
            assertArrayEquals(expected.pixels(), image.pixels());
            assertArrayEquals(expected.alpha(), image.alpha());
            assertArrayEquals(expected.pixels(), results.get(1).get(15, TimeUnit.SECONDS).pixels());
            assertArrayEquals(expected.alpha(), results.get(1).get(15, TimeUnit.SECONDS).alpha());
        } finally {
            for (var result : results) { result.cancel(true); }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "image readers did not terminate");
        }
    }

    private static byte[] fixture(Fixture fixture) throws IOException {
        int width = 17;
        int height = 13;
        BufferedImage image;
        if (fixture == Fixture.INDEXED_OPAQUE_PNG || fixture == Fixture.INDEXED_ALPHA_PNG) {
            byte[] red = {(byte) 255, 0, 0, 35};
            byte[] green = {0, (byte) 128, (byte) 255, (byte) 211};
            byte[] blue = {64, (byte) 255, 0, 87};
            byte[] alpha = fixture == Fixture.INDEXED_ALPHA_PNG
                    ? new byte[]{0, 64, (byte) 128, (byte) 255}
                    : new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
            image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED,
                    new IndexColorModel(2, 4, red, green, blue, alpha));
        } else {
            int type = switch (fixture) {
                case BINARY_PNG -> BufferedImage.TYPE_BYTE_BINARY;
                case GRAY_8_PNG, GRAY_JPEG -> BufferedImage.TYPE_BYTE_GRAY;
                case GRAY_16_PNG -> BufferedImage.TYPE_USHORT_GRAY;
                case PREMULTIPLIED_ALPHA_PNG -> BufferedImage.TYPE_INT_ARGB_PRE;
                default -> throw new IllegalArgumentException("palette fixture already constructed");
            };
            image = new BufferedImage(width, height, type);
        }
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    switch (fixture) {
                        case INDEXED_OPAQUE_PNG, INDEXED_ALPHA_PNG ->
                                image.getRaster().setSample(x, y, 0, (x + 3 * y) % 4);
                        case BINARY_PNG -> image.getRaster().setSample(x, y, 0, (x + y) & 1);
                        case GRAY_8_PNG, GRAY_JPEG ->
                                image.getRaster().setSample(x, y, 0, (x * 17 + y * 29) & 255);
                        case GRAY_16_PNG ->
                                image.getRaster().setSample(x, y, 0, (x * 4093 + y * 257) & 65535);
                        case PREMULTIPLIED_ALPHA_PNG -> image.setRGB(x, y,
                                ((x * 19 + y * 11) & 255) << 24 | 0x001ec896);
                    }
                }
            }
            String format = fixture == Fixture.GRAY_JPEG ? "jpeg" : "png";
            var writer = ImageIO.getImageWritersByFormatName(format).next();
            var bytes = new ByteArrayOutputStream();
            try (var output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
            } finally { writer.dispose(); }
            return bytes.toByteArray();
        } finally { image.flush(); }
    }

    private static Samples reference(byte[] encoded) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw new IOException("fixture decoder unavailable"); }
            var reader = readers.next();
            BufferedImage decoded;
            try {
                reader.setInput(input, true, true);
                decoded = reader.read(0);
            } finally { reader.dispose(); }
            if (decoded == null) { throw new IOException("fixture decoder unavailable"); }
            try {
                int width = decoded.getWidth();
                int height = decoded.getHeight();
                // The reference asks the JDK for the entire raster, independently of RgbImage's row loop.
                int[] argb = decoded.getRGB(0, 0, width, height, null, 0, width);
                byte[] pixels = new byte[argb.length * 3];
                byte[] alpha = decoded.getColorModel().hasAlpha() ? new byte[argb.length] : new byte[0];
                for (int index = 0; index < argb.length; index++) {
                    pixels[index * 3] = (byte) (argb[index] >>> 16);
                    pixels[index * 3 + 1] = (byte) (argb[index] >>> 8);
                    pixels[index * 3 + 2] = (byte) argb[index];
                    if (alpha.length != 0) { alpha[index] = (byte) (argb[index] >>> 24); }
                }
                return new Samples(width, height, pixels, alpha);
            } finally { decoded.flush(); }
        }
    }
}
