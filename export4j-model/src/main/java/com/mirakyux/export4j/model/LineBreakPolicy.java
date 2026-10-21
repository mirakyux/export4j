// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Immutable line-boundary policy; explicit line breaks always take precedence. */
public enum LineBreakPolicy {
    /** Existing JDK line boundaries with scalar/grapheme-safe emergency wrapping. */
    STANDARD,
    /** Keeps opening/closing punctuation, paired ellipses/dashes and nonbreaking characters together.
     * A group that cannot fit fails; this is a bounded subset of Chinese typography, not full UAX 14.
     */
    CJK_STRICT
}
