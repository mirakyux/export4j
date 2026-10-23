// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Complete immutable font-role name and 24-bit RGB, independent of text geometry and resources.
 * Empty role selects the caller's explicit default fonts. Other roles are static identifiers matching
 * {@code [A-Za-z_][A-Za-z_0-9]{0,255}}. Null/invalid roles and RGB outside 0..0xFFFFFF are rejected.
 * Themes resolve these values while authoring; no font lookup, resource ownership or I/O occurs.
 */
public record TextAppearance(String fontRole, int rgb) {
    public static final TextAppearance DEFAULT = new TextAppearance("", 0);

    public TextAppearance {
        Objects.requireNonNull(fontRole, "fontRole");
        if ((!fontRole.isEmpty() && !fontRole.matches("[A-Za-z_][A-Za-z_0-9]{0,255}"))
                || rgb < 0 || rgb > 0xffffff) {
            throw new IllegalArgumentException("invalid text appearance");
        }
    }

    /** Resolves complete, undecorated inline styling at the supplied positive point size. */
    public InlineStyle style(double fontSize) {
        return new InlineStyle(fontRole, fontSize, rgb, 0, false, false);
    }
}
