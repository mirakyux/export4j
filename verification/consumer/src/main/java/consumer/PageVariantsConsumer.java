// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class PageVariantsConsumer {
    private PageVariantsConsumer() { }

    static void verify(DocumentEngine engine, Path output) throws IOException {
        var format = ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build();
        var regions = PageRegions.builder()
                .header(PageRegion.builder().height(20).paragraph(new Paragraph("odd", "CALLER-ODD", format)).build())
                .header(PageRegions.Variant.EVEN, PageRegion.builder().height(30).paragraph(new Paragraph("even", "CALLER-EVEN", format)).build()).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s",
                PageSettings.builder().size(260, 180).margins(20).build(),
                List.of(new Paragraph("a", "CALLER-ONE", format), new PageBreak("break"), new Paragraph("b", "CALLER-TWO", format)), 2, regions)));
        engine.write(source, new PdfBackend(), output.resolve("consumer-page-variants.pdf"));
    }
}
