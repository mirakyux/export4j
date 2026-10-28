// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.Loader;
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
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;

/** Ordinary PDF writer with bounded per-page PDFBox state and disk-backed xref/page indices.
 * Each accepted page is rendered with the ordinary PdfBackend, copied as an independent object
 * graph, and released before return. Fonts are embedded per page, trading size for bounded state.
 * Does not apply encryption, archival certification, signatures or accessibility certification.
 * Output is borrowed; the supplied temporary parent is borrowed and must already exist.
 * Instances are immutable and reusable; sessions are confined to their opening thread.
 */
public final class PdfSpoolBackend implements PageBackend {
    private final Path temporaryDirectory;
    private final PdfLimits pageLimits;
    private final PdfSpoolLimits limits;

    public PdfSpoolBackend(Path temporaryDirectory) {
        this(temporaryDirectory, PdfLimits.DEFAULT, PdfSpoolLimits.DEFAULT);
    }

    public PdfSpoolBackend(Path temporaryDirectory, PdfLimits pageLimits, PdfSpoolLimits limits) {
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").toAbsolutePath();
        this.pageLimits = Objects.requireNonNull(pageLimits, "pageLimits");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override public PageWriter open(DocumentMetadata metadata, OutputStream output) throws IOException {
        return new Session(Objects.requireNonNull(metadata, "metadata"), Objects.requireNonNull(output, "output"));
    }

    private final class Session implements PageWriter {
        private static final int LEAF_PAGES = 256;
        private final Thread owner = Thread.currentThread();
        private final DocumentMetadata metadata;
        private final Path directory;
        private final Path pageFile;
        private final Path xrefFile;
        private final Path leavesFile;
        private final Path pagesFile;
        private final RandomAccessFile xref;
        private final RandomAccessFile leaves;
        private final RandomAccessFile pageIndex;
        private final CountedOutput output;
        private final PdfBackend renderer = new PdfBackend(pageLimits);
        private final List<Long> leafPages = new ArrayList<>(LEAF_PAGES);
        private long nextObject = 4;
        private long leafObject;
        private int pages;
        private long commands;
        private boolean terminal;
        private boolean closed;
        private PageNavigation navigation;
        private final java.util.Map<Integer, List<Long>> internalAnnotations = new java.util.HashMap<>();
        private final List<Long> linkObjects = new ArrayList<>();
        private final List<Long> bookmarkObjects = new ArrayList<>();
        private long outlineObject;

        private Session(DocumentMetadata metadata, OutputStream target) throws IOException {
            this.metadata = metadata;
            directory = Files.createTempDirectory(temporaryDirectory, ".export4j-pdf-spool-");
            pageFile = directory.resolve("page.pdf");
            xrefFile = directory.resolve("xref.bin");
            leavesFile = directory.resolve("leaves.bin");
            pagesFile = directory.resolve("pages.bin");
            RandomAccessFile openedXref = null;
            RandomAccessFile openedLeaves = null;
            RandomAccessFile openedPages = null;
            try {
                openedXref = new RandomAccessFile(xrefFile.toFile(), "rw");
                openedLeaves = new RandomAccessFile(leavesFile.toFile(), "rw");
                openedPages = new RandomAccessFile(pagesFile.toFile(), "rw");
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
                    pageIndex.writeLong(reserve()); pageIndex.writeDouble(0);
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
                if (pages == limits.maxPages() || navigation != null && pages == navigation.pages()
                        || page.commands().size() > limits.maxCommands() - commands) {
                    throw new IOException("PDF spool page or command budget exceeded");
                }
                var localLinks = new ArrayList<DocumentExtras.ExternalLink>(links.size());
                for (var link : links) {
                    if (link.pageIndex() != pages) { throw new IOException("PDF spool link page index is not current"); }
                    localLinks.add(new DocumentExtras.ExternalLink(0, link.bounds(), link.destination()));
                }
                var extras = new DocumentExtras(List.of(), List.of(), List.of(), List.of(), localLinks);
                try (var file = Files.newOutputStream(pageFile)) {
                    renderer.write(new DisplayDocument(DocumentMetadata.EMPTY, List.of(page), extras),
                            new CountedOutput(file, limits.maxPageBytes()));
                }
                checkTemporaryBudget();
                if (leafPages.isEmpty()) { leafObject = reserve(); }
                try (var pdf = Loader.loadPDF(pageFile.toFile())) {
                    if (pdf.getNumberOfPages() != 1) { throw new IOException("PDF page renderer produced an invalid page count"); }
                    var copier = new PageObjects(pdf.getPage(0).getCOSObject());
                    if (navigation != null) {
                        pageIndex.seek((long) pages * 16); long assigned = pageIndex.readLong();
                        copier.numbers.put(copier.page, assigned); copier.pending.add(copier.page);
                        pageIndex.writeDouble(page.size().height());
                    }
                    long pageObject = copier.reference(copier.page);
                    copier.copy();
                    leafPages.add(pageObject);
                }
                Files.delete(pageFile);
                commands += page.commands().size(); pages++;
                if (leafPages.size() == LEAF_PAGES) { finishLeaf(); }
                checkTemporaryBudget();
                ExecutionContext.checkpoint();
            } catch (IOException | RuntimeException | Error failure) { terminal = true; throw failure; }
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
                    ExecutionContext.checkpoint(); write(leaves.readLong() + " 0 R ");
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
                    long position = xref.readLong();
                    if (position == 0) { throw new IOException("PDF spool has an unwritten reserved object"); }
                    write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", position));
                }
                write("trailer\n<< /Size " + nextObject + " /Root 1 0 R /Info 3 0 R >>\nstartxref\n"
                        + xrefStart + "\n%%EOF\n");
                output.flush(); terminal = true;
            } catch (IOException | RuntimeException | Error failure) { terminal = true; throw failure; }
        }

        private long pageReference(int index) throws IOException { pageIndex.seek((long) index * 16); return pageIndex.readLong(); }
        private double pageHeight(int index) throws IOException { pageIndex.seek((long) index * 16 + 8); return pageIndex.readDouble(); }
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
            leaves.seek(leaves.length()); leaves.writeLong(leafObject); leafPages.clear();
        }

        private long reserve() throws IOException {
            if (nextObject > limits.maxObjects()) { throw new IOException("PDF spool object budget exceeded"); }
            long object = nextObject++;
            if (nextObject * 8 > limits.maxTemporaryBytes()) { throw new IOException("PDF spool xref budget exceeded"); }
            xref.setLength(nextObject * 8);
            checkTemporaryBudget();
            return object;
        }

        private void begin(long object) throws IOException {
            ExecutionContext.checkpoint();
            xref.seek(object * 8); xref.writeLong(output.count);
            write(object + " 0 obj\n");
        }

        private void checkTemporaryBudget() throws IOException {
            long pageBytes = Files.exists(pageFile) ? Files.size(pageFile) : 0;
            if (pageBytes > limits.maxTemporaryBytes() - xref.length()
                    || leaves.length() > limits.maxTemporaryBytes() - xref.length() - pageBytes
                    || pageIndex.length() > limits.maxTemporaryBytes() - xref.length() - pageBytes - leaves.length()) {
                throw new IOException("PDF spool temporary byte budget exceeded");
            }
        }

        private final class PageObjects {
            private final COSDictionary page;
            private final IdentityHashMap<COSBase, Long> numbers = new IdentityHashMap<>();
            private final List<COSBase> pending = new ArrayList<>();

            private PageObjects(COSDictionary page) { this.page = page; }

            private long reference(COSBase value) throws IOException {
                var number = numbers.get(value);
                if (number != null) { return number; }
                if (pending.size() == limits.maxObjectsPerPage()) { throw new IOException("PDF per-page object budget exceeded"); }
                long result = reserve(); numbers.put(value, result); pending.add(value); return result;
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
            closed = true; terminal = true;
            IOException failure = null;
            try { xref.close(); } catch (IOException cause) { failure = cause; }
            try { leaves.close(); } catch (IOException cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            try { pageIndex.close(); } catch (IOException cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            for (Path file : List.of(pageFile, xrefFile, leavesFile, pagesFile, directory)) {
                try { Files.deleteIfExists(file); }
                catch (IOException cause) { if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); } }
            }
            if (failure != null) { throw failure; }
        }
        private void cleanupFiles(Throwable failure) {
            for (Path file : List.of(pageFile, xrefFile, leavesFile, pagesFile, directory)) {
                try { Files.deleteIfExists(file); }
                catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
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
