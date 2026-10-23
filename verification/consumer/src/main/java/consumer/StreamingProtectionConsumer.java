// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import com.mirakyux.export4j.backend.pdf.PdfArchival;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfEncryption;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

final class StreamingProtectionConsumer {
    private StreamingProtectionConsumer() { }
    static void verify(DocumentEngine engine, FontData anchor, Path output) throws IOException {
        var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
        var table = StreamingTable.builder("caller-protection").columns(180).cellFormat(format).header("CALLER HEADER").build();
        for (boolean archival : List.of(false, true)) {
            var options = archival ? PdfOptions.DEFAULT.withArchival(new PdfArchival(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), anchor))
                    : PdfOptions.DEFAULT.withEncryption(new PdfEncryption("fixture-owner".toCharArray(), "fixture-reader".toCharArray(), PdfEncryption.Permissions.READ_ONLY));
            var summary = engine.export(table, () -> new TableRowSource() {
                private boolean emitted;
                @Override public Optional<TableRow> next() {
                    if (emitted) { return Optional.empty(); }
                    emitted = true; return Optional.of(new TableRow(List.of(new TableCell("CALLER PROTECTED", format))));
                }
            }).backend(PdfBackend.builder().options(options).build())
                    .to(output.resolve(archival ? "consumer-streaming-archive.pdf" : "consumer-streaming-encrypted.pdf"));
            if (summary.pages() != 1 || summary.bodyRows() != 1) { throw new AssertionError("protected caller counts"); }
        }
    }
}
