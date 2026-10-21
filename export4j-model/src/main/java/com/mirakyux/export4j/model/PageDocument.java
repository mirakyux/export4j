// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.Objects;

/** Immutable semantic document with fixed repeating page regions for component-engine layout.
 * The body and band IDs share one namespace. Owns no resources and is safe to share.
 * Extracting document() explicitly selects the body alone; adapters accepting Document do not export bands.
 */
public record PageDocument(Document document, PageRegions pageRegions) {
    public PageDocument {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pageRegions, "pageRegions");
        var blocks = new ArrayList<Block>(document.blocks());
        blocks.addAll(pageRegions.header().blocks());
        blocks.addAll(pageRegions.footer().blocks());
        SemanticTree.validateUniqueIds(blocks);
    }
}
