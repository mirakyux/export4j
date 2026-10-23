// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class MinimumLinesConsumer {
    private MinimumLinesConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var style = ParagraphStyle.of(10).toBuilder().lineHeight(20).build();
        var source = Documents.builder().pageSettings(PageSettings.builder().size(300, 120).margins(20).build())
                .component(new BoxComponent(260, 60, new TextComponent("CALLER-PREFACE", style, RgbColor.BLACK)))
                .component(ContinuousColumns.builder(new FlowText("CALL01\nCALL02\nCALL03\nCALL04\nCALL05", style, RgbColor.BLACK, PaginationPolicy.BALANCED))
                        .columnWeights(List.of(1.0, 3.0)).gap(20).build()).build();
        engine.write(source, new PdfBackend(), output.resolve("consumer-minimum-lines.pdf"));
    }
}
