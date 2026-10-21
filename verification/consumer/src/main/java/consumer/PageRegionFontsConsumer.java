// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FontScope;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.StyleScope;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Verifies named fixed-region fonts using only isolated Maven artifacts. */
final class PageRegionFontsConsumer {
    private PageRegionFontsConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var regular = FontGroup.strict(List.of(font));
        var bold = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Bold.ttf")));
        var page = PageSettings.builder().size(320, 260).margins(18).build();
        var template = PageTemplate.builder()
                .header(band -> band.height(28).gap(4).content(region("HEADER", regular)))
                .footer(PageBand.builder().height(40).gap(6)
                        .add(new Placement(region("FOOTER", regular), new Position.Relative(Position.Anchor.TOP_LEFT, 284, 16, 0, 0)))
                        .add(new Placement(PageNumberComponent.builder("PAGE {page} OF {pages}").fontSize(10).lineHeight(16).build(),
                                new Position.Relative(Position.Anchor.TOP_LEFT, 284, 16, 0, 20))).build())
                .background(new Placement(region("BACKGROUND", regular), new Position.Absolute(new Rect(18, 120, 284, 16))))
                .foreground(new Placement(region("FOREGROUND", regular), new Position.Absolute(new Rect(18, 166, 284, 16))))
                .build();
        var body = Documents.builder().pageSettings(page).bodyFormat(format -> format.fontSize(11).lineHeight(18))
                .paragraph("BODY-CALLER-FIRST").pageBreak().paragraph("BODY-CALLER-SECOND").build();
        var document = SectionedDocument.builder().metadata(body.metadata())
                .section(Section.builder("caller").pageSettings(page).firstPageNumber(3).blocks(body.blocks()).build()).build();
        var result = DocumentEngine.builder(regular).fontRole("brand", FontGroup.strict(List.of(bold))).pageTemplate(template)
                .build().export(document).backend(new PdfBackend()).to(output.resolve("consumer-page-region-fonts.pdf"));
        if (result.display().pages().size() != 2 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Published fixed-region fonts differ");
        }
        try (var target = Files.newOutputStream(output.resolve("consumer-page-region-fonts.svg"))) {
            new SvgBackend().write(result.display(), target);
        }
    }

    private static Component region(String name, FontGroup regular) {
        Component selected = context -> {
            var brand = context.fontRoles().get("brand");
            if (brand == null || context.pageCount() != 2 || context.displayPageNumber() != context.pageIndex() + 3) {
                throw new IllegalStateException("Published page context lost its roles or numbering");
            }
            return TextComponent.builder("CALLER-" + name + " I" + context.pageIndex() + " N" + context.displayPageNumber()
                    + " TOTAL" + context.pageCount()).build().layout(context.withFonts(brand));
        };
        return StyleScope.builder(new FontScope(selected, regular)).fontSize(10).lineHeight(16)
                .color(new RgbColor(.12, .24, .48)).build();
    }
}
