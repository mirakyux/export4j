// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.CellLocation;
import java.util.List;
import java.util.Objects;

/** Original source location, not browser/page coordinates. Indexes and UTF-16 offsets are zero based;
 * -1 means not applicable. region is BODY, HEADER, FOOTER or DOCUMENT. Cell paths retain real anchors.
 */
public record ExportLocation(int sectionIndex, String region, int rootBlockIndex, String blockId,
        List<CellLocation> cellPath, int sourceOffset) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public ExportLocation {
        Objects.requireNonNull(region, "region"); Objects.requireNonNull(blockId, "blockId");
        cellPath = List.copyOf(cellPath);
        if (sectionIndex < -1 || rootBlockIndex < -1 || sourceOffset < -1) { throw new IllegalArgumentException("invalid export location"); }
    }
    ExportLocation offset(int value) { return new ExportLocation(sectionIndex, region, rootBlockIndex, blockId, cellPath, value); }
    ExportLocation cell(CellLocation cell, String id) {
        var path = new java.util.ArrayList<>(cellPath); path.add(cell);
        return new ExportLocation(sectionIndex, region, rootBlockIndex, id, path, -1);
    }
}
