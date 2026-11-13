// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.EOFException;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;

/** Ordinary PDF writer with bounded per-page PDFBox state and disk-backed xref/page indices.
 * Each accepted page is rendered with the ordinary PdfBackend, serialized to a bounded discard sink
 * to finalize font subsets and measure its byte budget, copied directly from its independent object
 * graph, and released before return. No intermediate PDF file or parser is required.
 * Equivalent font and image graphs reuse previously written objects; up to 256 finalized TrueType
 * program streams are also shared independently of page-local CID and Unicode mappings.
 * bounded session-local caches retain fingerprints, output object numbers and at most eight parsed
 * horizontal TrueType programs backed by at most 32 MiB of encoded source data. PDF fonts remain page-local.
 * Up to 64 finalized horizontal TrueType subset snapshots reuse at most 16 MiB including metadata;
 * subset keys include source identity and every Unicode/glyph-ID pair. Every page still undergoes
 * ordinary PDF serialization and its exact byte admission check.
 * Up to 64 simple lossless image programs reuse at most 32 MiB of compressed image and alpha bytes;
 * their PDF image objects and streams are rebuilt independently for every page.
 * The temporary-work byte budget conservatively includes the current page's serialized-equivalent
 * bytes until copying completes, preserving its existing admission boundary without storing those
 * bytes on disk. Only the xref and page indices require temporary files.
 * Each of the three indices caches at most two 8 KiB blocks; reserved logical bytes still count
 * exactly toward the temporary budget, without charging cache-block padding or preallocating it.
 * Does not apply encryption, archival certification, signatures or accessibility certification.
 * Output is borrowed; the supplied temporary parent is borrowed and must already exist.
 * Instances are immutable and reusable; sessions are confined to their opening thread.
 */
public final class PdfSpoolBackend implements PageBackend {
    private final Path temporaryDirectory;
    private final PdfLimits pageLimits;
    private final PdfSpoolLimits limits;
    private final PdfFontCache fontCache;

    public PdfSpoolBackend(Path temporaryDirectory) {
        this(temporaryDirectory, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT);
    }

    /** Explicit existing local temporary parent; the directory remains caller-owned. */
    public PdfSpoolBackend(String temporaryDirectory) {
        this(Path.of(Objects.requireNonNull(temporaryDirectory, "temporaryDirectory")));
    }

    /** Explicit existing local temporary parent; the directory remains caller-owned. */
    public PdfSpoolBackend(File temporaryDirectory) {
        this(Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").toPath());
    }

    /** Local temporary parent and explicit budgets, with the same ownership as the Path constructor. */
    public PdfSpoolBackend(String temporaryDirectory, PdfLimits pageLimits, PdfSpoolLimits limits) {
        this(Path.of(Objects.requireNonNull(temporaryDirectory, "temporaryDirectory")), pageLimits, limits);
    }

    /** Local temporary parent and explicit budgets, with the same ownership as the Path constructor. */
    public PdfSpoolBackend(File temporaryDirectory, PdfLimits pageLimits, PdfSpoolLimits limits) {
        this(Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").toPath(), pageLimits, limits);
    }

    public PdfSpoolBackend(Path temporaryDirectory, PdfLimits pageLimits, PdfSpoolLimits limits) {
        this(temporaryDirectory, pageLimits, limits, PdfFontCache.defaultCache());
    }

    /** Uses the caller's existing temporary parent and borrows its thread-safe font cache. */
    public PdfSpoolBackend(Path temporaryDirectory, PdfLimits pageLimits, PdfSpoolLimits limits, PdfFontCache fontCache) {
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").toAbsolutePath();
        this.pageLimits = Objects.requireNonNull(pageLimits, "pageLimits");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.fontCache = Objects.requireNonNull(fontCache, "fontCache");
    }

    @Override public PageWriter open(DocumentMetadata metadata, OutputStream output) throws IOException {
        return new Session(Objects.requireNonNull(metadata, "metadata"), Objects.requireNonNull(output, "output"));
    }

    private final class Session implements PageWriter {
        private static final int LEAF_PAGES = 256;
        private static final int SHARED_FONTS = 256;
        private static final int SHARED_FONT_PROGRAMS = 256;
        private static final int SHARED_IMAGES = 256;
        private final Thread owner = Thread.currentThread();
        private final DocumentMetadata metadata;
        private final Path directory;
        private final Path xrefFile;
        private final Path leavesFile;
        private final Path pagesFile;
        private final DiskIndex xref;
        private final DiskIndex leaves;
        private final DiskIndex pageIndex;
        private final byte[] xrefEntry = "0000000000 00000 n \n".getBytes(StandardCharsets.US_ASCII);
        private final CountedOutput output;
        private final PdfBackend renderer = new PdfBackend(pageLimits, PdfOptions.DEFAULT, fontCache);
        private final PdfFontPrograms fontPrograms = new PdfFontPrograms(fontCache);
        private final PdfImagePrograms imagePrograms = new PdfImagePrograms();
        private final List<Long> leafPages = new ArrayList<>(LEAF_PAGES);
        private final LinkedHashMap<String, Long> sharedFonts = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<String, Long> sharedFontPrograms = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<String, Long> sharedImages = new LinkedHashMap<>(16, 0.75f, true);
        private long nextObject = 4;
        private long leafObject;
        private int pages;
        private long commands;
        private long serializedPageBytes;
        private boolean terminal;
        private boolean closed;
        private boolean finished;
        private PageNavigation navigation;
        private final java.util.Map<Integer, List<Long>> internalAnnotations = new java.util.HashMap<>();
        private final List<Long> linkObjects = new ArrayList<>();
        private final List<Long> bookmarkObjects = new ArrayList<>();
        private long outlineObject;

        private Session(DocumentMetadata metadata, OutputStream target) throws IOException {
            this.metadata = metadata;
            directory = Files.createTempDirectory(temporaryDirectory, ".export4j-pdf-spool-");
            xrefFile = directory.resolve("xref.bin");
            leavesFile = directory.resolve("leaves.bin");
            pagesFile = directory.resolve("pages.bin");
            DiskIndex openedXref = null;
            DiskIndex openedLeaves = null;
            DiskIndex openedPages = null;
            try {
                openedXref = new DiskIndex(xrefFile);
                openedLeaves = new DiskIndex(leavesFile);
                openedPages = new DiskIndex(pagesFile);
                xref = openedXref; leaves = openedLeaves;
                pageIndex = openedPages;
                output = new CountedOutput(target, limits.maxOutputBytes());
                write("%PDF-1.7\n%");
                output.write(new byte[] {(byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3, '\n'});
            } catch (IOException | RuntimeException | Error failure) {
                if (openedPages != null) { try { openedPages.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } }
                if (openedLeaves != null) { try { openedLeaves.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } }
                if (openedXref != null) { try { openedXref.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } }
                cleanupFiles(failure);
                throw failure;
            }
        }

        @Override public void configureNavigation(PageNavigation value) throws IOException {
            requireActive();
            try {
                if (pages != 0 || navigation != null) { throw new IllegalStateException("navigation must be configured once before pages"); }
                navigation = Objects.requireNonNull(value, "navigation");
                if (value.pages() > limits.maxPages()) { throw new IOException("PDF navigation page budget exceeded"); }
                for (int index = 0; index < value.pages(); index++) {
                    writeIndexLong(pageIndex, reserve()); writeIndexLong(pageIndex, 0);
                }
                for (var link : value.links()) {
                    long object = reserve(); linkObjects.add(object);
                    internalAnnotations.computeIfAbsent(link.pageIndex(), ignored -> new ArrayList<>()).add(object);
                }
                if (!value.bookmarks().isEmpty()) {
                    outlineObject = reserve();
                    for (var ignored : value.bookmarks()) { bookmarkObjects.add(reserve()); }
                }
                checkTemporaryBudget();
            } catch (IOException | RuntimeException | Error failure) { terminal = true; throw failure; }
        }

        @Override public void accept(DisplayPage page) throws IOException { accept(page, List.of()); }

        @Override public void accept(DisplayPage page, List<DocumentExtras.ExternalLink> links) throws IOException {
            requireActive();
            try {
                ExecutionContext.checkpoint();
                Objects.requireNonNull(page, "page");
                links = List.copyOf(links);
                if (pages == limits.maxPages() || navigation != null && pages == navigation.pages()) {
                    throw new IOException("PDF spool page or command budget exceeded");
                }
                long pageCommands = commandCount(page);
                var localLinks = new ArrayList<DocumentExtras.ExternalLink>(links.size());
                for (var link : links) {
                    if (link.pageIndex() != pages) { throw new IOException("PDF spool link page index is not current"); }
                    localLinks.add(new DocumentExtras.ExternalLink(0, link.bounds(), link.destination()));
                }
                var extras = new DocumentExtras(List.of(), List.of(), List.of(), List.of(), localLinks);
                var serialization = new CountedOutput(OutputStream.nullOutputStream(), limits.maxPageBytes());
                renderer.withRenderedPage(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page), extras),
                        serialization, fontPrograms, imagePrograms, rendered -> {
                    // Preserve the existing conservative temporary-work admission boundary even though
                    // the finalized single-page serialization no longer occupies a temporary file.
                    serializedPageBytes = serialization.count;
                    checkTemporaryBudget();
                    if (leafPages.isEmpty()) { leafObject = reserve(); }
                    var copier = new PageObjects(rendered.getCOSObject());
                    if (navigation != null) {
                        pageIndex.seek((long) pages * 16); long assigned = readIndexLong(pageIndex);
                        copier.numbers.put(copier.page, assigned); copier.pending.add(copier.page);
                        writeIndexLong(pageIndex, Double.doubleToLongBits(page.size().height()));
                    }
                    long pageObject = copier.reference(copier.page);
                    copier.copy();
                    leafPages.add(pageObject);
                });
                serializedPageBytes = 0;
                commands += pageCommands; pages++;
                if (leafPages.size() == LEAF_PAGES) { finishLeaf(); }
                checkTemporaryBudget();
                ExecutionContext.checkpoint();
            } catch (IOException | RuntimeException | Error failure) { terminal = true; throw failure; }
        }

        private long commandCount(DisplayPage page) throws IOException {
            long[] count = {0};
            try {
                GraphicsResources.walk(page.commands(), ignored -> {
                    if (count[0] == limits.maxCommands() - commands) {
                        throw new UncheckedIOException(new IOException("PDF spool command budget exceeded"));
                    }
                    count[0]++;
                });
            } catch (UncheckedIOException failure) { throw failure.getCause(); }
            return count[0];
        }

        @Override public void finish() throws IOException {
            requireActive();
            try {
                ExecutionContext.checkpoint();
                if (pages == 0) { throw new IllegalStateException("PDF spool requires at least one page"); }
                if (navigation != null && pages != navigation.pages()) { throw new IOException("PDF navigation final page count differs"); }
                if (!leafPages.isEmpty()) { finishLeaf(); }
                finishNavigation();
                begin(1); write("<< /Type /Catalog /Pages 2 0 R ");
                if (outlineObject != 0) { write("/Outlines " + outlineObject + " 0 R /PageMode /UseOutlines "); }
                write(">>\nendobj\n");
                begin(2); write("<< /Type /Pages /Count " + pages + " /Kids [");
                leaves.seek(0);
                while (leaves.getFilePointer() < leaves.length()) {
                    ExecutionContext.checkpoint(); write(readIndexLong(leaves) + " 0 R ");
                }
                write("] >>\nendobj\n");
                begin(3); write("<< /Producer "); string("Export4j");
                write(" /Title "); string(metadata.title()); write(" /Author "); string(metadata.author());
                write(" >>\nendobj\n");
                long xrefStart = output.count;
                write("xref\n0 " + nextObject + "\n0000000000 65535 f \n");
                xref.seek(8);
                for (long object = 1; object < nextObject; object++) {
                    ExecutionContext.checkpoint();
                    long position = readIndexLong(xref);
                    if (position == 0) { throw new IOException("PDF spool has an unwritten reserved object"); }
                    writeXrefEntry(position);
                }
                write("trailer\n<< /Size " + nextObject + " /Root 1 0 R /Info 3 0 R >>\nstartxref\n"
                        + xrefStart + "\n%%EOF\n");
                output.flush(); finished = true; terminal = true;
            } catch (IOException | RuntimeException | Error failure) { terminal = true; throw failure; }
        }

        private long pageReference(int index) throws IOException { pageIndex.seek((long) index * 16); return readIndexLong(pageIndex); }
        private double pageHeight(int index) throws IOException {
            pageIndex.seek((long) index * 16 + 8); return Double.longBitsToDouble(readIndexLong(pageIndex));
        }
        private void destination(int target, com.mirakyux.export4j.graphics.Point point) throws IOException {
            write("[" + pageReference(target) + " 0 R /XYZ " + decimal(point.x()) + " "
                    + decimal(pageHeight(target) - point.y()) + " null]");
        }
        private String decimal(double value) throws IOException {
            if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) { throw new IOException("PDF navigation geometry exceeded"); }
            return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        private void finishNavigation() throws IOException {
            if (navigation == null) { return; }
            for (int index = 0; index < navigation.links().size(); index++) {
                var link = navigation.links().get(index); var bounds = link.bounds();
                begin(linkObjects.get(index));
                write("<< /Type /Annot /Subtype /Link /Border [0 0 0] /Rect [" + decimal(bounds.x()) + " "
                        + decimal(pageHeight(link.pageIndex()) - bounds.y() - bounds.height()) + " "
                        + decimal(bounds.x() + bounds.width()) + " " + decimal(pageHeight(link.pageIndex()) - bounds.y()) + "] /Dest ");
                destination(link.targetPageIndex(), link.destination()); write(" >>\nendobj\n");
            }
            int count = navigation.bookmarks().size();
            if (count == 0) { return; }
            int[] parent = new int[count], previous = new int[count], next = new int[count], first = new int[count], last = new int[count];
            java.util.Arrays.fill(parent, -1); java.util.Arrays.fill(previous, -1); java.util.Arrays.fill(next, -1);
            java.util.Arrays.fill(first, -1); java.util.Arrays.fill(last, -1);
            int[] stack = new int[count + 1]; java.util.Arrays.fill(stack, -1);
            int firstRoot = -1, lastRoot = -1;
            for (int index = 0; index < count; index++) {
                int level = navigation.bookmarks().get(index).level(); int owner = level == 1 ? -1 : stack[level - 1];
                parent[index] = owner;
                int sibling = owner < 0 ? lastRoot : last[owner];
                if (sibling >= 0) { previous[index] = sibling; next[sibling] = index; }
                if (owner < 0) { if (firstRoot < 0) { firstRoot = index; } lastRoot = index; }
                else { if (first[owner] < 0) { first[owner] = index; } last[owner] = index; }
                stack[level] = index;
            }
            begin(outlineObject); write("<< /Type /Outlines /Count " + count + " /First " + bookmarkObjects.get(firstRoot)
                    + " 0 R /Last " + bookmarkObjects.get(lastRoot) + " 0 R >>\nendobj\n");
            for (int index = 0; index < count; index++) {
                var bookmark = navigation.bookmarks().get(index); begin(bookmarkObjects.get(index));
                write("<< /Title "); string(bookmark.title()); write(" /Parent "
                        + (parent[index] < 0 ? outlineObject : bookmarkObjects.get(parent[index])) + " 0 R ");
                if (previous[index] >= 0) { write("/Prev " + bookmarkObjects.get(previous[index]) + " 0 R "); }
                if (next[index] >= 0) { write("/Next " + bookmarkObjects.get(next[index]) + " 0 R "); }
                if (first[index] >= 0) {
                    write("/First " + bookmarkObjects.get(first[index]) + " 0 R /Last " + bookmarkObjects.get(last[index]) + " 0 R ");
                    int descendants = 0;
                    for (int child = index + 1; child < count && navigation.bookmarks().get(child).level() > bookmark.level(); child++) { descendants++; }
                    write("/Count " + descendants + " ");
                }
                write("/Dest "); destination(bookmark.pageIndex(), bookmark.destination()); write(" >>\nendobj\n");
            }
        }

        private void finishLeaf() throws IOException {
            begin(leafObject);
            write("<< /Type /Pages /Parent 2 0 R /Count " + leafPages.size() + " /Kids [");
            for (long page : leafPages) { write(page + " 0 R "); }
            write("] >>\nendobj\n");
            leaves.seek(leaves.length()); writeIndexLong(leaves, leafObject); leafPages.clear();
        }

        private long reserve() throws IOException {
            ExecutionContext.checkpoint();
            if (nextObject > limits.maxObjects()) { throw new IOException("PDF spool object budget exceeded"); }
            if (nextObject >= limits.maxTemporaryBytes() / Long.BYTES) {
                throw new IOException("PDF spool xref budget exceeded");
            }
            long object = nextObject;
            long required = (object + 1) * Long.BYTES;
            checkTemporaryBudget(required - xref.length());
            xref.setLength(required);
            nextObject++;
            return object;
        }

        private void begin(long object) throws IOException {
            ExecutionContext.checkpoint();
            xref.seek(object * 8); writeIndexLong(xref, output.count);
            write(object + " 0 obj\n");
        }

        private void writeIndexLong(DiskIndex file, long value) throws IOException {
            long end = file.getFilePointer() + Long.BYTES;
            checkTemporaryBudget(Math.max(0, end - file.length()));
            file.writeLong(value);
        }

        private long readIndexLong(DiskIndex file) throws IOException {
            return file.readLong();
        }

        private void writeXrefEntry(long position) throws IOException {
            // PdfSpoolLimits bounds every output offset to the classic xref's ten decimal digits.
            long remaining = position;
            for (int index = 9; index >= 0; index--) {
                xrefEntry[index] = (byte) ('0' + remaining % 10);
                remaining /= 10;
            }
            output.write(xrefEntry);
        }

        private void checkTemporaryBudget() throws IOException {
            checkTemporaryBudget(0);
        }

        private void checkTemporaryBudget(long additionalBytes) throws IOException {
            long remaining = limits.maxTemporaryBytes();
            if (serializedPageBytes > remaining) {
                throw new IOException("PDF spool temporary byte budget exceeded");
            }
            remaining -= serializedPageBytes;
            if (xref.length() > remaining) { throw new IOException("PDF spool temporary byte budget exceeded"); }
            remaining -= xref.length();
            if (leaves.length() > remaining) { throw new IOException("PDF spool temporary byte budget exceeded"); }
            remaining -= leaves.length();
            if (pageIndex.length() > remaining) { throw new IOException("PDF spool temporary byte budget exceeded"); }
            remaining -= pageIndex.length();
            if (additionalBytes > remaining) { throw new IOException("PDF spool temporary byte budget exceeded"); }
        }

        private final class PageObjects {
            private final COSDictionary page;
            private final IdentityHashMap<COSBase, Long> numbers = new IdentityHashMap<>();
            private final IdentityHashMap<COSBase, Boolean> sourceObjects = new IdentityHashMap<>();
            private final IdentityHashMap<COSBase, Boolean> fontProgramSources = new IdentityHashMap<>();
            private final PdfFontFingerprint fingerprints = new PdfFontFingerprint(this::countSource);
            private final List<COSBase> pending = new ArrayList<>();

            private PageObjects(COSDictionary page) throws IOException { this.page = page; countSource(page); }

            private void countSource(COSBase value) throws IOException {
                if (sourceObjects.put(value, Boolean.TRUE) == null && sourceObjects.size() > limits.maxObjectsPerPage()) {
                    throw new IOException("PDF per-page object budget exceeded");
                }
                if (value instanceof COSDictionary dictionary
                        && COSName.FONT_DESC.equals(dictionary.getCOSName(COSName.TYPE))
                        && dictionary.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream program) {
                    fontProgramSources.put(program, Boolean.TRUE);
                }
            }

            private long reference(COSBase value) throws IOException {
                var number = numbers.get(value);
                if (number != null) { return number; }
                countSource(value);
                boolean font = value instanceof COSDictionary dictionary && COSName.FONT.equals(dictionary.getCOSName(COSName.TYPE));
                boolean fontProgram = fontProgramSources.containsKey(value);
                boolean image = value instanceof COSStream stream && COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE));
                var sharedResources = font ? sharedFonts : fontProgram ? sharedFontPrograms : image ? sharedImages : null;
                String key = sharedResources == null ? null : fingerprints.key((COSDictionary) value);
                if (key != null) {
                    var shared = sharedResources.get(key);
                    if (shared != null) { numbers.put(value, shared); return shared; }
                }
                long result = reserve(); numbers.put(value, result); pending.add(value);
                if (key != null) {
                    int capacity = font ? SHARED_FONTS : fontProgram ? SHARED_FONT_PROGRAMS : SHARED_IMAGES;
                    if (sharedResources.size() == capacity) { sharedResources.pollFirstEntry(); }
                    sharedResources.put(key, result);
                }
                return result;
            }

            private void copy() throws IOException {
                for (int index = 0; index < pending.size(); index++) {
                    ExecutionContext.checkpoint();
                    COSBase value = pending.get(index);
                    begin(numbers.get(value));
                    if (value instanceof COSStream stream) {
                        dictionary(stream); write("\nstream\n");
                        try (InputStream raw = stream.createRawInputStream()) {
                            byte[] buffer = new byte[16_384]; long copied = 0; int count;
                            while ((count = raw.read(buffer)) != -1) {
                                ExecutionContext.checkpoint(); output.write(buffer, 0, count); copied += count;
                            }
                            if (copied != stream.getLength()) { throw new IOException("PDF stream byte length changed while copying"); }
                        }
                        write("\nendstream");
                    } else if (value instanceof COSDictionary dictionary) { dictionary(dictionary); }
                    else if (value instanceof COSArray array) {
                        write("["); for (int at = 0; at < array.size(); at++) { value(array.get(at)); write(" "); } write("]");
                    } else { throw new IOException("unsupported indirect PDF spool value"); }
                    write("\nendobj\n");
                }
            }

            private void dictionary(COSDictionary dictionary) throws IOException {
                write("<< ");
                for (var entry : dictionary.entrySet()) {
                    ExecutionContext.checkpoint();
                    if (dictionary instanceof COSStream && entry.getKey().equals(COSName.LENGTH)) { continue; }
                    if (dictionary == page && entry.getKey().equals(COSName.ANNOTS) && internalAnnotations.containsKey(pages)) { continue; }
                    entry.getKey().writePDF(output); write(" ");
                    if (dictionary == page && entry.getKey().equals(COSName.PARENT)) { write(leafObject + " 0 R"); }
                    else { value(entry.getValue()); }
                    write(" ");
                }
                if (dictionary == page && internalAnnotations.containsKey(pages)) {
                    write("/Annots ["); var original = page.getCOSArray(COSName.ANNOTS);
                    if (original != null) { for (int at = 0; at < original.size(); at++) { value(original.get(at)); write(" "); } }
                    for (long annotation : internalAnnotations.get(pages)) { write(annotation + " 0 R "); }
                    write("] ");
                }
                if (dictionary instanceof COSStream stream) { write("/Length " + stream.getLength() + " "); }
                write(">>");
            }

            private void value(COSBase raw) throws IOException {
                ExecutionContext.checkpoint();
                COSBase value = raw;
                int depth = 0;
                while (value instanceof COSObject object) {
                    if (++depth > 64) { throw new IOException("PDF spool object-reference depth exceeded"); }
                    value = object.getObject();
                }
                if (value == null || value instanceof COSNull) { write("null"); }
                else if (value instanceof COSDictionary || value instanceof COSArray) { write(reference(value) + " 0 R"); }
                else if (value instanceof COSName name) { name.writePDF(output); }
                else if (value instanceof COSString text) { bytes(text.getBytes()); }
                else if (value instanceof COSInteger integer) { integer.writePDF(output); }
                else if (value instanceof COSFloat number) { number.writePDF(output); }
                else if (value instanceof COSBoolean flag) { flag.writePDF(output); }
                else { throw new IOException("unsupported PDF spool primitive"); }
            }
        }

        private void string(String value) throws IOException {
            byte[] utf16 = value.getBytes(StandardCharsets.UTF_16BE);
            write("<FEFF"); hex(utf16); write(">");
        }
        private void bytes(byte[] value) throws IOException { write("<"); hex(value); write(">"); }
        private void hex(byte[] value) throws IOException {
            final byte[] digits = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
            for (int index = 0; index < value.length; index++) {
                if ((index & 1023) == 0) { ExecutionContext.checkpoint(); }
                output.write(digits[(value[index] & 255) >>> 4]); output.write(digits[value[index] & 15]);
            }
        }
        private void write(String value) throws IOException { output.write(value.getBytes(StandardCharsets.ISO_8859_1)); }

        private void requireThread() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("PDF spool session used from another thread"); }
        }
        private void requireActive() {
            requireThread();
            if (terminal || closed) { throw new IllegalStateException("PDF spool session is terminal"); }
        }
        @Override public void close() throws IOException {
            requireThread();
            if (closed) { return; }
            if (!finished) { fontPrograms.discard(); }
            closed = true; terminal = true;
            sharedFonts.clear();
            sharedFontPrograms.clear();
            sharedImages.clear();
            imagePrograms.close();
            Throwable failure = null;
            try { fontPrograms.close(); } catch (IOException | RuntimeException | Error cause) { failure = cause; }
            try { xref.close(); } catch (IOException | RuntimeException | Error cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            try { leaves.close(); } catch (IOException | RuntimeException | Error cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            try { pageIndex.close(); } catch (IOException | RuntimeException | Error cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            for (Path file : List.of(xrefFile, leavesFile, pagesFile, directory)) {
                try { Files.deleteIfExists(file); }
                catch (IOException | RuntimeException | Error cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            }
            if (failure instanceof IOException cause) { throw cause; }
            if (failure instanceof RuntimeException cause) { throw cause; }
            if (failure instanceof Error cause) { throw cause; }
        }
        private void cleanupFiles(Throwable failure) {
            for (Path file : List.of(xrefFile, leavesFile, pagesFile, directory)) {
                try { Files.deleteIfExists(file); }
                catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
        }
    }

    /** Two fixed-size cache blocks per scratch index. Logical growth has no file-system side effect;
     * evicted blocks initialize any preceding gap explicitly, so reserved entries always read zero.
     * Every physical write remains within the logical length admitted by the session's byte budget.
     * These files are private scratch storage: close discards dirty blocks because the session has
     * already consumed the index, or has failed, and immediately removes the file.
     */
    private static final class DiskIndex implements AutoCloseable {
        private static final int BLOCK_BYTES = 8 * 1024;
        private static final int CACHED_BLOCKS = 2;
        private static final byte[] ZERO_BYTES = new byte[BLOCK_BYTES];
        private final RandomAccessFile file;
        private final LinkedHashMap<Long, Block> blocks = new LinkedHashMap<>(4, 0.75f, true);
        private long length;
        private long storedLength;
        private long position;

        private DiskIndex(Path path) throws IOException { file = new RandomAccessFile(path.toFile(), "rw"); }

        private long length() { return length; }
        private long getFilePointer() { return position; }
        private void seek(long offset) {
            if (offset < 0 || (offset & (Long.BYTES - 1)) != 0) {
                throw new IllegalArgumentException("PDF index offset must be a nonnegative multiple of eight");
            }
            position = offset;
        }
        private void setLength(long value) {
            if (value < length || (value & (Long.BYTES - 1)) != 0) {
                throw new IllegalArgumentException("PDF index length must grow in complete entries");
            }
            length = value;
        }

        private long readLong() throws IOException {
            if (position > length - Long.BYTES) { throw new EOFException("PDF index entry is outside its logical length"); }
            var block = block(position - position % BLOCK_BYTES);
            long result = block.values.getLong((int) (position - block.offset));
            position += Long.BYTES;
            return result;
        }

        private void writeLong(long value) throws IOException {
            if (position > Long.MAX_VALUE - Long.BYTES) { throw new IOException("PDF index length overflow"); }
            var block = block(position - position % BLOCK_BYTES);
            block.values.putLong((int) (position - block.offset), value);
            block.dirty = true;
            position += Long.BYTES;
            length = Math.max(length, position);
        }

        private Block block(long offset) throws IOException {
            ExecutionContext.checkpoint();
            var result = blocks.get(offset);
            if (result != null) { return result; }
            if (blocks.size() == CACHED_BLOCKS) {
                var oldest = blocks.firstEntry();
                result = oldest.getValue();
                flush(result);
                blocks.remove(oldest.getKey());
                Arrays.fill(result.bytes, (byte) 0);
            } else { result = new Block(); }
            result.offset = offset;
            result.dirty = false;
            int available = (int) Math.min(BLOCK_BYTES, Math.max(0, storedLength - offset));
            if (available != 0) {
                file.seek(offset);
                file.readFully(result.bytes, 0, available);
            }
            blocks.put(offset, result);
            return result;
        }

        private void flush(Block block) throws IOException {
            if (!block.dirty) { return; }
            while (storedLength < block.offset) {
                ExecutionContext.checkpoint();
                int zeros = (int) Math.min(BLOCK_BYTES, block.offset - storedLength);
                file.seek(storedLength);
                file.write(ZERO_BYTES, 0, zeros);
                storedLength += zeros;
            }
            int count = (int) Math.min(BLOCK_BYTES, length - block.offset);
            file.seek(block.offset);
            file.write(block.bytes, 0, count);
            storedLength = Math.max(storedLength, block.offset + count);
            block.dirty = false;
        }

        @Override public void close() throws IOException { blocks.clear(); file.close(); }

        private static final class Block {
            private final byte[] bytes = new byte[BLOCK_BYTES];
            private final ByteBuffer values = ByteBuffer.wrap(bytes);
            private long offset;
            private boolean dirty;
        }
    }

    private static final class CountedOutput extends FilterOutputStream {
        private final long limit;
        private long count;
        private CountedOutput(OutputStream output, long limit) { super(output); this.limit = limit; }
        private void reserve(int size) throws IOException {
            ExecutionContext.checkpoint();
            if (size > limit - count) { throw new IOException("PDF spool output byte budget exceeded"); }
            count += size;
        }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length); reserve(length); out.write(bytes, offset, length);
        }
        @Override public void close() { /* Borrowed. */ }
    }
}
