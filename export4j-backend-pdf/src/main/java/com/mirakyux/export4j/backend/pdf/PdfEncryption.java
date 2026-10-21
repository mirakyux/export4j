// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.util.Objects;

/** AES-256 password protection. Permission flags are enforced by cooperating readers, not a DRM boundary.
 * Passwords are copied, never included in diagnostics/toString. This immutable configuration is reusable.
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

    public PdfEncryption(char[] owner, char[] user, Permissions permissions) {
        this.owner = Objects.requireNonNull(owner, "owner").clone();
        this.user = Objects.requireNonNull(user, "user").clone();
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        if (owner.length == 0 || owner.length > 127 || user.length > 127 || java.util.Arrays.equals(owner, user)) {
            throw new IllegalArgumentException("owner password must be nonempty, distinct, and passwords at most 127 UTF-16 units");
        }
    }

    String ownerPassword() { return new String(owner); }
    String userPassword() { return new String(user); }
    Permissions permissions() { return permissions; }
    @Override public String toString() { return "PdfEncryption[AES-256, passwords redacted]"; }
}
