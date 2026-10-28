// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.mirakyux.export4j.model.ExecutionContext;

/** Offline DocMDP effective-revision comparison, separate from cryptographic/trust verification.
 * It compares the signed revision with the current page content/resources, catalog, metadata, field
 * definitions/values and annotations. Unknown certification structures return INDETERMINATE.
 * DSS/document-timestamp updates are classified separately; their cryptographic evidence is checked by PdfPades.
 */
public final class PdfDocMdp {
    private PdfDocMdp() { }
    public enum Status { NOT_CERTIFIED, PERMITTED, FORBIDDEN, INDETERMINATE }
    public record Report(Status status, int permission, long certifiedRevisionBytes, List<String> changes) {
        public Report { changes = List.copyOf(changes); }
    }
    private static final Set<COSName> CATALOG = Set.of(COSName.PAGES, COSName.ACRO_FORM, COSName.PERMS, COSName.getPDFName("DSS"));
    private static final Set<COSName> FIELD_VOLATILE = Set.of(COSName.V, COSName.AP, COSName.AS, COSName.M, COSName.PARENT, COSName.P, COSName.KIDS);

    public static Report verify(Path source, PdfSignatureVerifier.Limits limits) throws IOException {
        ExecutionContext.checkpoint(); byte[] bytes;
        try (var stream = Files.newInputStream(source)) { bytes = stream.readNBytes(limits.maxInputBytes() + 1); }
        if (bytes.length > limits.maxInputBytes()) { throw new IOException("DocMDP input budget exceeded"); }
        try (var current = Loader.loadPDF(bytes)) {
            if (current.isEncrypted()) { throw new IOException("encrypted DocMDP comparison is unsupported"); }
            var permissions = current.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PERMS);
            if (permissions == null) { return new Report(Status.NOT_CERTIFIED, 0, 0, List.of()); }
            if (permissions.size() != 1 || !(permissions.getDictionaryObject(COSName.DOCMDP) instanceof COSDictionary signature)) { return indeterminate(0); }
            var prepared = PdfSignatureVerifier.prepare(signature, bytes, limits, false);
            int permission = permission(signature); if (permission == 0) { return indeterminate(prepared.end()); }
            if (prepared.end() == bytes.length) { return new Report(Status.PERMITTED, permission, prepared.end(), List.of()); }
            try (var signed = Loader.loadPDF(Arrays.copyOf(bytes, prepared.end()))) {
                var originalPermissions = signed.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PERMS);
                if (originalPermissions == null || !(originalPermissions.getDictionaryObject(COSName.DOCMDP) instanceof COSDictionary original)
                        || permission(original) != permission || !Arrays.equals(certificationHash(original), certificationHash(signature))) { return new Report(Status.FORBIDDEN, permission, prepared.end(), List.of("CERTIFICATION_CHANGED")); }
                return compare(signed, current, permission, prepared.end(), limits);
            }
        }
    }

    private static Report compare(PDDocument signed, PDDocument current, int permission, long revision, PdfSignatureVerifier.Limits limits) throws IOException {
        var changes = new ArrayList<String>(); boolean forbidden = false;
        if (signed.getNumberOfPages() != current.getNumberOfPages()) { return new Report(Status.FORBIDDEN, permission, revision, List.of("PAGE_TREE_CHANGED")); }
        for (int page = 0; page < signed.getNumberOfPages(); page++) {
            ExecutionContext.checkpoint(); var oldPage = effectivePage(signed.getPage(page)); var newPage = effectivePage(current.getPage(page));
            if (!Arrays.equals(hash(oldPage, Set.of(COSName.PARENT, COSName.ANNOTS)), hash(newPage, Set.of(COSName.PARENT, COSName.ANNOTS)))) { changes.add("PAGE_CONTENT_OR_RESOURCES_CHANGED"); forbidden = true; }
            var oldAnnotations = annotations(oldPage, permission); var newAnnotations = annotations(newPage, permission);
            if (!Arrays.equals(oldAnnotations.annotations(), newAnnotations.annotations())) { changes.add("ANNOTATIONS_CHANGED"); if (permission < 3) { forbidden = true; } }
            if (!Arrays.equals(oldAnnotations.widgets(), newAnnotations.widgets())) { changes.add("WIDGET_DEFINITIONS_CHANGED"); forbidden = true; }
        }
        if (!Arrays.equals(hash(signed.getDocumentCatalog().getCOSObject(), CATALOG), hash(current.getDocumentCatalog().getCOSObject(), CATALOG))
                || !Arrays.equals(hash(signed.getDocumentInformation().getCOSObject(), Set.of()), hash(current.getDocumentInformation().getCOSObject(), Set.of()))) { changes.add("CATALOG_OR_METADATA_CHANGED"); forbidden = true; }
        var oldFields = fields(signed); var newFields = fields(current);
        if (!Arrays.equals(oldFields.definitions(), newFields.definitions())) { changes.add("FIELD_DEFINITIONS_CHANGED"); forbidden = true; }
        if (!Arrays.equals(hash(oldFields.resources(), Set.of()), hash(newFields.resources(), Set.of()))) {
            changes.add("FORM_DEFAULT_RESOURCES_CHANGED");
            if (permission == 1 || !resourceExtension(oldFields.resources(), newFields.resources())) { forbidden = true; }
        }
        if (!Arrays.equals(oldFields.values(), newFields.values())) { changes.add("FIELD_VALUES_OR_APPEARANCES_CHANGED"); if (permission == 1) { forbidden = true; } }
        int oldSignatures = PdfSignatureInputs.signatures(signed).size(), newSignatures = PdfSignatureInputs.signatures(current).size();
        if (newSignatures < oldSignatures || newSignatures > limits.maxSignatures()) { changes.add("SIGNATURES_REMOVED_OR_EXCESSIVE"); forbidden = true; }
        else if (newSignatures > oldSignatures) {
            boolean approval = PdfSignatureInputs.signatures(current).stream().filter(item -> !"ETSI.RFC3161".equals(item.getSubFilter())).count()
                    > PdfSignatureInputs.signatures(signed).stream().filter(item -> !"ETSI.RFC3161".equals(item.getSubFilter())).count();
            changes.add(approval ? "APPROVAL_SIGNATURE_ADDED" : "DOCUMENT_TIMESTAMP_ADDED"); if (approval && permission == 1) { forbidden = true; }
        }
        var oldDss = signed.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("DSS"));
        var newDss = current.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("DSS"));
        if (!Arrays.equals(hash(oldDss, Set.of()), hash(newDss, Set.of()))) { changes.add("DSS_EVIDENCE_UPDATED"); }
        return new Report(forbidden ? Status.FORBIDDEN : changes.isEmpty() ? Status.INDETERMINATE : Status.PERMITTED, permission, revision, changes.stream().distinct().toList());
    }

    private record Fields(byte[] definitions, byte[] values, COSDictionary resources) { }
    private record Visit(COSDictionary dictionary, String name, COSName type) { }
    private static Fields fields(PDDocument document) throws IOException {
        var form = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.ACRO_FORM);
        if (form == null) { return new Fields(hash(null, Set.of()), hash(null, Set.of()), null); }
        var definitions = new COSDictionary(); var values = new COSDictionary();
        var general = new COSDictionary(form); general.removeItem(COSName.FIELDS); general.removeItem(COSName.SIG_FLAGS);
        general.removeItem(COSName.DR);
        definitions.setItem(COSName.getPDFName("General"), general);
        var pending = new ArrayDeque<Visit>(); var roots = form.getCOSArray(COSName.FIELDS);
        if (roots != null) { for (int i = 0; i < roots.size(); i++) { if (!(roots.getObject(i) instanceof COSDictionary field)) { throw new IOException("malformed DocMDP field tree"); } pending.add(new Visit(field, "", null)); } }
        var visited = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>()); int widgets = 0;
        while (!pending.isEmpty()) {
            ExecutionContext.checkpoint(); var visit = pending.removeFirst(); var field = visit.dictionary();
            if (visited.size() >= 10_000 || !visited.add(field)) { throw new IOException("DocMDP field tree budget/cycle violation"); }
            var type = field.getCOSName(COSName.FT, visit.type()); if (COSName.SIG.equals(type)) { continue; }
            String partial = field.getString(COSName.T, ""); String name = visit.name().isEmpty() ? partial : partial.isEmpty() ? visit.name() + ".widget" + widgets++ : visit.name() + "." + partial;
            if (name.isEmpty() || definitions.containsKey(COSName.getPDFName(name))) { throw new IOException("ambiguous DocMDP field name"); }
            var definition = new COSDictionary(field); for (var key : FIELD_VOLATILE) { definition.removeItem(key); } definition.setItem(COSName.FT, type);
            definitions.setItem(COSName.getPDFName(name), definition);
            var current = new COSDictionary(); for (var key : Set.of(COSName.V, COSName.AP, COSName.AS, COSName.M)) { var value = field.getDictionaryObject(key); if (value != null) { current.setItem(key, value); } } values.setItem(COSName.getPDFName(name), current);
            var kids = field.getCOSArray(COSName.KIDS); if (kids != null) { for (int i = 0; i < kids.size(); i++) { if (!(kids.getObject(i) instanceof COSDictionary child)) { throw new IOException("malformed DocMDP field child"); } pending.add(new Visit(child, name, type)); } }
        }
        return new Fields(hash(definitions, Set.of()), hash(values, Set.of()), form.getCOSDictionary(COSName.DR));
    }

    private static boolean resourceExtension(COSDictionary before, COSDictionary after) throws IOException {
        if (after == null) { return before == null; }
        if (before != null) { for (var key : before.keySet()) {
            if (!COSName.FONT.equals(key) && !Arrays.equals(hash(before.getDictionaryObject(key), Set.of()), hash(after.getDictionaryObject(key), Set.of()))) { return false; }
        } }
        for (var key : after.keySet()) { if (!COSName.FONT.equals(key) && (before == null || !before.containsKey(key))) { return false; } }
        var oldFonts = before == null ? null : before.getCOSDictionary(COSName.FONT); var newFonts = after.getCOSDictionary(COSName.FONT);
        if (oldFonts != null) { for (var key : oldFonts.keySet()) { if (newFonts == null || !Arrays.equals(hash(oldFonts.getDictionaryObject(key), Set.of()), hash(newFonts.getDictionaryObject(key), Set.of()))) { return false; } } }
        if (newFonts != null) { for (var key : newFonts.keySet()) { if (!(newFonts.getDictionaryObject(key) instanceof COSDictionary font) || !COSName.FONT.equals(font.getCOSName(COSName.TYPE))) { return false; } } }
        return true;
    }

    private record AnnotationState(byte[] annotations, byte[] widgets) { }
    private static AnnotationState annotations(COSDictionary page, int permission) throws IOException {
        var result = new COSArray(); var widgets = new COSArray(); var annotations = page.getCOSArray(COSName.ANNOTS);
        if (annotations != null) { for (int i = 0; i < annotations.size(); i++) {
            if (!(annotations.getObject(i) instanceof COSDictionary annotation)) { throw new IOException("malformed DocMDP annotation"); }
            if (COSName.WIDGET.equals(annotation.getCOSName(COSName.SUBTYPE))) {
                var owner = annotation; var visited = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
                while (owner != null && visited.add(owner) && owner.getCOSName(COSName.FT) == null) { owner = owner.getCOSDictionary(COSName.PARENT); }
                if (owner != null && COSName.SIG.equals(owner.getCOSName(COSName.FT))) { continue; }
                var copy = new COSDictionary(annotation); copy.removeItem(COSName.P); copy.removeItem(COSName.PARENT);
                if (permission >= 2) { for (var key : Set.of(COSName.V, COSName.AP, COSName.AS, COSName.M)) { copy.removeItem(key); } } widgets.add(copy);
            } else { var copy = new COSDictionary(annotation); copy.removeItem(COSName.P); result.add(copy); }
        } }
        return new AnnotationState(hash(result, Set.of()), hash(widgets, Set.of()));
    }

    private static int permission(COSDictionary signature) {
        var references = signature.getCOSArray(COSName.getPDFName("Reference"));
        if (references == null || references.size() != 1 || !(references.getObject(0) instanceof COSDictionary reference)
                || !"DocMDP".equals(reference.getNameAsString(COSName.getPDFName("TransformMethod")))) { return 0; }
        var parameters = reference.getCOSDictionary(COSName.getPDFName("TransformParams")); if (parameters == null) { return 0; }
        int result = parameters.getInt(COSName.P, 0); return result >= 1 && result <= 3 ? result : 0;
    }
    private static COSDictionary effectivePage(org.apache.pdfbox.pdmodel.PDPage page) {
        var result = new COSDictionary(page.getCOSObject()); result.setItem(COSName.RESOURCES, page.getResources()); result.setItem(COSName.MEDIA_BOX, page.getMediaBox()); result.setItem(COSName.CROP_BOX, page.getCropBox()); result.setInt(COSName.ROTATE, page.getRotation()); return result;
    }
    private static byte[] certificationHash(COSDictionary signature) throws IOException {
        var copy = new COSDictionary(signature); var references = signature.getCOSArray(COSName.getPDFName("Reference")); var replacement = new COSArray();
        if (references != null) { for (int i = 0; i < references.size(); i++) { if (!(references.getObject(i) instanceof COSDictionary value)) { throw new IOException("invalid certification reference"); } var reference = new COSDictionary(value); reference.removeItem(COSName.getPDFName("Data")); replacement.add(reference); } copy.setItem(COSName.getPDFName("Reference"), replacement); }
        return hash(copy, Set.of());
    }
    private static Report indeterminate(long revision) { return new Report(Status.INDETERMINATE, 0, revision, List.of("UNSUPPORTED_CERTIFICATION_STRUCTURE")); }

    private static byte[] hash(COSBase value, Set<COSName> omitted) throws IOException {
        try { var digest = MessageDigest.getInstance("SHA-256"); canonical(value, omitted, digest, new IdentityHashMap<>(), 0, new long[2]); return digest.digest(); }
        catch (NoSuchAlgorithmException failure) { throw new IOException("DocMDP digest is unavailable", failure); }
    }
    private static void canonical(COSBase value, Set<COSName> omitted, MessageDigest digest, Map<COSBase, Integer> seen, int depth, long[] budget) throws IOException {
        ExecutionContext.checkpoint(); if (depth > 64 || ++budget[0] > 100_000) { throw new IOException("DocMDP comparison structure budget exceeded"); }
        if (value instanceof COSObject reference) { value = reference.getObject(); }
        if (value == null) { text(digest, "null"); return; }
        if (value instanceof COSDictionary || value instanceof COSArray) { var previous = seen.putIfAbsent(value, seen.size()); if (previous != null) { text(digest, "ref" + previous); return; } }
        if (value instanceof COSDictionary dictionary) {
            text(digest, value instanceof COSStream ? "stream" : "dictionary");
            var keys = new ArrayList<>(dictionary.keySet()); keys.sort(Comparator.comparing(COSName::getName));
            for (var key : keys) {
                if (depth == 0 && omitted.contains(key) || COSName.PARENT.equals(key) || value instanceof COSStream && Set.of(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS).contains(key)) { continue; }
                text(digest, key.getName()); canonical(dictionary.getDictionaryObject(key), Set.of(), digest, seen, depth + 1, budget);
            }
            if (value instanceof COSStream stream) { try (var input = stream.createInputStream()) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) { ExecutionContext.checkpoint(); if (count > 64L * 1_048_576 - budget[1]) { throw new IOException("DocMDP comparison decoded byte budget exceeded"); } budget[1] += count; digest.update(buffer, 0, count); } } }
        } else if (value instanceof COSArray array) { text(digest, "array" + array.size()); for (var item : array) { canonical(item, Set.of(), digest, seen, depth + 1, budget); } }
        else if (value instanceof COSString string) { text(digest, "string" + string.getBytes().length); digest.update(string.getBytes()); }
        else { text(digest, value.getClass().getSimpleName() + ":" + value); }
        text(digest, "end");
    }
    private static void text(MessageDigest digest, String value) { var bytes = value.getBytes(StandardCharsets.UTF_8); digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16)); digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length); digest.update(bytes); }
}
