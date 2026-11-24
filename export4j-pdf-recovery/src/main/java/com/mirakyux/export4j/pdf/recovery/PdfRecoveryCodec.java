// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.pdf.recovery;

import com.mirakyux.export4j.adapter.pdf.PdfRecoveryAttachment;
import com.mirakyux.export4j.adapter.template.EmbeddedRecoveryCodec;
import com.mirakyux.export4j.adapter.template.XmlModelRecovery;
import java.io.IOException;

/** Standard PDF attachment recovery provided by {@code export4j-pdf-recovery}. */
public final class PdfRecoveryCodec implements EmbeddedRecoveryCodec {
    @Override public String format() { return "PDF"; }

    @Override public byte[] encode(byte[] standard, byte[] companion, XmlModelRecovery.Limits limits) throws IOException {
        return PdfRecoveryAttachment.encode(standard, companion, limits.maxDocumentBytes(), limits.bundle().maxEncodedBytes());
    }

    @Override public Extracted decode(byte[] current, XmlModelRecovery.Limits limits) throws IOException {
        return PdfRecoveryAttachment.decode(current, limits.maxDocumentBytes(), limits.bundle().maxEncodedBytes(), Extracted::new);
    }
}
