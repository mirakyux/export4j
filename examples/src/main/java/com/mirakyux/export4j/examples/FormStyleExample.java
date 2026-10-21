// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Explicit reusable appearances for genuinely editable fields, with unchanged legacy defaults. */
public final class FormStyleExample {
    private static final Path OUTPUT = Path.of("examples/build/form-styles");
    private static final RgbColor BLUE = new RgbColor(.1, .2, .6);
    private static final RgbColor AMBER = new RgbColor(.6, .25, .1);
    private static final RgbColor PALE_BLUE = new RgbColor(.9, .95, 1);
    private static final RgbColor PALE_AMBER = new RgbColor(1, .96, .86);
    private static final RgbColor UNDERLAY = new RgbColor(.88, .95, .91);

    private FormStyleExample() { }

    /** Writes an original three-page form; no user documents, system font lookup or remote resources. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var regular = AuthoringExample.face("LiberationSans-Regular.ttf");
        var chinese = AuthoringExample.face("LXGWWenKaiTC-Regular.ttf");
        var blueVisual = FormStyle.builder().color(BLUE).background(PALE_BLUE).border(1.5, BLUE).build();
        var amberVisual = FormStyle.builder().color(AMBER).background(PALE_AMBER).border(2, AMBER).build();
        var fields = new ArrayList<Navigation.Field>();
        fields.add(Navigation.TextField.builder().target(target("ascii-area", 150, 0, 280, 28))
                .name("ascii_center").value("Centered review").style(blueVisual.toBuilder()
                        .fontSize(14).alignment(FormStyle.Alignment.CENTER).build()).build());
        fields.add(Navigation.TextField.builder().target(target("ascii-area", 150, 44, 280, 28))
                .name("ascii_reset").value("Explicit black and transparent")
                .style(amberVisual.toBuilder().fontSize(12).color(RgbColor.BLACK).noBackground().noBorder()
                        .alignment(FormStyle.Alignment.RIGHT).build()).build());
        fields.add(Navigation.ChoiceField.builder().target(target("ascii-area", 150, 88, 280, 28))
                .name("ascii_choice").options(List.of("Draft", "Review", "Accepted")).value("Review")
                .style(amberVisual.toBuilder().fontSize(12).alignment(FormStyle.Alignment.RIGHT).build()).build());
        fields.add(Navigation.CheckBox.builder().target(target("ascii-area", 150, 132, 24, 24))
                .name("blue_check").checked(true).style(blueVisual).build());
        fields.add(Navigation.RadioGroup.builder().target(target("ascii-area", 150, 176, 200, 24))
                .name("amber_radio").options(List.of("Draft", "Final")).value("Final").style(amberVisual).build());
        fields.add(Navigation.CheckBox.builder().target(target("ascii-area", 150, 220, 8, 8))
                .name("small_check").checked(true).style(style -> style.color(BLUE).noBackground().noBorder()).build());
        fields.add(Navigation.RadioGroup.builder().target(target("ascii-area", 150, 254, 80, 8))
                .name("small_radio").options(List.of("A", "B")).value("A")
                .style(style -> style.color(AMBER).background(PALE_AMBER).adaptiveBorder(6, AMBER)).build());
        fields.add(Navigation.TextField.builder().target(target("unicode-area", 150, 0, 280, 30))
                .name("unicode_name").value("完整字体：初始值").fonts(List.of(chinese.data()))
                .style(blueVisual.toBuilder().fontSize(13).alignment(FormStyle.Alignment.LEFT).build()).build());
        fields.add(Navigation.ChoiceField.builder().target(target("unicode-area", 150, 44, 280, 28))
                .name("unicode_choice").options(List.of("草稿", "审核", "完成")).value("审核").fonts(List.of(chinese.data()))
                .style(amberVisual.toBuilder().fontSize(12).alignment(FormStyle.Alignment.CENTER).build()).build());
        fields.add(Navigation.TextField.builder().target(target("unicode-area", 150, 88, 280, 50))
                .name("unicode_notes").value("可编辑中文备注\n第二行。").multiline(true).fonts(List.of(chinese.data()))
                .style(style -> style.fontSize(12).color(RgbColor.BLACK).noBackground().border(0, BLUE)
                        .alignment(FormStyle.Alignment.LEFT)).build());
        for (boolean explicit : List.of(false, true)) {
            String suffix = explicit ? "explicit" : "legacy";
            double x = explicit ? 290 : 150;
            var defaults = FormStyle.builder().build();
            var text = Navigation.TextField.builder().target(target("unicode-area", x, 172, 120, 26))
                    .name("default_text_" + suffix).value("Same default");
            var choice = Navigation.ChoiceField.builder().target(target("unicode-area", x, 216, 120, 26))
                    .name("default_choice_" + suffix).options(List.of("One", "Two")).value("Two");
            var check = Navigation.CheckBox.builder().target(target("unicode-area", x, 260, 18, 18))
                    .name("default_check_" + suffix).checked(true);
            var radio = Navigation.RadioGroup.builder().target(target("unicode-area", x, 304, 100, 18))
                    .name("default_radio_" + suffix).options(List.of("Left", "Right")).value("Left");
            if (explicit) { text.style(defaults); choice.style(defaults); check.style(defaults); radio.style(defaults); }
            fields.add(text.build()); fields.add(choice.build()); fields.add(check.build()); fields.add(radio.build());
        }
        var document = new Document(new DocumentMetadata("Editable form style review", "Export4j synthetic example"),
                PageSettings.builder().size(500, 540).margins(24).build(), List.of(
                        new Heading("ascii-title", "01 ASCII appearance styles", 1),
                        new ComponentBlock("ascii-area", FormStyleExample::asciiPanel),
                        new PageBreak("next-form-page"),
                        new Heading("unicode-title", "02 Unicode and unchanged defaults", 1),
                        new ComponentBlock("unicode-area", FormStyleExample::unicodePanel)));
        var navigation = Navigation.fromHeadings(document,
                new TableOfContents(true, "Contents", ParagraphStyle.of(12)), List.of(), fields);
        var result = DocumentEngine.builder(FontGroup.strict(List.of(regular, chinese))).navigation(navigation).build()
                .export(document).backend(new PdfBackend()).to(OUTPUT.resolve("styled.pdf"));
        if (result.bodyPageOffset() != 1 || result.display().pages().size() != 3 || !result.diagnostics().isEmpty()) {
            throw new IllegalStateException("Form styles must preserve one contents page, two body pages and no diagnostics");
        }
        Files.writeString(OUTPUT.resolve("fonts.json"), "{\"fonts\":["
                + "{\"resource\":\"LiberationSans-Regular.ttf\",\"sha256\":\"" + regular.data().id() + "\",\"bytes\":" + regular.data().byteSize() + "},"
                + "{\"resource\":\"LXGWWenKaiTC-Regular.ttf\",\"sha256\":\"" + chinese.data().id() + "\",\"bytes\":" + chinese.data().byteSize() + "}]}\n",
                StandardCharsets.UTF_8);
        System.out.println("Form styles: three pages, " + fields.size() + " editable fields with final contents offset");
    }

    private static Navigation.Target target(String id, double x, double y, double width, double height) {
        return new Navigation.BlockTarget(id, new Rect(x, y, width, height));
    }

    private static ComponentLayout asciiPanel(ComponentContext context) {
        var builder = new DisplayListBuilder(new PageSize(context.width(), 300));
        underlay(builder, 44, 28);
        label(builder, context, "BLUE / centered text", 0, 8);
        label(builder, context, "BLACK / transparent", 0, 52);
        label(builder, context, "AMBER / choice", 0, 96);
        label(builder, context, "BLUE / selected", 0, 140);
        label(builder, context, "AMBER / radio", 0, 184);
        label(builder, context, "Draft", 180, 184);
        label(builder, context, "Final", 280, 184);
        label(builder, context, "8 pt / borderless", 0, 218);
        label(builder, context, "8 pt / adaptive border", 0, 252);
        return new ComponentLayout(context.width(), 300, 9, builder.build().commands(), List.of());
    }

    private static ComponentLayout unicodePanel(ComponentContext context) {
        var builder = new DisplayListBuilder(new PageSize(context.width(), 350));
        underlay(builder, 88, 50);
        label(builder, context, "UNICODE / full font", 0, 8);
        label(builder, context, "UNICODE / choice", 0, 52);
        label(builder, context, "UNICODE / multiline", 0, 96);
        label(builder, context, "Legacy", 150, 150);
        label(builder, context, "Explicit empty style", 290, 150);
        label(builder, context, "Default text", 0, 180);
        label(builder, context, "Default choice", 0, 224);
        label(builder, context, "Default check", 0, 264);
        label(builder, context, "Default radio", 0, 308);
        return new ComponentLayout(context.width(), 350, 9, builder.build().commands(), List.of());
    }

    private static void underlay(DisplayListBuilder builder, double y, double height) {
        builder.fillRect(new Rect(150, y, 280, height), UNDERLAY);
    }

    private static void label(DisplayListBuilder builder, ComponentContext context, String text, double x, double y) {
        var layout = new ParagraphLayouter().layout(text, context.fonts(), 145, ParagraphStyle.of(10));
        TextLayoutPainter.draw(builder, layout, new Point(x, y), RgbColor.BLACK);
    }
}
