// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.FormStyle;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Document;

/** Verifies editable form styles through the published public API and final interaction data. */
final class FormStyleConsumer {
    private FormStyleConsumer() { }

    static void verify(FontFace font, Path output) throws IOException {
        var ink = new RgbColor(0.1, 0.2, 0.6);
        var fill = new RgbColor(0.9, 0.95, 1);
        var textStyle = FormStyle.builder().fontSize(13).color(ink).background(fill)
                .border(1, ink).alignment(FormStyle.Alignment.CENTER).build();
        var markStyle = FormStyle.builder().color(new RgbColor(0.8, 0.1, 0.2)).noBackground().noBorder().build();
        var fields = List.<Navigation.Field>of(
                Navigation.TextField.builder().target(target(60, 240, 28)).name("styled_text")
                        .value("Café").fonts(List.of(font.data())).style(textStyle).build(),
                Navigation.ChoiceField.builder().target(target(105, 240, 28)).name("styled_choice")
                        .options(List.of("Alpha", "Beta")).value("Beta")
                        .style(style -> style.fontSize(12).color(RgbColor.BLACK).noBorder()
                                .alignment(FormStyle.Alignment.RIGHT)).build(),
                Navigation.CheckBox.builder().target(target(150, 20, 20)).name("styled_check")
                        .checked(true).style(markStyle).build(),
                Navigation.RadioGroup.builder().target(target(190, 100, 20)).name("styled_radio")
                        .options(List.of("First", "Second")).value("Second")
                        .style(style -> style.color(ink).background(fill).border(0.8, ink)).build());
        var document = Document.builder().pageSettings(page -> page.size(360, 280).margins(24))
                .heading("FORM-STYLE-CONSUMER", 1).build();
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(font)))
                .navigation(new Navigation(List.of(), com.mirakyux.export4j.components.TableOfContents.NONE,
                        List.of(), fields)).build();
        var result = engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-form-style.pdf"));
        if (result.display().pages().size() != 1 || result.display().extras().fields().size() != 4
                || !result.diagnostics().isEmpty()
                || !result.display().extras().fields().getFirst().style().equals(textStyle)) {
            throw new IllegalStateException("published editable form style or resolved field geometry was lost");
        }
    }

    private static Navigation.Target target(double y, double width, double height) {
        return new Navigation.PageTarget(0, new Position.Absolute(new Rect(24, y, width, height)));
    }
}
