// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

/** Immutable inline content accepted by Paragraph.mixed; atomic images and fields occupy one U+FFFC source position. */
public sealed interface InlineContent permits InlineText, InlineImage, InlineField { }
