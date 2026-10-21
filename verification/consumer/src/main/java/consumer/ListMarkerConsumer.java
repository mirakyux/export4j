// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Verifies marker-only roles through the public artifact boundary. */
final class ListMarkerConsumer {
    private ListMarkerConsumer() { }
    static void verify(FontFace regular, Path output) throws IOException {
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var format = ParagraphFormat.builder().fontSize(10).lineHeight(20).spaceBefore(0).spaceAfter(0).build();
        var source = Document.builder().pageSettings(PageSettings.builder().size(320, 220).margins(18).build())
                .add(NumberedItem.builder().id("number").listId("check").text("CALLER-NUMBER").format(format)
                        .markerStyle(style -> style.fontRole("bold").fontSize(12).rgb(0x224488).underline(true)).build())
                .add(BulletItem.builder().id("bullet").marker("*").text("CALLER-BULLET").format(format)
                        .markerStyle(style -> style.fontRole("bold").rgb(0x336633)).build()).build();
        var result = DocumentEngine.builder(FontGroup.strict(List.of(regular)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).build()
                .export(source).backend(new PdfBackend()).to(output.resolve("consumer-list-markers.pdf"));
        if (result.display().pages().size() != 1 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Public list marker APIs differ");
        }
        try (var target = Files.newOutputStream(output.resolve("consumer-list-markers.docx"))) { new DocxAdapter().write(source, target); }
    }
}
