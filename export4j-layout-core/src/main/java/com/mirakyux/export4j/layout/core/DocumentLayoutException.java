// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import com.mirakyux.export4j.model.CellLocation;

/**
 * Structured layout failure without source text. All indices are zero-based; -1 means not applicable.
 * Empty block ID denotes a document-level failure. Cell source positions are cell-local UTF-16.
 * Legacy paragraphId/paragraphIndex accessors alias blockId/blockIndex, also for tables.
 * Causes preserve text-layer failures and codes.
 */
public final class DocumentLayoutException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        BUDGET_EXCEEDED, CONTENT_TOO_TALL, TEXT_LAYOUT_FAILED, INVALID_GEOMETRY,
        UNSUPPORTED_COMPONENT, COMPONENT_LAYOUT_FAILED
    }

    private final Code code;
    private final String paragraphId;
    private final int paragraphIndex;
    private final int pageIndex;
    private final int sourceIndex;
    private final int rowIndex;
    private final int columnIndex;
    private final ArrayList<CellLocation> sourcePath;

    public DocumentLayoutException(Code code, String paragraphId, int paragraphIndex,
            int pageIndex, int sourceIndex, String message, Throwable cause) {
        this(code, paragraphId, paragraphIndex, pageIndex, -1, -1, sourceIndex, message, cause);
    }

    public DocumentLayoutException(Code code, String blockId, int blockIndex, int pageIndex,
            int rowIndex, int columnIndex, int sourceIndex, String message, Throwable cause) {
        this(code, blockId, blockIndex, pageIndex, rowIndex, columnIndex, sourceIndex, List.of(), message, cause);
    }

    /** Cell ancestry is ordered outermost to innermost; leaf indices retain their existing meaning. */
    public DocumentLayoutException(Code code, String blockId, int blockIndex, int pageIndex,
            int rowIndex, int columnIndex, int sourceIndex, List<CellLocation> sourcePath, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.paragraphId = Objects.requireNonNull(blockId, "blockId");
        this.paragraphIndex = blockIndex;
        this.pageIndex = pageIndex;
        this.sourceIndex = sourceIndex;
        this.rowIndex = rowIndex;
        this.columnIndex = columnIndex;
        this.sourcePath = new ArrayList<>(List.copyOf(sourcePath));
    }

    public Code code() {
        return code;
    }

    public String paragraphId() {
        return paragraphId;
    }

    public int paragraphIndex() {
        return paragraphIndex;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public int sourceIndex() {
        return sourceIndex;
    }

    public String blockId() {
        return paragraphId;
    }

    public int blockIndex() {
        return paragraphIndex;
    }

    public int rowIndex() {
        return rowIndex;
    }

    public int columnIndex() {
        return columnIndex;
    }

    public List<CellLocation> sourcePath() { return List.copyOf(sourcePath); }

    /** Adds missing ancestry while retaining the underlying cause; never replaces a deeper path. */
    public DocumentLayoutException withSourcePath(List<CellLocation> value) {
        if (!sourcePath.isEmpty() || value.isEmpty()) { return this; }
        return new DocumentLayoutException(code, blockId(), blockIndex(), pageIndex, rowIndex, columnIndex,
                sourceIndex, value, getMessage(), getCause());
    }
}
