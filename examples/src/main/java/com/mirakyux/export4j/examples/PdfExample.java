// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.PathBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

/** Runnable, self-contained precision-drawing example using only generated RGB image data. */
public final class PdfExample {
    private PdfExample() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one output PDF path");
        }
        Path output = Path.of(args.length == 0 ? "examples/build/export4j-example.pdf" : args[0])
                .toAbsolutePath();
        Files.createDirectories(output.getParent());
        try (OutputStream stream = Files.newOutputStream(output)) {
            new PdfBackend().write(createDocument(), stream);
        }
        System.out.println("Generated: " + output);
    }

    private static DisplayDocument createDocument() {
        RgbColor ink = new RgbColor(0.12, 0.18, 0.27);
        RgbColor blue = new RgbColor(0.15, 0.35, 0.75);
        DisplayListBuilder first = new DisplayListBuilder(PageSize.A4);
        first.fillRect(new Rect(0, 0, PageSize.A4.width(), PageSize.A4.height()), RgbColor.WHITE)
                .fillRect(new Rect(36, 36, 523, 8), blue)
                .drawText("Export4j", new Point(36, 86),
                        new TextStyle(FontFamily.SANS_SERIF, 30, ink))
                .drawText("Precision drawing / PDF milestone", new Point(36, 116),
                        new TextStyle(FontFamily.SANS_SERIF, 14, ink))
                .drawText("Coordinates: points, top-left origin, Y downward.", new Point(36, 150),
                        new TextStyle(FontFamily.MONOSPACE, 10, ink))
                .fillPath(new PathBuilder().moveTo(36, 210).lineTo(96, 170).lineTo(156, 210)
                        .close().build(), blue)
                .strokePath(new PathBuilder().moveTo(36, 245)
                        .curveTo(90, 175, 125, 310, 180, 245).build(), ink, 2)
                .drawImage(sampleImage(), new Rect(240, 175, 160, 100))
                .drawText("Generated RGB image", new Point(240, 295),
                        new TextStyle(FontFamily.SANS_SERIF, 11, ink))
                .save()
                .transform(AffineTransform.translation(36, 340))
                .clip(new Rect(0, 0, 180, 48))
                .fillRect(new Rect(-15, -15, 220, 90), blue)
                .restore()
                .drawText("Scoped translation and clipping", new Point(36, 410),
                        new TextStyle(FontFamily.SANS_SERIF, 11, ink))
                .drawText("1 / 2", new Point(36, 800),
                        new TextStyle(FontFamily.SANS_SERIF, 10, ink));

        DisplayListBuilder second = new DisplayListBuilder(PageSize.LETTER);
        second.drawText("Explicit second page", new Point(36, 70),
                        new TextStyle(FontFamily.SERIF, 24, ink))
                .drawText("Page size: US Letter (612 x 792 pt)", new Point(36, 105),
                        new TextStyle(FontFamily.MONOSPACE, 11, ink))
                .drawText("Automatic layout, font embedding and DOCX follow later.",
                        new Point(36, 135), new TextStyle(FontFamily.SANS_SERIF, 11, ink));
        List<DisplayPage> pages = List.of(first.build(), second.build());
        return new DisplayDocument(new DocumentMetadata("Export4j PDF example",
                "Export4j contributors"), pages);
    }

    private static RgbImage sampleImage() {
        int width = 80;
        int height = 50;
        byte[] pixels = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 3;
                pixels[offset] = (byte) (x * 255 / (width - 1));
                pixels[offset + 1] = (byte) (y * 255 / (height - 1));
                pixels[offset + 2] = (byte) 160;
            }
        }
        return new RgbImage(width, height, pixels);
    }
}
