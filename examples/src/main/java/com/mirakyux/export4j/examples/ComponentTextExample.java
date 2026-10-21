// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.FontRoleScope;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.InlineStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Self-authored report demonstrating standard rich page regions and reusable font-role scopes. */
public final class ComponentTextExample {
    private static final Path OUTPUT = Path.of("examples/build/component-text");
    private ComponentTextExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var regular = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var bold = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Bold.ttf")));
        var italic = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Italic.ttf")));
        var header = RichTextComponent.builder().lineHeight(20)
                .add("REPORT ", ink("bold", 14, 0x224488))
                .add("DETAIL", ink("italic", 10, 0x883366))
                .add(" H", ink("", 10, 0))
                .add("2", InlineStyle.builder().fontSize(8).baselineShift(3).build())
                .add("O\nKEY ", ink("", 10, 0))
                .add("UNDERLINE", InlineStyle.builder().fontRole("bold").fontSize(10).rgb(0x884422).underline(true).build())
                .build();
        var foreground = RichTextComponent.builder().lineHeight(18)
                .add("STATUS ", ink("bold", 10, 0x224488))
                .add("REVIEWED", InlineStyle.builder().fontRole("italic").fontSize(10).rgb(0x336633).strikeThrough(true).build())
                .build();
        var template = PageTemplate.builder().header(band -> band.height(44).gap(10).content(header))
                .footer(band -> band.height(24).gap(8).content(FontRoleScope.builder(
                        PageNumberComponent.builder("PAGE {page} OF {pages}").fontSize(10).lineHeight(16).build())
                        .fontRole("bold").build()))
                .background(new Placement(FontRoleScope.builder(TextComponent.builder("BRAND").fontSize(10).lineHeight(16).build())
                        .fontRole("bold").build(), new Position.Absolute(new Rect(20, 225, 360, 16))))
                .foreground(new Placement(foreground, new Position.Absolute(new Rect(20, 288, 360, 18))))
                .build();
        var document = Documents.builder().title("Component text authoring")
                .pageSettings(page -> page.size(400, 380).margins(20))
                .bodyFormat(format -> format.fontSize(10).lineHeight(18))
                .paragraph("BODY-FIRST Regular document text")
                .component(FontRoleScope.builder(FlowText.builder("ROLE-DEFAULT-BOLD").build()).fontRole("bold").build())
                .component(FontRoleScope.builder(RichTextComponent.builder().lineHeight(18)
                        .add("RICH-DEFAULT-BOLD ", ink("", 10, 0x224488))
                        .add("RICH-EXPLICIT-ITALIC", ink("italic", 10, 0x883366)).build()).fontRole("bold").build())
                .pageBreak().paragraph("BODY-SECOND Regular document text")
                .component(FontRoleScope.builder(TextComponent.builder("ROLE-FIXED-BOLD").build()).fontRole("bold").build())
                .build();
        var result = DocumentEngine.builder(regular).fontRole("bold", bold).fontRole("italic", italic)
                .pageTemplate(template).build().export(document).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (result.display().pages().size() != 2 || !result.contextualDiagnostics().isEmpty()) {
            throw new IllegalStateException("Component text fixture differs");
        }
        try (var target = Files.newOutputStream(OUTPUT.resolve("report.svg"))) {
            new SvgBackend().write(result.display(), target);
        }
        System.out.println("Standard rich text and named font scopes: 2 pages");
    }

    private static InlineStyle ink(String role, double size, int rgb) {
        return InlineStyle.builder().fontRole(role).fontSize(size).rgb(rgb).build();
    }
}
