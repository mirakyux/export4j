// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FontRoleScope;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.InlineStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercises public component text APIs from isolated Maven artifacts. */
final class ComponentTextConsumer {
    private ComponentTextConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var italic = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Italic.ttf")));
        var template = PageTemplate.builder()
                .header(band -> band.height(24).gap(6).content(RichTextComponent.builder().lineHeight(18)
                        .add("CALLER-HEADER ", ink("bold", 0x224488)).add("DETAIL", ink("italic", 0x883366)).build()))
                .footer(band -> band.height(20).gap(6).content(FontRoleScope.builder(
                        PageNumberComponent.builder("PAGE {page} OF {pages}").fontSize(10).lineHeight(16).build())
                        .fontRole("bold").build())).build();
        var document = Documents.builder().pageSettings(page -> page.size(360, 240).margins(18))
                .bodyFormat(format -> format.fontSize(10).lineHeight(18))
                .paragraph("CALLER-REGULAR")
                .component(FontRoleScope.builder(RichTextComponent.builder().lineHeight(18)
                        .add("CALLER-DEFAULT-ROLE ", ink("", 0x224488))
                        .add("ITALIC", ink("italic", 0x883366)).build()).fontRole("bold").build()).build();
        var result = DocumentEngine.builder(FontGroup.strict(List.of(font)))
                .fontRole("bold", FontGroup.strict(List.of(bold))).fontRole("italic", FontGroup.strict(List.of(italic)))
                .pageTemplate(template).build().export(document).backend(new PdfBackend()).to(output.resolve("consumer-component-text.pdf"));
        if (result.display().pages().size() != 1 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Published component text APIs differ");
        }
        try (var target = Files.newOutputStream(output.resolve("consumer-component-text.svg"))) {
            new SvgBackend().write(result.display(), target);
        }
    }

    private static InlineStyle ink(String role, int rgb) {
        return InlineStyle.builder().fontRole(role).fontSize(10).rgb(rgb).build();
    }
}
