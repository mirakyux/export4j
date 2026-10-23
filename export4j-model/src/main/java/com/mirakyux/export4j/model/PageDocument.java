// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.Objects;

/** Immutable semantic document with fixed repeating page regions for component-engine layout.
 * The body and band IDs share one namespace. Owns no resources and is safe to share.
 * Extracting document() explicitly selects the body alone; adapters accepting Document do not export regions.
 */
public record PageDocument(Document document, PageRegions pageRegions) {
    public PageDocument {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pageRegions, "pageRegions");
        pageRegions.requireContained(document.pageSettings().size());
        var blocks = new ArrayList<Block>(document.blocks());
        pageRegions.headers().forEach(band -> blocks.addAll(band.blocks()));
        pageRegions.footers().forEach(band -> blocks.addAll(band.blocks()));
        pageRegions.backgrounds().forEach(band -> blocks.addAll(band.blocks()));
        pageRegions.foregrounds().forEach(band -> blocks.addAll(band.blocks()));
        SemanticTree.validateUniqueIds(blocks);
    }
}
