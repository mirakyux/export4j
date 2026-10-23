// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.processing;

import com.mirakyux.export4j.model.CellLocation;
import java.util.List;
import java.util.Objects;

/** Structured, content-free loss/skip diagnostic. UTF-16 ranges are half-open in the stage's input;
 * -1/-1 means inapplicable. Cell paths contain physical anchors, never covered slots.
 */
public record ProcessingIssue(String stage, String sourceFormat, String targetFormat,
        String feature, String operation, Location location, String reason,
        ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment, List<String> effects) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public record Location(String sectionId, String region, String blockId, List<CellLocation> cellPath,
            int startUtf16, int endUtf16) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public Location {
            Objects.requireNonNull(sectionId, "sectionId"); Objects.requireNonNull(region, "region");
            Objects.requireNonNull(blockId, "blockId"); cellPath = List.copyOf(cellPath);
            if (startUtf16 < -1 || endUtf16 < startUtf16 || (startUtf16 == -1) != (endUtf16 == -1)) {
                throw new IllegalArgumentException("invalid diagnostic range");
            }
        }
        public Location range(int start, int end) { return new Location(sectionId, region, blockId, cellPath, start, end); }
    }
    public ProcessingIssue {
        Objects.requireNonNull(stage, "stage"); Objects.requireNonNull(sourceFormat, "sourceFormat");
        Objects.requireNonNull(targetFormat, "targetFormat"); Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(operation, "operation"); Objects.requireNonNull(location, "location");
        Objects.requireNonNull(reason, "reason"); Objects.requireNonNull(action, "action");
        Objects.requireNonNull(treatment, "treatment"); effects = List.copyOf(effects);
    }
    public boolean warning() { return action == ProcessingPolicy.Action.WARN; }
    public boolean lossy() {
        return treatment == ProcessingPolicy.Treatment.DEGRADE || treatment == ProcessingPolicy.Treatment.DISCARD;
    }
}
