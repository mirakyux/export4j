// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;

/** Operation-confined validation of graphics states whose effects do not change recovered text
 * or image opacity. Unsupported valid states skip the page; malformed supported properties fail.
 */
final class PdfSemanticGraphicsState {
    private static final Set<String> INTENTS = Set.of("AbsoluteColorimetric", "RelativeColorimetric", "Saturation", "Perceptual");
    private final PdfLimits limits;
    private final Map<COSDictionary, Boolean> checked = new IdentityHashMap<>();
    private long work;

    PdfSemanticGraphicsState(PdfLimits limits) { this.limits = limits; }

    /** Rendering may preserve validated opacity/blend/soft-mask state that a semantic model cannot express. */
    boolean renderable(PDResources resources, COSName key) throws IOException {
        supported(resources, List.of(key));
        var state = resources.getExtGState(key).getCOSObject();
        var known = Set.of("Type", "LW", "LC", "LJ", "ML", "D", "RI", "SA", "CA", "ca", "BM", "SMask", "Font", "AIS", "TK", "OP", "op", "OPM");
        for (var name : state.keySet()) { if (!known.contains(name.getName())) { return false; } }
        if (state.containsKey(COSName.RI) && !INTENTS.contains(name(state.getDictionaryObject(COSName.RI)))) { return false; }
        var modes = Set.of("Normal", "Compatible", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "ColorDodge", "ColorBurn", "HardLight", "SoftLight", "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity");
        var blend = state.getDictionaryObject(COSName.BM);
        if (blend instanceof COSName item && !modes.contains(item.getName())) { return false; }
        if (blend instanceof COSArray items) {
            for (var item : items) { if (!(item instanceof COSName mode) || !modes.contains(mode.getName())) { return false; } }
        }
        if (state.getDictionaryObject(COSName.SMASK) instanceof COSDictionary mask) {
            for (var name : mask.keySet()) { if (!Set.of("Type", "S", "G", "BC").contains(name.getName())) { return false; } }
        }
        // PDFBox's RGB renderer does not implement overprint simulation. Never call its ignored
        // overprint switches a supported appearance, even when the input dictionary is well formed.
        return !state.getBoolean(COSName.OP, false) && !state.getBoolean(COSName.getPDFName("op"), false);
    }

    boolean supported(PDResources resources, List<COSBase> operands) throws IOException {
        checkpoint();
        if (operands.size() != 1 || !(operands.getFirst() instanceof COSName name) || resources == null) { throw PdfObjects.invalid(); }
        var group = resources.getCOSObject().getDictionaryObject(COSName.EXT_G_STATE);
        if (!(group instanceof COSDictionary states) || group instanceof COSStream) { throw PdfObjects.invalid(); }
        var value = states.getDictionaryObject(name);
        if (!(value instanceof COSDictionary state) || value instanceof COSStream) { throw PdfObjects.invalid(); }
        var previous = checked.get(state);
        if (previous != null) { return previous; }
        boolean supported = true;
        for (var key : state.keySet()) {
            checkpoint();
            var item = state.getDictionaryObject(key);
            switch (key.getName()) {
                case "Type" -> { if (!name(item).equals("ExtGState")) { throw PdfObjects.invalid(); } }
                case "LW" -> number(item, 0, Double.MAX_VALUE);
                case "LC", "LJ" -> integer(item, 0, 2);
                case "ML" -> number(item, 1, Double.MAX_VALUE);
                case "D" -> dash(item);
                case "RI" -> { if (!INTENTS.contains(name(item))) { supported = false; } }
                case "SA" -> bool(item);
                case "CA", "ca" -> { if (number(item, 0, 1) != 1) { supported = false; } }
                case "BM" -> { if (!blend(item)) { supported = false; } }
                case "SMask" -> { if (!mask(item)) { supported = false; } }
                case "Font" -> { font(item); supported = false; }
                case "AIS", "TK", "OP", "op" -> { bool(item); supported = false; }
                case "OPM" -> { integer(item, 0, 1); supported = false; }
                default -> supported = false;
            }
        }
        checked.put(state, supported);
        return supported;
    }

    private void checkpoint() throws IOException {
        ExecutionContext.checkpoint();
        if (++work > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); }
    }

    private static double number(COSBase value, double minimum, double maximum) throws IOException {
        if (!(value instanceof COSNumber number)) { throw PdfObjects.invalid(); }
        double result = number instanceof COSInteger ? number.longValue() : number.floatValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) { throw PdfObjects.invalid(); }
        return result;
    }

    private static void integer(COSBase value, int minimum, int maximum) throws IOException {
        if (!(value instanceof COSInteger number) || number.longValue() < minimum || number.longValue() > maximum) { throw PdfObjects.invalid(); }
    }

    private static String name(COSBase value) throws IOException {
        if (!(value instanceof COSName name)) { throw PdfObjects.invalid(); }
        return name.getName();
    }

    private static void bool(COSBase value) throws IOException { if (!(value instanceof COSBoolean)) { throw PdfObjects.invalid(); } }

    private void dash(COSBase value) throws IOException {
        if (!(value instanceof COSArray setting) || setting.size() != 2 || !(setting.getObject(0) instanceof COSArray lengths)) { throw PdfObjects.invalid(); }
        number(setting.getObject(1), 0, Double.MAX_VALUE);
        boolean positive = lengths.size() == 0;
        for (int i = 0; i < lengths.size(); i++) {
            checkpoint();
            if (number(lengths.getObject(i), 0, Double.MAX_VALUE) > 0) { positive = true; }
        }
        if (!positive) { throw PdfObjects.invalid(); }
    }

    private boolean blend(COSBase value) throws IOException {
        if (value instanceof COSName) { return Set.of("Normal", "Compatible").contains(name(value)); }
        if (!(value instanceof COSArray modes) || modes.size() == 0) { throw PdfObjects.invalid(); }
        for (int i = 0; i < modes.size(); i++) { checkpoint(); name(modes.getObject(i)); }
        // The first release does not interpret a renderer's preference among alternative modes.
        return false;
    }

    private boolean mask(COSBase value) throws IOException {
        if (value instanceof COSName) {
            if (!name(value).equals("None")) { throw PdfObjects.invalid(); }
            return true;
        }
        if (!(value instanceof COSDictionary mask) || value instanceof COSStream
                || !Set.of("Alpha", "Luminosity").contains(name(mask.getDictionaryObject(COSName.S)))
                || !(mask.getDictionaryObject(COSName.G) instanceof COSStream group)
                || !"Form".equals(group.getNameAsString(COSName.SUBTYPE))) { throw PdfObjects.invalid(); }
        var attributes = group.getDictionaryObject(COSName.GROUP);
        if (!(attributes instanceof COSDictionary dictionary) || attributes instanceof COSStream
                || !"Transparency".equals(name(dictionary.getDictionaryObject(COSName.S)))) { throw PdfObjects.invalid(); }
        if (mask.containsKey(COSName.BC)) {
            if (!(mask.getDictionaryObject(COSName.BC) instanceof COSArray backdrop) || backdrop.size() == 0) { throw PdfObjects.invalid(); }
            for (int i = 0; i < backdrop.size(); i++) { checkpoint(); number(backdrop.getObject(i), 0, 1); }
        }
        return false;
    }

    private static void font(COSBase value) throws IOException {
        if (!(value instanceof COSArray setting) || setting.size() != 2
                || !(setting.getObject(0) instanceof COSDictionary font) || font instanceof COSStream) { throw PdfObjects.invalid(); }
        number(setting.getObject(1), -Double.MAX_VALUE, Double.MAX_VALUE);
    }
}
