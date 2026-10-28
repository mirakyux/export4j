// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.VerticalText;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.PageSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Public coordinate-only caller for the declared em-cell vertical writing subset. */
public final class Gap08VerticalTextConsumer {
    private Gap08VerticalTextConsumer() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("vertical-text");
        Files.createDirectories(output);
        var cjk = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LXGWWenKaiTC-Regular.ttf")));
        var latin = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        var fonts = FontGroup.strict(List.of(cjk, latin));
        var engine = DocumentEngine.builder(fonts).build();
        var context = new ComponentContext(200, 140, fonts, 0, 0, 1);
        fixture("upright", "天地玄黃宇宙洪荒", VerticalText.ColumnOrder.RIGHT_TO_LEFT, engine, context, output);
        fixture("mixed", "一I字B竪1排2", VerticalText.ColumnOrder.RIGHT_TO_LEFT, engine, context, output);
        fixture("punctuation", "「天地」，玄黃。", VerticalText.ColumnOrder.RIGHT_TO_LEFT, engine, context, output);
        fixture("left-to-right", "天地\r\n玄黃", VerticalText.ColumnOrder.LEFT_TO_RIGHT, engine, context, output);
    }

    private static void fixture(String name, String source, VerticalText.ColumnOrder order, DocumentEngine engine,
            ComponentContext context, Path output) throws Exception {
        var component = VerticalText.builder(source).fontSize(18).cellAdvance(32).columnAdvance(36)
                .height(128).columnOrder(order).build();
        var result = component.layoutDetails(context);
        var document = Documents.builder().pageSettings(PageSettings.builder().size(240, 180).margins(20).build())
                .component(name, component).build();
        engine.export(document).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
        engine.export(document).backend(new SvgBackend()).to(output.resolve(name + ".svg"));
        engine.export(document).backend(new RasterBackend(144).singlePage()).to(output.resolve(name + ".png"));
        Files.writeString(output.resolve(name + ".txt"), source);
        var rows = new StringBuilder("start\tend\tcolumn\tx\ty\twidth\theight\trendered\tsideways\n");
        for (var placement : result.placements()) {
            var box = placement.bounds();
            rows.append(String.format(Locale.ROOT, "%d\t%d\t%d\t%.6f\t%.6f\t%.6f\t%.6f\t%d\t%s%n",
                    placement.sourceStart(), placement.sourceEnd(), placement.columnIndex(), box.x() + 20,
                    box.y() + 20, box.width(), box.height(), placement.renderedCodePoint(), placement.sideways()));
        }
        Files.writeString(output.resolve(name + ".tsv"), rows);
    }
}
