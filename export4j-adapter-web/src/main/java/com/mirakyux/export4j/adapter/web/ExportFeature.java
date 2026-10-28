// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

/** Stable feature keys for unsupported-content policies. See the web export guide for each fallback. */
public enum ExportFeature {
    PAGE_GEOMETRY, PAGE_REGIONS, PAGE_NUMBERING, PAGE_BREAK, INLINE_FIELD, CUSTOM_BLOCK,
    CAPTION, CROSS_REFERENCE, FONT_ROLE, TEXT_STYLE, PARAGRAPH_STYLE,
    TABLE_GEOMETRY, TABLE_STRUCTURE, IMAGE_GEOMETRY, LIST_MARKER, METADATA, NAVIGATION
}
