// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

class SvgBackendTest {
    @Test void outputIsXmlWithClippedPagesAndEscapedLogicalText() throws Exception {
        var page = new DisplayPage(new PageSize(60, 40), List.of(new DrawCommand.Save(), new DrawCommand.Clip(new Rect(0, 0, 10, 10)),
                new DrawCommand.FillRect(new Rect(0, 0, 30, 30), new RgbColor(1, 0, 0)), new DrawCommand.Restore(),
                new DrawCommand.OutlineText("<&中文", List.of(), RgbColor.BLACK)));
        var bytes = new TrackingOutput(); new SvgBackend().write(new DisplayDocument(new DocumentMetadata("T & T", ""), List.of(page, page)), bytes);
        assertFalse(bytes.closed);
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(3, xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg").getLength());
        assertEquals("100.0pt", xml.getDocumentElement().getAttribute("height"));
        assertTrue(xml.getDocumentElement().getTextContent().contains("<&中文"));
        assertEquals(2, xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "clipPath").getLength());
    }
    @Test void interactionsAreNotSilentlyDiscarded() {
        var page = new DisplayPage(new PageSize(60, 40), List.of());
        var doc = new DisplayDocument(DocumentMetadata.EMPTY, List.of(page), new DocumentExtras(List.of(new DocumentExtras.Bookmark("B", 1, 0, new Point(0, 0))), List.of(), List.of(), List.of()));
        var output = new ByteArrayOutputStream(); assertThrows(IllegalArgumentException.class, () -> new SvgBackend().write(doc, output)); assertEquals(0, output.size());
    }
    private static final class TrackingOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
}
