// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math.docx;

import com.mirakyux.export4j.math.api.MathException;
import com.mirakyux.export4j.math.api.MathOptions;

import com.mirakyux.export4j.adapter.docx.DocxMath;

/** Bounded, offline TeX conversion to the native Word mathematics tree. */
public final class DocxTexMath {
    private DocxTexMath() { }

    /** Converts the supported semantic subset to editable Word mathematics using default limits.
     * @throws MathException for unsupported syntax, invalid input or limits
     */
    public static DocxMath parse(String tex) { return parse(tex, MathOptions.DEFAULT); }

    /** Converts to an immutable OMML semantic tree under the supplied source and syntax limits.
     * Word determines formula geometry when it lays out the document.
     * @throws MathException for unsupported syntax, invalid input or limits
     */
    public static DocxMath parse(String tex, MathOptions options) { return TexToDocx.parse(tex, options); }
}
