// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;

/** Text and its resolved inline style; concatenation defines paragraph UTF-16 source positions. */
public record InlineText(String text, InlineStyle style, String hyperlink) implements InlineContent {
    public InlineText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(hyperlink, "hyperlink");
        if (!hyperlink.isEmpty()) { new ExternalDestination(hyperlink); }
    }

    public InlineText(String text, InlineStyle style) { this(text, style, ""); }
}
