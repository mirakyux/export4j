// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

import java.awt.color.ICC_Profile;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Explicit ICC program (at most 4 MiB) and normalized coordinates. Bytes and coordinates are snapshotted.
 * Supports gray, RGB and CMYK ICC profiles. No profile is read from the machine or network. */
public final class IccColor implements ColorValue {
    private final byte[] profile;
    private final List<Double> components;
    private final String id;
    public IccColor(byte[] profile, List<Double> components) {
        Objects.requireNonNull(profile, "profile");
        if (profile.length < 128 || profile.length > 4 * 1024 * 1024) { throw new IllegalArgumentException("ICC byte budget exceeded"); }
        this.profile = profile.clone(); this.components = List.copyOf(components);
        var parsed = ICC_Profile.getInstance(this.profile);
        int channels = parsed.getNumComponents();
        int type = parsed.getProfileClass();
        if (type != ICC_Profile.CLASS_INPUT && type != ICC_Profile.CLASS_DISPLAY && type != ICC_Profile.CLASS_OUTPUT && type != ICC_Profile.CLASS_COLORSPACECONVERSION
                || parsed.getPCSType() != java.awt.color.ColorSpace.TYPE_XYZ && parsed.getPCSType() != java.awt.color.ColorSpace.TYPE_Lab) { throw new IllegalArgumentException("ICC color requires an input, display, output or color-space profile with XYZ/Lab PCS"); }
        if (channels != 1 && channels != 3 && channels != 4 || this.components.size() != channels) { throw new IllegalArgumentException("unsupported ICC component count"); }
        for (double value : this.components) { if (!Double.isFinite(value) || value < 0 || value > 1) { throw new IllegalArgumentException("ICC components must be normalized in [0,1]"); } }
        try { id = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(this.profile)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
    public byte[] profile() { return profile.clone(); }
    public List<Double> components() { return components; }
    public String id() { return id; }
    @Override public boolean equals(Object other) { return other instanceof IccColor value && id.equals(value.id) && components.equals(value.components); }
    @Override public int hashCode() { return Objects.hash(id, components); }
    @Override public String toString() { return "IccColor[profile=" + id + ", components=" + components.size() + "]"; }
}
