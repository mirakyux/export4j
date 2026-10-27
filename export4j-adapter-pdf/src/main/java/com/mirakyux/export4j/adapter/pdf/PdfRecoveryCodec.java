// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.adapter.template.EmbeddedRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

/** Standard PDF attachment recovery. Requires the PDF adapter at the call site; no global lookup. */
public final class PdfRecoveryCodec implements EmbeddedRecoveryCodec {
    private static final String NAME = "export4j-recovery.zip";
    private static final String DESCRIPTION = "Export4j recovery;source-bytes=";

    @Override public String format() { return "PDF"; }

    @Override public byte[] encode(byte[] standard, byte[] companion, XmlModelRecovery.Limits limits) throws IOException {
        if (standard.length > limits.maxDocumentBytes()) { throw new IOException("PDF recovery byte budget exceeded"); }
        try (var document = load(standard)) {
            available(document);
            var files = PdfObjects.files(document, PdfLimits.DEFAULT);
            if (files.containsKey(NAME)) { throw new IOException("PDF already contains recovery attachment"); }
            if (companion == null) { return standard.clone(); }
            if (companion.length > limits.bundle().maxEncodedBytes()) { throw new IOException("PDF recovery ZIP budget exceeded"); }
            var embedded = new PDEmbeddedFile(document, new ByteArrayInputStream(companion), COSName.FLATE_DECODE);
            embedded.setSubtype("application/zip"); embedded.setSize(companion.length);
            var specification = new PDComplexFileSpecification();
            specification.setFile(NAME); specification.setFileUnicode(NAME);
            specification.getCOSObject().setString(COSName.DESC, DESCRIPTION + standard.length);
            specification.setEmbeddedFile(embedded); specification.setEmbeddedFileUnicode(embedded);
            files.put(NAME, specification); setFiles(document, files);
            var bytes = new ByteArrayOutputStream(); document.saveIncremental(bytes);
            if (bytes.size() > limits.maxDocumentBytes()) { throw new IOException("embedded PDF byte budget exceeded"); }
            return fixedUpdateId(bytes.toByteArray(), standard, companion);
        }
    }

    @Override public Extracted decode(byte[] current, XmlModelRecovery.Limits limits) throws IOException {
        if (current.length > limits.maxDocumentBytes()) { throw new IOException("PDF recovery byte budget exceeded"); }
        try (var document = load(current)) {
            available(document);
            var files = PdfObjects.files(document, PdfLimits.DEFAULT);
            var specification = files.get(NAME);
            if (specification == null) { return new Extracted(current, null); }
            var embedded = specification.getEmbeddedFileUnicode();
            if (embedded == null || specification.getEmbeddedFile() == null || !embedded.getCOSObject().equals(specification.getEmbeddedFile().getCOSObject())) {
                throw new IOException("invalid PDF recovery attachment");
            }
            String description = specification.getCOSObject().getString(COSName.DESC);
            if (description == null || !description.startsWith(DESCRIPTION)) { throw new IOException("PDF recovery source length missing"); }
            int length;
            try { length = Integer.parseInt(description.substring(DESCRIPTION.length())); }
            catch (NumberFormatException failure) { throw new IOException("invalid PDF recovery source length", failure); }
            if (length < 8 || length > current.length) { throw new IOException("invalid PDF recovery source length"); }
            var archive = new ByteArrayOutputStream();
            try (var input = embedded.createInputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    ExecutionContext.checkpoint();
                    if (count > limits.bundle().maxEncodedBytes() - archive.size()) { throw new IOException("PDF recovery ZIP budget exceeded"); }
                    archive.write(buffer, 0, count);
                }
            }
            byte[] zip = archive.toByteArray();
            byte[] original = Arrays.copyOf(current, length);
            try {
                if (Arrays.equals(current, encode(original, zip, limits))) { return new Extracted(original, zip); }
            } catch (IOException stalePrefix) {
                // A full editor save can move every PDF object and leave the old length in
                // the attachment. The current PDF has already parsed; only the old prefix failed.
            }
            files.remove(NAME); setFiles(document, files);
            var stripped = new ByteArrayOutputStream(); document.save(stripped);
            if (stripped.size() > limits.maxDocumentBytes()) { throw new IOException("PDF fallback byte budget exceeded"); }
            return new Extracted(stripped.toByteArray(), zip);
        }
    }

    private static PDDocument load(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
            throw new IOException("invalid PDF recovery input");
        }
        ExecutionContext.checkpoint();
        return Loader.loadPDF(bytes);
    }
    private static byte[] fixedUpdateId(byte[] output, byte[] source, byte[] companion) throws IOException {
        // PDFBox chooses a fresh second trailer ID for every incremental save. Fix only that
        // generated 32-byte ID so an unchanged attachment can be verified byte for byte.
        byte[] marker = "/ID [<".getBytes(StandardCharsets.US_ASCII);
        int start = -1;
        for (int index = source.length; index <= output.length - marker.length; index++) {
            if (output[index] == marker[0] && Arrays.equals(output, index, index + marker.length, marker, 0, marker.length)) { start = index; }
        }
        if (start < 0) { throw new IOException("PDF incremental trailer ID missing"); }
        int firstEnd = start + marker.length;
        while (firstEnd < output.length && output[firstEnd] != '>') { firstEnd++; }
        int second = firstEnd + 3;
        if (firstEnd - start - marker.length != 64 || second + 66 > output.length
                || output[firstEnd + 1] != ' ' || output[firstEnd + 2] != '<'
                || output[second + 64] != '>' || output[second + 65] != ']') {
            throw new IOException("unsupported PDF incremental trailer ID layout");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(source); ExecutionContext.checkpoint(); digest.update(companion);
            byte[] id = HexFormat.of().withUpperCase().formatHex(digest.digest()).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(id, 0, output, second, id.length);
            return output;
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
    private static void available(PDDocument document) throws IOException {
        if (document.isEncrypted() || !document.getSignatureDictionaries().isEmpty()) {
            throw new IOException("encrypted or signed PDF cannot carry model recovery");
        }
        var catalog = document.getDocumentCatalog();
        if (catalog.getCOSObject().containsKey(COSName.OUTPUT_INTENTS)) {
            throw new IOException("PDF output intent requires separate archival compatibility validation");
        }
        var metadata = catalog.getMetadata();
        if (metadata != null) {
            try (var input = metadata.createInputStream()) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) { throw new IOException("PDF metadata budget exceeded"); }
                var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var tree = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                if (tree.getElementsByTagNameNS("http://www.aiim.org/pdfa/ns/id/", "part").getLength() > 0) {
                    throw new IOException("PDF/A requires separate embedded-file conformance validation");
                }
            } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException failure) {
                throw new IOException("invalid PDF recovery metadata", failure);
            }
        }
    }
    private static void setFiles(PDDocument document, Map<String, PDComplexFileSpecification> files) {
        var catalog = document.getDocumentCatalog();
        var names = catalog.getNames(); if (names == null) { names = new PDDocumentNameDictionary(catalog); }
        if (files.isEmpty()) { names.getCOSObject().removeItem(COSName.EMBEDDED_FILES); }
        else { var tree = new PDEmbeddedFilesNameTreeNode(); tree.setNames(new TreeMap<>(files)); names.setEmbeddedFiles(tree); }
        if (names.getCOSObject().size() == 0) { catalog.setNames(null); } else { catalog.setNames(names); }
        catalog.getCOSObject().setNeedToBeUpdated(true);
    }
}
