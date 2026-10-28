// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.svg;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.core.StaticBackendSupport;
import com.mirakyux.export4j.model.DocumentMetadata;

/** Self-contained SVG with outlined text and embedded PNGs. Multiple pages stack vertically with a 20 pt gap.
 * Rejects interactive extras. Caller owns output. Instances are immutable and reusable concurrently. */
public final class SvgBackend implements DocumentBackend {
    private final Map<FontFamily, FontData> standardFonts;

    public SvgBackend() { this(Map.of()); }
    public SvgBackend(Map<FontFamily, FontData> standardFonts) { this.standardFonts = Map.copyOf(standardFonts); }

    public void writePage(DisplayPage page, OutputStream output) throws IOException {
        write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page)), output);
    }

    @Override
    public void write(DisplayDocument document, OutputStream output) throws IOException {
        write(document, output, com.mirakyux.export4j.model.processing.ProcessingPolicy.STRICT);
    }

    public com.mirakyux.export4j.model.processing.ProcessingReport write(DisplayDocument document, OutputStream output,
            com.mirakyux.export4j.model.processing.ProcessingPolicy policy) throws IOException {
        Objects.requireNonNull(output, "output");
        if (!document.accessibility().children().isEmpty()) { throw new IllegalArgumentException("SVG output does not map the explicit accessibility tree"); }
        var pages = StaticBackendSupport.outlines(document, standardFonts);
        var issues = com.mirakyux.export4j.graphics.core.GraphicsResources.colorIssues(pages, "SVG", Objects.requireNonNull(policy, "policy"));
        double width = pages.stream().mapToDouble(p -> p.size().width()).max().orElseThrow();
        double height = pages.stream().mapToDouble(p -> p.size().height()).sum() + 20.0 * (pages.size() - 1);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("pt\" height=\"").append(height)
                .append("pt\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">");
        xml.append("<title>").append(escape(document.metadata().title())).append("</title>");
        double y = 0;
        var writer = new SvgGraphicsWriter(xml);
        for (int index = 0; index < pages.size(); index++) {
            com.mirakyux.export4j.model.ExecutionContext.progress("SVG_PAGE", "", index, index, pages.size());
            var page = pages.get(index);
            xml.append("<svg data-page=\"").append(index + 1).append("\" x=\"0\" y=\"").append(y).append("\" width=\"")
                    .append(page.size().width()).append("\" height=\"").append(page.size().height()).append("\" viewBox=\"0 0 ")
                    .append(page.size().width()).append(' ').append(page.size().height()).append("\">");
            xml.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
            writer.commands(page.commands());
            xml.append("</svg>");
            y += page.size().height() + 20;
        }
        xml.append("</svg>\n");
        output.write(xml.toString().getBytes(StandardCharsets.UTF_8));
        return new com.mirakyux.export4j.model.processing.ProcessingReport(com.mirakyux.export4j.model.processing.ProcessingReport.Phase.ACTUAL, issues);
    }

    private static String escape(String value) {
        if (value.codePoints().anyMatch(cp -> cp < 32 && cp != 9 && cp != 10 && cp != 13 || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("invalid XML character");
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
