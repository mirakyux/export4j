// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** Invocation-confined validation and source-token caching for copy-on-write Form edits. */
final class PdfTextForms {
    private final PdfLimits limits;
    private final Map<COSStream, List<Object>> parsed = new IdentityHashMap<>();
    private final Set<COSStream> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private long cachedTokens;
    private long validationOperators;
    private long editOperators;
    final PdfSemanticGraphicsState graphicsStates;

    PdfTextForms(PdfLimits limits) { this.limits = limits; graphicsStates = new PdfSemanticGraphicsState(limits); }

    List<Object> page(PDPage page) throws IOException {
        var bytes = new PdfImageSupport.Bytes(Math.min(limits.maxDecodedBytes(), limits.maxDecodedStreamBytes()));
        try (var content = page.getContents()) {
            var buffer = new byte[8192]; int length;
            while ((length = content.read(buffer)) != -1) { ExecutionContext.checkpoint(); bytes.write(buffer, 0, length); }
        }
        var parser = new PDFStreamParser(bytes.data());
        try { return tokens(parser); } finally { parser.close(); }
    }

    List<Object> tokens(PDFormXObject form) throws IOException {
        var stream = form.getCOSObject(); var found = parsed.get(stream);
        if (found != null) { return found; }
        var parser = new PDFStreamParser(new PdfContentStream(stream));
        List<Object> result;
        try { result = tokens(parser); } finally { parser.close(); }
        parsed.put(stream, result); return result;
    }

    private List<Object> tokens(PDFStreamParser parser) throws IOException {
        var values = new ArrayList<Object>(); Object token;
        while ((token = parser.parseNextToken()) != null) {
            ExecutionContext.checkpoint();
            if (!(token instanceof Operator) && !(token instanceof COSBase)) { throw PdfObjects.invalid(); }
            if (++cachedTokens > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); }
            values.add(token);
        }
        return List.copyOf(values);
    }

    void operator(boolean editing) throws IOException {
        ExecutionContext.checkpoint();
        long count = editing ? ++editOperators : ++validationOperators;
        if (count > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
    }

    void enter(PDFormXObject form, int depth) throws IOException {
        if (depth > limits.maxDepth()) { throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT, 0, -1); }
        if (!active.add(form.getCOSObject())) { throw PdfObjects.invalid(); }
    }
    void leave(PDFormXObject form) { active.remove(form.getCOSObject()); }

    PDFormXObject form(PDResources resources, List<COSBase> operands) throws IOException {
        if (operands.size() != 1 || !(operands.getFirst() instanceof COSName name) || resources == null
                || !(resources.getCOSObject().getDictionaryObject(COSName.XOBJECT) instanceof COSDictionary objects)
                || objects instanceof COSStream || !(objects.getDictionaryObject(name) instanceof COSStream stream)) { throw PdfObjects.invalid(); }
        String subtype = stream.getNameAsString(COSName.SUBTYPE, "");
        if (subtype.equals("Image")) { return null; }
        if (!subtype.equals("Form")) { throw PdfObjects.invalid(); }
        return new PDFormXObject(stream);
    }

    boolean supported(PDFormXObject form) throws IOException {
        var dictionary = form.getCOSObject();
        if (dictionary.containsKey(COSName.FORMTYPE)
                && (!(dictionary.getDictionaryObject(COSName.FORMTYPE) instanceof COSInteger type) || type.longValue() != 1)) { throw PdfObjects.invalid(); }
        var box = array(dictionary.getDictionaryObject(COSName.BBOX), 4);
        double left = number(box.getObject(0)); double bottom = number(box.getObject(1));
        double right = number(box.getObject(2)); double top = number(box.getObject(3));
        if (right <= left || top <= bottom || !Float.isFinite((float) (right - left)) || !Float.isFinite((float) (top - bottom))) { throw PdfObjects.invalid(); }
        if (dictionary.containsKey(COSName.MATRIX)) {
            var matrix = array(dictionary.getDictionaryObject(COSName.MATRIX), 6);
            for (int i = 0; i < matrix.size(); i++) { number(matrix.getObject(i)); }
        }
        if (dictionary.containsKey(COSName.RESOURCES)) { resources(form, null); }
        boolean supported = !dictionary.containsKey(COSName.STRUCT_PARENTS) && !dictionary.containsKey(COSName.STRUCT_PARENT);
        if (!dictionary.containsKey(COSName.GROUP)) { return supported; }
        if (!(dictionary.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group) || group instanceof COSStream
                || !(group.getDictionaryObject(COSName.S) instanceof COSName)) { throw PdfObjects.invalid(); }
        return false;
    }

    PDResources resources(PDFormXObject form, PDResources inherited) throws IOException {
        if (!form.getCOSObject().containsKey(COSName.RESOURCES)) { return inherited; }
        if (!(form.getCOSObject().getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary resources) || resources instanceof COSStream) { throw PdfObjects.invalid(); }
        return new PDResources(resources);
    }

    static void requireFont(PDResources resources, COSName name) throws IOException {
        if (resources == null || !(resources.getCOSObject().getDictionaryObject(COSName.FONT) instanceof COSDictionary fonts)
                || fonts instanceof COSStream || !(fonts.getDictionaryObject(name) instanceof COSDictionary font) || font instanceof COSStream) { throw PdfObjects.invalid(); }
    }

    private static COSArray array(COSBase value, int count) throws IOException {
        if (!(value instanceof COSArray array) || array.size() != count) { throw PdfObjects.invalid(); }
        return array;
    }
    private static double number(COSBase value) throws IOException {
        if (!(value instanceof COSNumber number) || !Float.isFinite(number.floatValue())) { throw PdfObjects.invalid(); }
        return number.floatValue();
    }
}
