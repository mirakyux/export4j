// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;

/** Parser view of a stream without adding Form XObject dictionary entries to page content. */
record PdfContentStream(COSStream stream) implements PDContentStream {
    @Override public InputStream getContents() throws IOException { return stream.createInputStream(); }
    @Override public RandomAccessRead getContentsForRandomAccess() throws IOException { return stream.createView(); }
    @Override public PDResources getResources() { return null; }
    @Override public PDRectangle getBBox() { return null; }
    @Override public Matrix getMatrix() { return new Matrix(); }
}
