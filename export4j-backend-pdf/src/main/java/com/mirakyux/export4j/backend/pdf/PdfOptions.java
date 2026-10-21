// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.util.Objects;
import java.util.Optional;

/** Immutable backend configuration. TEMP_FILE spools PDF stream buffers; the layout/display list remains in memory. */
public final class PdfOptions {
    public enum Storage { MEMORY, TEMP_FILE }
    public static final PdfOptions DEFAULT = new PdfOptions(Storage.MEMORY, null, null);
    /** Task-confined builder; build validates archival/encryption compatibility. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private Storage storage = Storage.MEMORY;
        private PdfEncryption encryption;
        private PdfArchival archival;
        private Builder() { }
        public Builder storage(Storage value) { storage = Objects.requireNonNull(value, "storage"); return this; }
        public Builder encryption(PdfEncryption value) { encryption = Objects.requireNonNull(value, "encryption"); return this; }
        public Builder archival(PdfArchival value) { archival = Objects.requireNonNull(value, "archival"); return this; }
        public PdfOptions build() { return new PdfOptions(storage, encryption, archival); }
    }
    private final Storage storage;
    private final PdfEncryption encryption;
    private final PdfArchival archival;

    private PdfOptions(Storage storage, PdfEncryption encryption, PdfArchival archival) {
        this.storage = Objects.requireNonNull(storage, "storage"); this.encryption = encryption; this.archival = archival;
        if (encryption != null && archival != null) { throw new IllegalArgumentException("PDF/A forbids encryption"); }
    }
    public static PdfOptions storage(Storage storage) { return new PdfOptions(storage, null, null); }
    public PdfOptions withEncryption(PdfEncryption value) { return new PdfOptions(storage, Objects.requireNonNull(value, "encryption"), archival); }
    public PdfOptions withArchival(PdfArchival value) { return new PdfOptions(storage, encryption, Objects.requireNonNull(value, "archival")); }
    public Storage storage() { return storage; }
    public Optional<PdfEncryption> encryption() { return Optional.ofNullable(encryption); }
    public Optional<PdfArchival> archival() { return Optional.ofNullable(archival); }
}
