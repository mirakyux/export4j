// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.net.URI;
import java.util.Objects;

/** Passive external hyperlink; only HTTP, HTTPS and mailto are allowed. Never fetched during rendering. */
public record ExternalDestination(String uri) {
    public ExternalDestination {
        Objects.requireNonNull(uri, "uri");
        if (uri.length() > 8192 || uri.codePoints().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException("invalid hyperlink URI"); }
        URI parsed = URI.create(uri);
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("mailto"))
                || parsed.getSchemeSpecificPart().isBlank()
                || !scheme.equalsIgnoreCase("mailto") && (parsed.getHost() == null || parsed.getUserInfo() != null)) {
            throw new IllegalArgumentException("unsupported external destination");
        }
    }
}
