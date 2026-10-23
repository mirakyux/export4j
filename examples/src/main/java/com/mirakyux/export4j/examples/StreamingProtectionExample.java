// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.backend.pdf.PdfArchival;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfEncryption;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

/** Final-file encryption and independently validated static archival output from a one-pass source. */
public final class StreamingProtectionExample {
    private StreamingProtectionExample() { }
    public static void main(String[] args) throws IOException {
        var output = Path.of("examples/build/streaming-protection"); Files.createDirectories(output);
        var font = AuthoringExample.face("LiberationSans-Regular.ttf");
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font))).build();
        var format = CellFormat.builder().fontSize(10).lineHeight(18).build();
        var spec = StreamingTable.builder("protected").columns(230).padding(3).cellFormat(format)
                .pageSettings(PageSettings.builder().size(260, 150).margins(15).build()).header("PROTECTION HEADER").build();
        var raster = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        var encoded = new ByteArrayOutputStream();
        try { raster.setRGB(0, 0, 0x225588); raster.setRGB(1, 0, 0xddaa44); ImageIO.write(raster, "png", encoded); }
        finally { raster.flush(); }
        var image = ImageResource.load(encoded.toByteArray());
        for (boolean archival : List.of(false, true)) {
            var options = PdfOptions.storage(PdfOptions.Storage.TEMP_FILE);
            options = archival ? options.withArchival(new PdfArchival(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), font.data()))
                    : options.withEncryption(new PdfEncryption("fixture-owner".toCharArray(), "fixture-reader".toCharArray(), PdfEncryption.Permissions.READ_ONLY));
            var calls = new int[1]; var closes = new int[1];
            var summary = engine.export(spec, () -> new TableRowSource() {
                @Override public Optional<TableRow> next() {
                    if (++calls[0] > 7) { return Optional.empty(); }
                    return Optional.of(new TableRow(List.of(TableCell.mixed(List.of(
                            new InlineText("PROTECTED-" + calls[0] + " ", InlineStyle.of(10), archival ? "" : "https://example.com/protected/" + calls[0]),
                            new InlineImage(image, 12, 6, 0, "Sample")), format))));
                }
                @Override public void close() { closes[0]++; }
            }).backend(PdfBackend.builder().options(options).build()).to(output.resolve(archival ? "archive.pdf" : "encrypted.pdf"));
            if (summary.pages() != 2 || calls[0] != 8 || closes[0] != 1) { throw new AssertionError("protected streaming lifecycle"); }
        }
    }
}
