// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FontScope;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.StyleScope;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
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

/** Self-authored fixed-region fixture with explicit physical fonts and final section numbering. */
public final class PageRegionFontsExample {
    private static final Path OUTPUT = Path.of("examples/build/page-region-fonts");
    private static final PageSettings PAGE = PageSettings.builder().size(420, 520).margins(28).build();
    private static final RgbColor HEADER = new RgbColor(0.12, 0.24, 0.48);
    private static final RgbColor FOOTER = new RgbColor(0.16, 0.40, 0.24);
    private static final RgbColor BACKGROUND = new RgbColor(0.56, 0.32, 0.12);
    private static final RgbColor FOREGROUND = new RgbColor(0.48, 0.16, 0.40);

    private PageRegionFontsExample() { }

    /** Generates every page from registered local resources, without font discovery or remote I/O. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var brand = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")));
        var engine = DocumentEngine.builder(regular).fontRole("brand", brand)
                .pageTemplate(template(regular)).build();
        var body = Documents.builder().title("Fixed page regions and font roles")
                .author("Export4j synthetic example").pageSettings(PAGE)
                .bodyFormat(format -> format.fontSize(11).lineHeight(18).spaceAfter(8))
                .paragraph("BODY-FIRST Regular body text starts below the reserved header.")
                .paragraph("Both font and style scopes preserve the named brand role.")
                .pageBreak()
                .paragraph("BODY-SECOND A new physical page continues section numbering.")
                .paragraph("All four fixed regions receive the final physical index and page count.")
                .build();
        var document = SectionedDocument.builder().metadata(body.metadata())
                .section(Section.builder("review").pageSettings(PAGE).firstPageNumber(7)
                        .blocks(body.blocks()).build()).build();
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 2 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Fixed-region fixture has unexpected pagination or diagnostics");
        }
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.svg"))) {
            new SvgBackend().write(result.display(), output);
        }
        System.out.println("Fixed page-region font fixture: 2 pages, section labels 7 and 8");
    }

    private static PageTemplate template(FontGroup regular) {
        var number = PageNumberComponent.builder("PAGE {page} OF {pages}")
                .fontSize(10).lineHeight(16).build();
        return PageTemplate.builder()
                .header(PageBand.builder().height(32).gap(12)
                        .content(scoped("REGION-HEADER", HEADER, regular)).build())
                .footer(PageBand.builder().height(40).gap(12)
                        .add(new Placement(scoped("REGION-FOOTER", FOOTER, regular),
                                new Position.Relative(Position.Anchor.TOP_LEFT, 364, 16, 0, 0)))
                        .add(new Placement(number,
                                new Position.Relative(Position.Anchor.TOP_LEFT, 364, 16, 0, 20))).build())
                .background(new Placement(scoped("REGION-BACKGROUND", BACKGROUND, regular),
                        new Position.Absolute(new Rect(28, 220, 364, 16))))
                .foreground(new Placement(scoped("REGION-FOREGROUND", FOREGROUND, regular),
                        new Position.Absolute(new Rect(28, 392, 364, 16))))
                .build();
    }

    private static Component scoped(String marker, RgbColor color, FontGroup regular) {
        // The explicit default stays regular; the child selects the separately registered role.
        return StyleScope.builder(FontScope.builder(new RoleText(marker)).fonts(regular).build())
                .fontSize(10).lineHeight(16).color(color).build();
    }

    /** Immutable, thread-safe trusted component; borrows fonts and writes no output during layout. */
    private record RoleText(String marker) implements Component {
        @Override
        public ComponentLayout layout(ComponentContext context) {
            var brand = context.fontRoles().get("brand");
            if (brand == null) {
                throw new IllegalStateException("Fixed page region lost its registered brand font role");
            }
            if (context.pageCount() != 2 || context.pageIndex() > 1
                    || context.displayPageNumber() != 7 + context.pageIndex()) {
                throw new IllegalStateException("Fixed page region did not receive final section numbering");
            }
            String text = marker + " I" + context.pageIndex() + " N" + context.displayPageNumber()
                    + " TOTAL" + context.pageCount();
            return TextComponent.builder(text).build().layout(context.withFonts(brand));
        }
    }
}
