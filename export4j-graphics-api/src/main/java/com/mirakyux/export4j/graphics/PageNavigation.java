// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.List;

/** Explicit final-page navigation for replay output. Supplied once before accepting pages.
 * Bounded declarations contain no model or backend handles. Page indices are physical and zero-based;
 * destinations use top-left points. The writer verifies the declared page count on completion.
 */
public record PageNavigation(int pages, List<DocumentExtras.Bookmark> bookmarks, List<DocumentExtras.Link> links) {
    public PageNavigation {
        bookmarks = List.copyOf(bookmarks); links = List.copyOf(links);
        if (pages <= 0 || pages > 100_000 || bookmarks.size() > 10_000 || links.size() > 100_000) {
            throw new IllegalArgumentException("page navigation budget exceeded");
        }
        int previous = 0;
        for (var bookmark : bookmarks) {
            if (bookmark.pageIndex() >= pages || bookmark.level() > previous + 1) {
                throw new IllegalArgumentException("invalid page navigation bookmark");
            }
            previous = bookmark.level();
        }
        for (var link : links) {
            if (link.pageIndex() >= pages || link.targetPageIndex() >= pages) { throw new IllegalArgumentException("invalid page navigation link"); }
        }
    }
}
