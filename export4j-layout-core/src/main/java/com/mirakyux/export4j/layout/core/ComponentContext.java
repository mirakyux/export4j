// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Finite positive viewport in points and explicit fonts. pageCount=0 denotes measurement before final pagination. */
public record ComponentContext(double width, double height, FontGroup fontGroup, int pageIndex, int pageCount, int displayPageNumber,
        Map<String, FontGroup> fontRoles, ParagraphStyle defaultTextStyle, RgbColor defaultTextColor) {
    public ComponentContext {
        java.util.Objects.requireNonNull(fontGroup, "fontGroup");
        java.util.Objects.requireNonNull(defaultTextStyle, "defaultTextStyle");
        java.util.Objects.requireNonNull(defaultTextColor, "defaultTextColor");
        fontRoles = Map.copyOf(fontRoles);
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0
                || pageIndex < 0 || pageCount < 0 || displayPageNumber <= 0 || pageCount > 0 && pageIndex >= pageCount) {
            throw new IllegalArgumentException("invalid component context");
        }
    }

    /** Compatibility context: unset text properties use 12 pt / 18 pt lines and black. */
    public ComponentContext(double width, double height, FontGroup fontGroup, int pageIndex, int pageCount,
            int displayPageNumber, Map<String, FontGroup> fontRoles) {
        this(width, height, fontGroup, pageIndex, pageCount, displayPageNumber, fontRoles,
                ParagraphStyle.of(12), RgbColor.BLACK);
    }

    /** Legacy context with no named font roles. */
    public ComponentContext(double width, double height, FontGroup fontGroup, int pageIndex, int pageCount, int displayPageNumber) {
        this(width, height, fontGroup, pageIndex, pageCount, displayPageNumber, Map.of());
    }

    public ComponentContext(double width, double height, List<FontFace> fonts, int pageIndex, int pageCount) {
        this(width, height, FontGroup.strict(fonts), pageIndex, pageCount, Math.addExact(pageIndex, 1));
    }

    public ComponentContext(double width, double height, List<FontFace> fonts, int pageIndex, int pageCount, int displayPageNumber) {
        this(width, height, FontGroup.strict(fonts), pageIndex, pageCount, displayPageNumber);
    }

    public List<FontFace> fonts() {
        return fontGroup.fonts();
    }

    public ComponentContext viewport(double newWidth, double newHeight) {
        return new ComponentContext(newWidth, newHeight, fontGroup, pageIndex, pageCount, displayPageNumber, fontRoles,
                defaultTextStyle, defaultTextColor);
    }

    /** Explicit regional font policy; preserves viewport and final-page context. */
    public ComponentContext withFonts(FontGroup group) {
        return new ComponentContext(width, height, group, pageIndex, pageCount, displayPageNumber, fontRoles,
                defaultTextStyle, defaultTextColor);
    }

    /** Replaces regional text defaults, preserving fonts, roles and final-page context.
     * Only components that declare a property unset inherit it. All dimensions are points.
     */
    public ComponentContext withTextDefaults(ParagraphStyle style, RgbColor color) {
        return new ComponentContext(width, height, fontGroup, pageIndex, pageCount, displayPageNumber, fontRoles, style, color);
    }
}
