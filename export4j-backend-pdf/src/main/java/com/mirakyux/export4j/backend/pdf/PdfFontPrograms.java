// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.model.ExecutionContext;

/** Session-confined parsed horizontal TrueType programs, never PDF fonts or document objects.
 * Both the number of programs and their total encoded source bytes are bounded. Parsed table
 * storage can exceed the encoded size, but does not accumulate with the number of pages.
 * Programs borrowed by the current page stay pinned until that page's PDF document is closed.
 */
final class PdfFontPrograms implements AutoCloseable {
    private final int maxEntries;
    private final long maxSourceBytes;
    private final PdfFontCache cache;
    private final boolean reuseSubsets;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(8, 0.75f, true);
    private final Set<String> pinned = new HashSet<>();
    private final PdfFontSubsets subsets = new PdfFontSubsets();
    private long sourceBytes;
    private boolean closed;

    PdfFontPrograms() { this(PdfFontCache.defaultCache()); }

    PdfFontPrograms(PdfFontCache cache) { this(cache, true); }

    PdfFontPrograms(PdfFontCache cache, boolean reuseSubsets) { this(8, 32L * 1024 * 1024, cache, reuseSubsets); }

    PdfFontPrograms(int maxEntries, long maxSourceBytes) { this(maxEntries, maxSourceBytes, PdfFontCache.defaultCache()); }

    PdfFontPrograms(int maxEntries, long maxSourceBytes, PdfFontCache cache) {
        this(maxEntries, maxSourceBytes, cache, true);
    }

    private PdfFontPrograms(int maxEntries, long maxSourceBytes, PdfFontCache cache, boolean reuseSubsets) {
        if (maxEntries <= 0 || maxSourceBytes <= 0) {
            throw new IllegalArgumentException("font program cache bounds must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxSourceBytes = maxSourceBytes;
        this.cache = java.util.Objects.requireNonNull(cache, "cache");
        this.reuseSubsets = reuseSubsets;
    }

    /** Null means the caller must use a page-owned program; no cache bound is relaxed. */
    TrueTypeFont borrow(FontData data) throws IOException {
        if (closed) { throw new IllegalStateException("PDF font program cache is closed"); }
        ExecutionContext.checkpoint();
        var existing = entries.get(data.id());
        if (existing != null && existing.lease().reusable()) {
            pinned.add(data.id());
            return existing.font();
        }
        if (existing != null) {
            existing.lease().close();
            entries.remove(data.id());
            sourceBytes -= existing.sourceBytes();
        }
        if (data.byteSize() > maxSourceBytes) { return null; }
        while (entries.size() >= maxEntries || data.byteSize() > maxSourceBytes - sourceBytes) {
            var candidates = entries.entrySet().iterator();
            Entry removed = null;
            while (candidates.hasNext()) {
                var candidate = candidates.next();
                if (!pinned.contains(candidate.getKey())) {
                    removed = candidate.getValue();
                    // Keep a failed close owned by the session so final cleanup can still attempt it.
                    removed.lease().close();
                    candidates.remove();
                    sourceBytes -= removed.sourceBytes();
                    break;
                }
            }
            if (removed == null) { return null; }
        }
        var lease = cache.acquire(data);
        var parsed = lease.font();
        entries.put(data.id(), new Entry(parsed, data.byteSize(), lease));
        sourceBytes += data.byteSize();
        pinned.add(data.id());
        return parsed;
    }

    PdfFontSubsets subsets() { return subsets; }
    boolean reusesSubsets() { return reuseSubsets; }

    /** A failed or abandoned export must not return potentially changed programs to other sessions. */
    void discard() { entries.values().forEach(entry -> entry.lease().discard()); }

    /** Called only after the page's PDF document and all of its font borrowers have closed. */
    void releasePage() { pinned.clear(); subsets.releasePage(); }

    static TrueTypeFont parse(FontData data) throws IOException {
        TrueTypeFont parsed;
        try (var input = new RandomAccessReadBuffer(data.readOnlyBuffer())) {
            parsed = new TTFParser().parse(input);
        }
        // Layout has already selected and measured every glyph; PDFBox must not shape it again.
        parsed.setEnableGsub(false);
        return parsed;
    }

    @Override public void close() throws IOException {
        if (closed) { return; }
        closed = true;
        subsets.close();
        Throwable failure = null;
        for (var entry : entries.values()) {
            try { entry.lease().close(); }
            catch (IOException | RuntimeException | Error cause) {
                if (failure == null) { failure = cause; } else { failure.addSuppressed(cause); }
            }
        }
        entries.clear(); pinned.clear(); sourceBytes = 0;
        if (failure instanceof IOException cause) { throw cause; }
        if (failure instanceof RuntimeException cause) { throw cause; }
        if (failure instanceof Error cause) { throw cause; }
    }

    private record Entry(TrueTypeFont font, int sourceBytes, PdfFontCache.Lease lease) { }
}
