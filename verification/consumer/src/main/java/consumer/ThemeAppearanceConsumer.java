// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.DocumentTheme;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ThemeAppearanceConsumer {
    private ThemeAppearanceConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var theme = DocumentTheme.builder().fontRole("brand").rgb(0x224488)
                .bodyFormat(format -> format.fontSize(11).lineHeight(22)).build();
        var document = Documents.builder().theme(theme).pageSettings(page -> page.size(300, 200).margins(20))
                .paragraph("CALLER-THEME").component(FlowText.builder("CALLER-COMPONENT").build())
                .theme(theme.toBuilder().bodyFontRole("").bodyRgb(0).build()).paragraph("CALLER-RESET").build();
        DocumentEngine.builder(FontGroup.strict(List.of(font))).fontRole("brand", FontGroup.strict(List.of(bold)))
                .build().write(document, new PdfBackend(), output.resolve("consumer-theme-appearance.pdf"));
    }
}
