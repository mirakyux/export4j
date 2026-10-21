// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.Objects;
import java.util.EnumSet;
import java.util.Set;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Immutable declaration of component text inheritance. Local property overrides apply to the
 * inherited paragraph style independently of color. No fonts or resources are owned; thread-safe.
 */
public record TextInheritance(boolean style, boolean color, Set<Property> overrides) {
    /** The six point-based or enum properties represented by ParagraphStyle. */
    public enum Property { FONT_SIZE, LINE_HEIGHT, FIRST_LINE_INDENT, SUBSEQUENT_LINE_INDENT, ALIGNMENT, LINE_BREAK_POLICY }

    public static final TextInheritance NONE = new TextInheritance(false, false);
    public static final TextInheritance ALL = new TextInheritance(true, true);

    /** Compatibility declaration: each boolean controls the complete style or color. */
    public TextInheritance(boolean style, boolean color) { this(style, color, Set.of()); }

    /** Overrides are local only when style uses the parent as its base. Sets are snapshotted.
     * A complete explicit style (style=false) cannot also declare partial overrides.
     */
    public TextInheritance {
        overrides = Set.copyOf(overrides);
        if (!style && !overrides.isEmpty()) { throw new IllegalArgumentException("complete style cannot have partial overrides"); }
    }

    /** Marks one local property explicit; a previously complete explicit style remains complete. */
    public TextInheritance withExplicit(Property property) {
        Objects.requireNonNull(property, "property");
        if (!style) { return this; }
        var values = EnumSet.noneOf(Property.class);
        values.addAll(overrides); values.add(property);
        return values.size() == Property.values().length ? new TextInheritance(false, color)
                : new TextInheritance(true, color, values);
    }

    /** Restores one property to its parent; all other local declarations remain explicit. */
    public TextInheritance withInherited(Property property) {
        Objects.requireNonNull(property, "property");
        var values = style ? EnumSet.noneOf(Property.class) : EnumSet.allOf(Property.class);
        if (style) { values.addAll(overrides); }
        values.remove(property);
        return new TextInheritance(true, color, values);
    }

    /** Replaces the complete style declaration and clears partial overrides. */
    public TextInheritance withStyleInheritance(boolean inherited) { return new TextInheritance(inherited, color); }

    /** Changes only color inheritance and preserves every local style declaration. */
    public TextInheritance withColorInheritance(boolean inherited) { return new TextInheritance(style, inherited, overrides); }

    public ParagraphStyle resolveStyle(ParagraphStyle local, ComponentContext context) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(context, "context");
        if (!style) { return local; }
        var parent = context.defaultTextStyle();
        if (overrides.isEmpty()) { return parent; }
        return new ParagraphStyle(
                overrides.contains(Property.FONT_SIZE) ? local.fontSize() : parent.fontSize(),
                overrides.contains(Property.LINE_HEIGHT) ? local.lineHeight() : parent.lineHeight(),
                overrides.contains(Property.FIRST_LINE_INDENT) ? local.firstLineIndent() : parent.firstLineIndent(),
                overrides.contains(Property.SUBSEQUENT_LINE_INDENT) ? local.subsequentLineIndent() : parent.subsequentLineIndent(),
                overrides.contains(Property.ALIGNMENT) ? local.alignment() : parent.alignment(),
                overrides.contains(Property.LINE_BREAK_POLICY) ? local.lineBreakPolicy() : parent.lineBreakPolicy());
    }

    public RgbColor resolveColor(RgbColor local, ComponentContext context) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(context, "context");
        return color ? context.defaultTextColor() : local;
    }
}
