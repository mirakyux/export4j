// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.mirakyux.export4j.model.ExecutionContext;

/** Bounded raw-field traversal; PDFBox convenience iterators are not used on untrusted field trees. */
final class PdfSignatureInputs {
    private PdfSignatureInputs() { }
    private record Field(COSDictionary dictionary, COSName inherited) { }
    static List<PDSignature> signatures(PDDocument pdf) throws IOException {
        var result = new ArrayList<PDSignature>(); var pending = new ArrayDeque<Field>();
        var form = pdf.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM);
        if (form == null) { return result; }
        if (!(form instanceof COSDictionary dictionary)) { throw new IOException("malformed PDF signature form"); }
        append(dictionary.getDictionaryObject(COSName.FIELDS), null, pending);
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>()), values = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint(); var field = pending.removeFirst(); if (seen.size() >= 10_000 || !seen.add(field.dictionary())) { throw new IOException("PDF signature field budget/cycle violation"); }
            var type = field.dictionary().getCOSName(COSName.FT, field.inherited()); var value = field.dictionary().getDictionaryObject(COSName.V);
            if (COSName.SIG.equals(type) && value != null) {
                if (!(value instanceof COSDictionary signature) || !values.add(signature) || result.size() >= 64) { throw new IOException("malformed, aliased or excessive PDF signatures"); }
                result.add(new PDSignature(signature));
            }
            append(field.dictionary().getDictionaryObject(COSName.KIDS), type, pending);
        }
        return result;
    }
    private static void append(COSBase value, COSName type, ArrayDeque<Field> pending) throws IOException {
        if (value == null) { return; } if (!(value instanceof COSArray array) || array.size() > 10_000 || pending.size() > 10_000 - array.size()) { throw new IOException("PDF signature field budget exceeded"); }
        for (int i = 0; i < array.size(); i++) { if (!(array.getObject(i) instanceof COSDictionary field)) { throw new IOException("malformed PDF signature field child"); } pending.add(new Field(field, type)); }
    }
    static void passive(PDDocument pdf) throws IOException {
        var pending = new ArrayDeque<COSBase>(); pending.add(pdf.getDocumentCatalog().getCOSObject()); var visited = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint(); var value = pending.removeFirst(); if (value instanceof COSObject reference) { value = reference.getObject(); } if (value == null || !visited.add(value)) { continue; }
            if (visited.size() > 100_000) { throw new IOException("PDF signature input object budget exceeded"); }
            if (value instanceof COSDictionary dictionary) {
                if (dictionary.containsKey(COSName.OPEN_ACTION) || dictionary.containsKey(COSName.AA) || dictionary.containsKey(COSName.JS) || dictionary.containsKey(COSName.JAVA_SCRIPT)
                        || dictionary.containsKey(COSName.XFA) || dictionary.containsKey(COSName.getPDFName("RichMediaContent")) || Set.of("JavaScript", "Launch", "SubmitForm", "ImportData", "GoToR", "Rendition", "Movie", "Sound").contains(dictionary.getNameAsString(COSName.S, ""))) { throw new IOException("active PDF content is unsupported for PAdES"); }
                pending.addAll(dictionary.getValues());
            } else if (value instanceof COSArray array) { for (var child : array) { pending.add(child); } }
        }
    }
}
