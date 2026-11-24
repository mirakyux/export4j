// SPDX-License-Identifier: Apache-2.0
@SuppressWarnings("requires-automatic")
module com.mirakyux.export4j.font.fontbox {
    requires com.mirakyux.export4j.font;
    requires org.apache.commons.logging;
    requires org.apache.fontbox;
    requires org.apache.pdfbox.io;

    provides com.mirakyux.export4j.font.spi.FontProgramParser
            with com.mirakyux.export4j.font.fontbox.FontBoxProgramParser;
}
