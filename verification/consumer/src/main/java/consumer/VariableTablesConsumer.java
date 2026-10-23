// SPDX-License-Identifier: Apache-2.0
package consumer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.SemanticTable;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Table;

final class VariableTablesConsumer {
    private VariableTablesConsumer() { }
    static void verify(DocumentEngine engine, Path output) throws IOException {
        var table = Table.builder("caller-variable").cellFormat(CellFormat.builder().fontSize(10).lineHeight(14).build())
                .format(format -> format.padding(2)).header("CALLER");
        for (int row = 1; row <= 10; row++) { table.row(String.format(Locale.ROOT, "CALL-%02d alpha beta gamma", row)); }
        var columns = ContinuousColumns.builder(new SemanticTable(table.build())).columnWeights(List.of(1.0, 2.0)).gap(12).build();
        var document = Documents.builder().pageSettings(PageSettings.builder().size(320, 180).margins(20).build())
                .component("caller-host", columns).build();
        engine.export(document).backend(new PdfBackend()).to(output.resolve("consumer-variable-tables.pdf"));
    }
}
