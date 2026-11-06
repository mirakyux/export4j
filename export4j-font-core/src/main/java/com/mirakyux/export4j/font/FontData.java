// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable, validated static TrueType or CFF program identified by SHA-256. Arrays are defensively copied. */
public final class FontData {
    private final byte[] bytes;
    private final String id;
    private final boolean subsetAllowed;

    FontData(byte[] bytes, boolean subsetAllowed) {
        this.bytes = bytes.clone();
        this.subsetAllowed = subsetAllowed;
        try {
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    public String id() {
        return id;
    }

    public int byteSize() {
        return bytes.length;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    /** Returns a new read-only view of the complete program without copying its bytes.
     * Position starts at zero and limit equals byteSize(); cursor changes are independent
     * of other views, and the private backing array cannot be accessed or modified.
     */
    public ByteBuffer readOnlyBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /** False when the font's embedding flags require embedding the complete program. */
    public boolean subsetAllowed() {
        return subsetAllowed;
    }

    /** True for static OpenType CFF; PDF can embed its native program, while outlined runs retain measured geometry. */
    public boolean cffOutlines() {
        return bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'T' && bytes[2] == 'T' && bytes[3] == 'O';
    }
}
