// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Semantic horizontal paragraph alignment within the indented content area. */
public enum ParagraphAlignment {
    LEFT, CENTER, RIGHT,
    /** Expands soft-wrapped lines at spaces, or eligible CJK boundaries. Final and explicit-break lines stay left aligned. */
    JUSTIFY
}
