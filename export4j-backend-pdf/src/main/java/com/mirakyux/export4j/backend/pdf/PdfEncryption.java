// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;

/** AES-256 password protection. Permission flags are enforced by cooperating readers, not a DRM boundary.
 * Passwords are copied, never included in diagnostics/toString. The effective SASLprep/NFKC password
 * must fit 127 UTF-8 bytes; an owner password must remain nonempty and distinct after preparation.
 * This immutable configuration is reusable.
 */
public final class PdfEncryption {
    private final char[] owner;
    private final char[] user;
    private final Permissions permissions;

    /** Task-confined builder. Password arrays are copied at entry and when building; never logged. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private char[] owner;
        private char[] user = new char[0];
        private Permissions permissions = Permissions.READ_ONLY;
        private Builder() { }
        public Builder ownerPassword(char[] value) { owner = Objects.requireNonNull(value, "ownerPassword").clone(); return this; }
        public Builder userPassword(char[] value) { user = Objects.requireNonNull(value, "userPassword").clone(); return this; }
        public Builder permissions(Permissions value) { permissions = Objects.requireNonNull(value, "permissions"); return this; }
        public PdfEncryption build() { return new PdfEncryption(owner, user, permissions); }
    }

    public record Permissions(boolean print, boolean modify, boolean extract, boolean annotations, boolean forms, boolean assemble) {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private boolean print = false;
            private boolean modify = false;
            private boolean extract = false;
            private boolean annotations = false;
            private boolean forms = false;
            private boolean assemble = false;
            private Builder() { }
            public Builder print(boolean value) { print = value; return this; }
            public Builder modify(boolean value) { modify = value; return this; }
            public Builder extract(boolean value) { extract = value; return this; }
            public Builder annotations(boolean value) { annotations = value; return this; }
            public Builder forms(boolean value) { forms = value; return this; }
            public Builder assemble(boolean value) { assemble = value; return this; }

            public Permissions build() { return new Permissions(print, modify, extract, annotations, forms, assemble); }
        }

        public static final Permissions READ_ONLY = new Permissions(false, false, false, false, false, false);
        public static final Permissions ALL = new Permissions(true, true, true, true, true, true);
    }

    /** Copies passwords and rejects malformed Unicode, effective values over 127 UTF-8 bytes,
     * an empty effective owner, or equal effective owner/user values. The user may be empty.
     * Length and equality use SASLprep mapping and NFKC before the provider's password processing.
     *
     * @throws IllegalArgumentException if the password constraints are not met
     */
    public PdfEncryption(char[] owner, char[] user, Permissions permissions) {
        this.owner = Objects.requireNonNull(owner, "owner").clone();
        this.user = Objects.requireNonNull(user, "user").clone();
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        String effectiveOwner = effectivePassword(this.owner), effectiveUser = effectivePassword(this.user);
        if (effectiveOwner.isEmpty() || effectiveOwner.equals(effectiveUser)
                || effectiveOwner.getBytes(StandardCharsets.UTF_8).length > 127
                || effectiveUser.getBytes(StandardCharsets.UTF_8).length > 127) {
            throw new IllegalArgumentException("effective owner password must be nonempty and distinct; prepared passwords must fit 127 UTF-8 bytes");
        }
    }

    private static String effectivePassword(char[] password) {
        var mapped = new StringBuilder(password.length);
        for (int index = 0; index < password.length; index++) {
            char value = password[index];
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= password.length || !Character.isLowSurrogate(password[index + 1])) {
                    throw new IllegalArgumentException("password must contain valid Unicode");
                }
                mapped.append(value).append(password[++index]); continue;
            }
            if (Character.isLowSurrogate(value)) { throw new IllegalArgumentException("password must contain valid Unicode"); }
            // RFC 4013 preparation: C.1.2 spaces, B.1 removals, then Unicode NFKC.
            // Mapping spaces first also matches the encryption provider's treatment of U+200B.
            if (value == '\u00a0' || value == '\u1680' || value >= '\u2000' && value <= '\u200b'
                    || value == '\u202f' || value == '\u205f' || value == '\u3000') { mapped.append(' '); }
            else if (!(value == '\u00ad' || value == '\u034f' || value == '\u1806'
                    || value >= '\u180b' && value <= '\u180d' || value >= '\u200c' && value <= '\u200d'
                    || value == '\u2060' || value >= '\ufe00' && value <= '\ufe0f' || value == '\ufeff')) { mapped.append(value); }
        }
        return Normalizer.normalize(mapped, Normalizer.Form.NFKC);
    }

    String ownerPassword() { return new String(owner); }
    String userPassword() { return new String(user); }
    Permissions permissions() { return permissions; }
    @Override public String toString() { return "PdfEncryption[AES-256, passwords redacted]"; }
}
