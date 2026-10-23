// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.RichTextComponent;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class VariableColumnsConsumer {
    private VariableColumnsConsumer() { }

    static void verify(DocumentEngine engine, Path output) throws IOException {
        var text = RichTextComponent.builder().lineHeight(16);
        for (int index = 1; index <= 40; index++) { text.add(String.format(Locale.ROOT, "CALL%02d ", index), InlineStyle.of(10)); }
        var flow = FlowColumn.builder().padding(2).gap(3).text("CALLER-BEGIN")
                .add(text.build().flow()).text("CALLER-END").build();
        var document = Documents.builder().pageSettings(PageSettings.builder().size(348, 160).margins(24).build())
                .component(ContinuousColumns.builder(flow).columnWeights(List.of(1.0, 3.0)).gap(20).balanced(true).build()).build();
        engine.write(document, new PdfBackend(), output.resolve("consumer-variable-columns.pdf"));
    }
}
