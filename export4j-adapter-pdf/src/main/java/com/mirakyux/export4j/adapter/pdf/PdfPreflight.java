// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.zip.InflaterInputStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Operation-confined graph and decoding preflight; deliberately accepts a strict static subset. */
final class PdfPreflight {
    private static final Set<String> REJECTED_KEYS = Set.of(
            "Annots", "AcroForm", "Outlines", "Names", "Dests", "OpenAction", "AA", "JS", "XFA",
            "EmbeddedFiles", "AF", "Collection", "SigFlags", "ByteRange", "StructTreeRoot",
            "StructParent", "StructParents", "OCProperties", "OC", "OutputIntents", "Requirements",
            "Threads", "PresSteps", "Trans", "Dur", "VP", "MarkInfo");
    private static final Set<String> REJECTED_TYPES = Set.of(
            "Sig", "EmbeddedFile", "Filespec", "Annot", "Action", "OCG", "OCMD", "StructElem");
    private static final Set<String> ACTIONS = Set.of(
            "JavaScript", "Launch", "URI", "GoTo", "GoToR", "GoToE", "Named", "SubmitForm",
            "ResetForm", "ImportData", "Rendition", "Movie", "Sound", "Hide", "SetOCGState", "Trans", "Thread");
    private static final Set<String> CATALOG_KEYS = Set.of(
            "Type", "Pages", "Version", "Metadata", "Lang", "ViewerPreferences", "PageLayout", "PageMode");
    private static final Set<String> RESOURCE_KINDS = Set.of(
            "Font", "XObject", "ExtGState", "ColorSpace", "Pattern", "Shading", "Properties");

    final PdfLimits limits;
    private final Set<COSStream> decoded = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<COSStream, CommandDepth> commands = new IdentityHashMap<>();
    private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSBase> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private long decodedBytes;
    private long imagePixels;
    private int objectKeys;
    private long parsedNodes;
    private int graphNodes;
    private int resources;
    private int pages;
    private long operators;
    private long operands;
    int inputIndex;
    int pageIndex = -1;

    PdfPreflight(PdfLimits limits) { this.limits = limits; }

    PdfOperationException failure(PdfOperationException.Code code) {
        return new PdfOperationException(code, inputIndex, pageIndex);
    }

    void objectKey() throws IOException {
        if (++objectKeys > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
    }

    private void parsedNode() throws IOException {
        ExecutionContext.checkpoint();
        if (++parsedNodes > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
    }

    void inspect(PDDocument document) throws IOException {
        int count = document.getNumberOfPages();
        if (count <= 0) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        if (count > limits.maxPages() - pages) { throw failure(PdfOperationException.Code.PAGE_LIMIT); }
        pages += count;
        if (document.getDocument().getXrefTable().size() > limits.maxObjects()) {
            throw failure(PdfOperationException.Code.OBJECT_LIMIT);
        }
        graph(document.getDocument().getTrailer(), 0);
        for (var key : document.getDocument().getXrefTable().keySet()) {
            ExecutionContext.checkpoint();
            graph(document.getDocument().getObjectFromPool(key), 0);
        }
        for (int index = 0; index < count; index++) {
            pageIndex = index;
            var streams = document.getPage(index).getContentStreams();
            int depth = 0;
            while (streams.hasNext()) {
                var value = commands(streams.next().getCOSObject());
                if (depth + value.minimum() < 0) { throw failure(PdfOperationException.Code.INVALID_PDF); }
                if (depth + value.maximum() > limits.maxDepth()) { throw failure(PdfOperationException.Code.DEPTH_LIMIT); }
                depth += value.finalDepth();
            }
            if (depth != 0) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        }
        pageIndex = -1;
    }

    private void graph(COSBase value, int depth) throws IOException {
        ExecutionContext.checkpoint();
        if (value == null) { return; }
        if (depth > limits.maxDepth()) { throw failure(PdfOperationException.Code.DEPTH_LIMIT); }
        if (active.contains(value)) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        if (!visited.add(value)) { return; }
        if (++graphNodes > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
        active.add(value);
        try {
            if (value instanceof COSObject indirect) { graph(indirect.getObject(), depth + 1); }
            else if (value instanceof COSArray array) {
                if (array.size() > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
                for (var item : array) { graph(item, depth + 1); }
            } else if (value instanceof COSDictionary dictionary) {
                rejectFeatures(dictionary);
                if (value instanceof COSStream stream) {
                    stream(stream);
                    if ("Form".equals(dictionary.getNameAsString(COSName.SUBTYPE)) || dictionary.containsKey("PatternType")) {
                        var commandDepth = commands(stream);
                        if (commandDepth.minimum() < 0 || commandDepth.finalDepth() != 0) { throw failure(PdfOperationException.Code.INVALID_PDF); }
                    }
                }
                String type = dictionary.getNameAsString(COSName.TYPE, "");
                for (var entry : dictionary.entrySet()) {
                    if (entry.getKey().equals(COSName.PARENT) && (type.equals("Page") || type.equals("Pages"))) { continue; }
                    if (entry.getKey().equals(COSName.RESOURCES)) { countResources(entry.getValue()); }
                    graph(entry.getValue(), depth + 1);
                }
            }
        } finally { active.remove(value); }
    }

    private void countResources(COSBase value) throws IOException {
        if (value instanceof COSObject object) { value = object.getObject(); }
        if (!(value instanceof COSDictionary dictionary)) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        for (String kind : RESOURCE_KINDS) {
            var group = dictionary.getDictionaryObject(COSName.getPDFName(kind));
            if (group == null) { continue; }
            if (!(group instanceof COSDictionary entries)) { throw failure(PdfOperationException.Code.INVALID_PDF); }
            if (entries.size() > limits.maxResources() - resources) {
                throw failure(PdfOperationException.Code.RESOURCE_LIMIT);
            }
            resources += entries.size();
        }
    }

    private void rejectFeatures(COSDictionary dictionary) throws IOException {
        String type = dictionary.getNameAsString(COSName.TYPE, "");
        if (REJECTED_TYPES.contains(type) || ACTIONS.contains(dictionary.getNameAsString(COSName.S, ""))
                || "Type3".equals(dictionary.getNameAsString(COSName.SUBTYPE))) {
            throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
        }
        for (var key : dictionary.keySet()) {
            if (REJECTED_KEYS.contains(key.getName()) || type.equals("Catalog") && !CATALOG_KEYS.contains(key.getName())) {
                throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
            }
        }
    }

    private record CommandDepth(int finalDepth, int minimum, int maximum) { }

    private CommandDepth commands(COSStream stream) throws IOException {
        var cached = commands.get(stream);
        if (cached != null) { return cached; }
        PdfContentSyntax.validate(stream.createInputStream(), this);
        var parser = new ContentParser(stream, this);
        try {
            Object token;
            int graphicsDepth = 0;
            int minimum = 0;
            int maximum = 0;
            while ((token = parser.parseNextToken()) != null) {
                ExecutionContext.checkpoint();
                if (token instanceof Operator operator) {
                    if (++operators > limits.maxContentOperators()) { throw failure(PdfOperationException.Code.OPERATOR_LIMIT); }
                    String name = operator.getName();
                    if (name.equals("BI")) { throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE); }
                    if (name.equals("q") && ++graphicsDepth > limits.maxDepth()) { throw failure(PdfOperationException.Code.DEPTH_LIMIT); }
                    if (name.equals("Q")) { graphicsDepth--; }
                    minimum = Math.min(minimum, graphicsDepth);
                    maximum = Math.max(maximum, graphicsDepth);
                }
            }
            var value = new CommandDepth(graphicsDepth, minimum, maximum);
            commands.put(stream, value);
            return value;
        } finally { parser.close(); }
    }

    private void contentNode() throws IOException {
        ExecutionContext.checkpoint();
        if (++operands > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
    }

    private static final class ContentParser extends PDFStreamParser {
        private final PdfPreflight budget;
        private int depth;

        ContentParser(COSStream stream, PdfPreflight budget) throws IOException {
            super(new PDFormXObject(stream));
            this.budget = budget;
        }

        private void enter() {
            if (++depth > budget.limits.maxDepth()) {
                depth--;
                throw new UncheckedIOException(budget.failure(PdfOperationException.Code.DEPTH_LIMIT));
            }
        }

        @Override public Object parseNextToken() throws IOException {
            budget.contentNode();
            return super.parseNextToken();
        }

        @Override protected COSBase parseDirObject() throws IOException {
            try { budget.contentNode(); return super.parseDirObject(); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
        }

        @Override protected COSArray parseCOSArray() throws IOException {
            enter();
            try { return super.parseCOSArray(); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
            finally { depth--; }
        }

        @Override protected COSDictionary parseCOSDictionary(boolean direct) throws IOException {
            enter();
            try { return super.parseCOSDictionary(direct); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
            finally { depth--; }
        }
    }

    /** Checks expansion with streaming JDK zlib before PDFBox's eager decode allocation.
     * XRef streams are unencrypted; encrypted ObjStm is checked after PDFBox decrypts and before object parsing.
     */
    void stream(COSStream stream) throws IOException {
        if (!decoded.add(stream)) { return; }
        ExecutionContext.checkpoint();
        if (stream.containsKey("F") || stream.containsKey("FFilter") || stream.containsKey("FDecodeParms")) {
            throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
        }
        boolean image = "Image".equals(stream.getNameAsString(COSName.SUBTYPE));
        if (image) {
            long width = positiveInteger(stream, COSName.WIDTH);
            long height = positiveInteger(stream, COSName.HEIGHT);
            if (width > limits.maxImagePixels() / height || width * height > limits.maxImagePixels() - imagePixels) {
                throw failure(PdfOperationException.Code.IMAGE_LIMIT);
            }
            imagePixels += width * height;
        }
        COSBase filters = stream.getDictionaryObject(COSName.FILTER);
        if (filters instanceof COSArray array) {
            if (array.size() != 1) { throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE); }
            filters = array.getObject(0);
        }
        String filter = filters == null ? "" : filters instanceof COSName name ? name.getName() : "unsupported";
        if (!filter.isEmpty() && !filter.equals("FlateDecode") && !filter.equals("Fl")
                && !(image && (filter.equals("DCTDecode") || filter.equals("DCT")))) {
            throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
        }
        predictor(stream, filter);
        try (InputStream raw = stream.createRawInputStream();
                InputStream content = filter.equals("FlateDecode") || filter.equals("Fl") ? new InflaterInputStream(raw) : raw) {
            var buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = content.read(buffer)) != -1) {
                ExecutionContext.checkpoint();
                if (read > limits.maxDecodedStreamBytes() - count || read > limits.maxDecodedBytes() - decodedBytes) {
                    throw failure(PdfOperationException.Code.DECODED_STREAM_LIMIT);
                }
                count += read;
                decodedBytes += read;
            }
        }
    }

    private void predictor(COSStream stream, String filter) throws IOException {
        COSBase parameters = stream.getDictionaryObject(COSName.DECODE_PARMS);
        if (parameters instanceof COSArray array) {
            if (array.size() != 1) { throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE); }
            parameters = array.getObject(0);
        }
        if (parameters == null || parameters instanceof org.apache.pdfbox.cos.COSNull) { return; }
        if (!(parameters instanceof COSDictionary dictionary) || !(filter.equals("FlateDecode") || filter.equals("Fl"))) {
            throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
        }
        for (var key : dictionary.keySet()) {
            if (!Set.of("Predictor", "Colors", "BitsPerComponent", "Columns").contains(key.getName())) {
                throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
            }
        }
        long predictor = dictionary.getInt(COSName.PREDICTOR, 1);
        long colors = dictionary.getInt(COSName.COLORS, 1);
        long bits = dictionary.getInt(COSName.BITS_PER_COMPONENT, 8);
        long columns = dictionary.getInt(COSName.COLUMNS, 1);
        if (!(predictor == 1 || predictor == 2 || predictor >= 10 && predictor <= 15)
                || colors < 1 || colors > 4 || !(bits == 1 || bits == 2 || bits == 4 || bits == 8 || bits == 16)
                || columns < 1 || columns > 65_536 || columns * colors * bits / 8 > limits.maxDecodedStreamBytes()) {
            throw failure(PdfOperationException.Code.UNSUPPORTED_FEATURE);
        }
    }

    private long positiveInteger(COSDictionary dictionary, COSName key) throws IOException {
        COSBase value = dictionary.getDictionaryObject(key);
        if (!(value instanceof COSInteger number) || number.longValue() <= 0) {
            throw failure(PdfOperationException.Code.INVALID_PDF);
        }
        return number.longValue();
    }

    private void pageTree(COSDictionary catalog) throws IOException {
        var root = catalog.getCOSDictionary(COSName.PAGES);
        if (root == null) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        var seen = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
        int count = pageNode(root, seen, 0, null);
        if (count <= 0 || count > limits.maxPages() - pages) { throw failure(PdfOperationException.Code.PAGE_LIMIT); }
    }

    private int pageNode(COSDictionary node, Set<COSDictionary> seen, int depth, COSDictionary parent) throws IOException {
        ExecutionContext.checkpoint();
        if (depth > limits.maxDepth()) { throw failure(PdfOperationException.Code.DEPTH_LIMIT); }
        if (!seen.add(node)) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        if (seen.size() > limits.maxObjects()) { throw failure(PdfOperationException.Code.OBJECT_LIMIT); }
        if (node.getCOSDictionary(COSName.PARENT) != parent) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        if (COSName.PAGE.equals(node.getCOSName(COSName.TYPE))) { return 1; }
        var kids = node.getCOSArray(COSName.KIDS);
        if (!COSName.PAGES.equals(node.getCOSName(COSName.TYPE)) || kids == null || kids.size() > limits.maxObjects()) {
            throw failure(PdfOperationException.Code.INVALID_PDF);
        }
        int count = 0;
        for (int index = 0; index < kids.size(); index++) {
            var value = kids.getObject(index);
            if (!(value instanceof COSDictionary child)) { throw failure(PdfOperationException.Code.INVALID_PDF); }
            int added = pageNode(child, seen, depth + 1, node);
            if (added > limits.maxPages() - pages - count) { throw failure(PdfOperationException.Code.PAGE_LIMIT); }
            count += added;
        }
        if (node.getInt(COSName.COUNT, -1) != count) { throw failure(PdfOperationException.Code.INVALID_PDF); }
        return count;
    }

    static final class Parser extends PDFParser {
        private final PdfPreflight budget;
        private final Set<COSObjectKey> keys = new HashSet<>();
        private int directDepth;

        Parser(RandomAccessRead source, String password, PdfPreflight budget) throws IOException {
            super(source, password);
            this.budget = budget;
        }

        @Override protected COSObjectKey getObjectKey(long object, int generation) {
            COSObjectKey key = super.getObjectKey(object, generation);
            if (keys.add(key)) {
                try { budget.objectKey(); }
                catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            }
            return key;
        }

        @Override public COSBase dereferenceCOSObject(COSObject object) throws IOException {
            try { return super.dereferenceCOSObject(object); }
            catch (IOException exception) {
                // COSObject.getObject() logs and replaces a checked parse failure with null.
                // Retain every failure across that lazy boundary, including size/filter budgets.
                throw new UncheckedIOException(exception);
            }
        }

        @Override protected COSBase parseDirObject() throws IOException {
            ExecutionContext.checkpoint();
            try { budget.parsedNode(); }
            catch (IOException exception) { throw new UncheckedIOException(exception); }
            if (++directDepth > budget.limits.maxDepth()) {
                directDepth--;
                throw new UncheckedIOException(budget.failure(PdfOperationException.Code.DEPTH_LIMIT));
            }
            try { return super.parseDirObject(); }
            finally { directDepth--; }
        }

        @Override protected COSDictionary parseCOSDictionary(boolean direct) throws IOException {
            if (directDepth == 0) {
                try { budget.parsedNode(); }
                catch (IOException exception) { throw new UncheckedIOException(exception); }
            }
            return super.parseCOSDictionary(direct);
        }

        @Override protected void checkPages(COSDictionary catalog) throws IOException {
            budget.pageTree(catalog);
            super.checkPages(catalog);
        }

        @Override protected COSStream parseCOSStream(COSDictionary dictionary) throws IOException {
            COSStream stream = super.parseCOSStream(dictionary);
            String type = dictionary.getNameAsString(COSName.TYPE, "");
            if (type.equals("XRef") && (dictionary.getLong(COSName.SIZE, -1) < 0
                    || dictionary.getLong(COSName.SIZE, -1) > budget.limits.maxObjects())) {
                throw budget.failure(PdfOperationException.Code.OBJECT_LIMIT);
            }
            if (type.equals("ObjStm") && (dictionary.getInt(COSName.N, -1) < 0
                    || dictionary.getInt(COSName.N, -1) > budget.limits.maxObjects())) {
                throw budget.failure(PdfOperationException.Code.OBJECT_LIMIT);
            }
            if (type.equals("XRef") || securityHandler == null) { budget.stream(stream); }
            return stream;
        }

        @Override protected COSBase parseObjectStreamObject(long number, COSObjectKey key) throws IOException {
            var value = document.getObjectFromPool(getObjectKey(number, 0)).getObject();
            if (!(value instanceof COSStream stream)) { throw budget.failure(PdfOperationException.Code.INVALID_PDF); }
            budget.stream(stream);
            return super.parseObjectStreamObject(number, key);
        }
    }
}
