// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import com.mirakyux.export4j.model.DocumentMetadata;

/** Immutable, nonempty Display List document; contains no backend handles or open resources. */
public record DisplayDocument(DocumentMetadata metadata, List<DisplayPage> pages, DocumentExtras extras) {
    public DisplayDocument {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(extras, "extras");
        pages = List.copyOf(pages);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("document must contain at least one page");
        }
        int level = 0;
        for (var bookmark : extras.bookmarks()) {
            if (bookmark.level() > level + 1) {
                throw new IllegalArgumentException("bookmark hierarchy cannot skip levels");
            }
            level = bookmark.level();
            requirePoint(pages, bookmark.pageIndex(), bookmark.destination());
        }
        for (var link : extras.links()) {
            requireRect(pages, link.pageIndex(), link.bounds());
            requirePoint(pages, link.targetPageIndex(), link.destination());
        }
        for (var comment : extras.comments()) {
            requireRect(pages, comment.pageIndex(), comment.bounds());
        }
        for (var link : extras.externalLinks()) { requireRect(pages, link.pageIndex(), link.bounds()); }
        var names = new HashSet<String>();
        for (var field : extras.fields()) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("form field names must be unique");
            }
            requireRect(pages, field.pageIndex(), field.bounds());
        }
    }

    public DisplayDocument(DocumentMetadata metadata, List<DisplayPage> pages) {
        this(metadata, pages, DocumentExtras.EMPTY);
    }

    private static void requireRect(List<DisplayPage> pages, int index, Rect bounds) {
        requirePoint(pages, index, new Point(bounds.x(), bounds.y()));
        requirePoint(pages, index, new Point(bounds.x() + bounds.width(), bounds.y() + bounds.height()));
    }

    private static void requirePoint(List<DisplayPage> pages, int index, Point point) {
        if (index < 0 || index >= pages.size()) {
            throw new IllegalArgumentException("interaction page does not exist");
        }
        var size = pages.get(index).size();
        if (point.x() < 0 || point.y() < 0 || point.x() > Math.nextUp(size.width()) || point.y() > Math.nextUp(size.height())) {
            throw new IllegalArgumentException("interaction must lie within its page");
        }
    }
}
