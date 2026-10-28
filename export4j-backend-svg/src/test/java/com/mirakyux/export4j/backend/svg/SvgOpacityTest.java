// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.svg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.PageSize;

class SvgOpacityTest {
    @Test void alphaReplacementAndRestorationDoNotNestMultiplicativeSvgGroups() throws Exception {
        var bounds = new Rect(0, 0, 10, 10);
        var page = new DisplayPage(new PageSize(40, 40), List.of(new DrawCommand.Opacity(0.5, 0.25),
                new DrawCommand.FillRect(bounds, RgbColor.BLACK), new DrawCommand.Save(), new DrawCommand.Opacity(0.8, 0.9),
                new DrawCommand.FillRect(bounds, RgbColor.BLACK), new DrawCommand.Restore(),
                new DrawCommand.FillRect(bounds, RgbColor.BLACK), new DrawCommand.Opacity(1, 1),
                new DrawCommand.FillRect(bounds, RgbColor.BLACK)));
        var bytes = new ByteArrayOutputStream(); new SvgBackend().writePage(page, bytes);
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(bytes.toByteArray()));
        var rectangles = xml.getElementsByTagName("rect");
        assertEquals("0.5", ((Element) rectangles.item(1)).getAttribute("fill-opacity"));
        assertEquals("0.8", ((Element) rectangles.item(2)).getAttribute("fill-opacity"));
        assertEquals("0.5", ((Element) rectangles.item(3)).getAttribute("fill-opacity"));
        assertEquals("", ((Element) rectangles.item(4)).getAttribute("fill-opacity"));
        assertEquals(0, xml.getElementsByTagName("g").getLength());
    }
}
