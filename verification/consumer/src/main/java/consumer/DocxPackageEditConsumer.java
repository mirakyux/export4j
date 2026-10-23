// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.model.Document;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DocxPackageEditConsumer {
    private DocxPackageEditConsumer() { }
    static void verify(Path output) throws IOException {
        var adapter = new DocxAdapter(); var bytes = new ByteArrayOutputStream();
        adapter.write(Document.builder().paragraph("CALLER-PACKAGE-OLD").build(), bytes);
        Files.write(output.resolve("consumer-package-source.docx"), bytes.toByteArray());
        var prepared = adapter.editPackage(bytes.toByteArray()).replaceText("CALLER-PACKAGE-OLD", "CALLER-PACKAGE-READY").prepare();
        var result = prepared.to(output.resolve("consumer-package-edited.docx"));
        if (!prepared.preview().issues().equals(result.report().issues()) || result.changes().size() != 1 || !result.report().losses().isEmpty()) {
            throw new IllegalStateException("isolated package preservation differs");
        }
    }
}
