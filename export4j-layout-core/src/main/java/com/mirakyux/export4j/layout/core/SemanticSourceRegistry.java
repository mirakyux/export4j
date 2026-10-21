// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.SemanticTree;

/**
 * Validates ordered continuation streams without treating repeated table headers as new sources.
 * One registry belongs to one synchronous layout call; mutable and not thread-safe. Owns no resources.
 * Source identity requires the same immutable Table instance, host ID and measured font configuration.
 */
public final class SemanticSourceRegistry {
    private static final class Stream {
        private final String hostId;
        private final SemanticPlacement first;
        private int next;

        private Stream(String hostId, SemanticPlacement first) {
            this.hostId = hostId;
            this.first = first;
        }
    }

    private final Set<String> reservedIds;
    private final Set<String> claimedIds = new HashSet<>();
    private final Map<String, Stream> streams = new HashMap<>();
    private final List<Table> sources = new ArrayList<>();

    /** Reserves all existing document node IDs, including descendants. Entries are non-null and copied. */
    public SemanticSourceRegistry(List<String> reservedIds) {
        this.reservedIds = new HashSet<>(List.copyOf(reservedIds));
    }

    /**
     * Registers one actual presentation in document order.
     * @return true only for the first fragment of a new source, for source-budget accounting
     * @throws IllegalArgumentException for ID collisions, repeated, missing or unordered fragments,
     *         or changes to the host, source snapshot, fragment count or measured font configuration
     */
    public boolean accept(String hostId, SemanticPlacement placement) {
        Objects.requireNonNull(hostId, "hostId");
        Objects.requireNonNull(placement, "placement");
        String id = placement.source().id();
        if (reservedIds.contains(id)) {
            throw new IllegalArgumentException("nested semantic source ID conflicts with a document node");
        }
        var stream = streams.get(id);
        boolean first = stream == null;
        if (first) {
            if (placement.fragmentIndex() != 0) {
                throw new IllegalArgumentException("semantic source must start at its first fragment");
            }
            if (streams.size() >= 100_000) {
                throw new IllegalArgumentException("semantic source stream budget exceeded");
            }
            var descendants = new HashSet<String>();
            SemanticTree.walk(placement.source(), visit -> {
                String descendantId = visit.block().id();
                if (!descendants.add(descendantId) || reservedIds.contains(descendantId) || claimedIds.contains(descendantId)) {
                    throw new IllegalArgumentException("semantic source descendant ID conflicts with another document source");
                }
                if ((long) descendants.size() + claimedIds.size() > 100_000) {
                    throw new IllegalArgumentException("semantic source descendant budget exceeded");
                }
            });
            claimedIds.addAll(descendants);
            stream = new Stream(hostId, placement);
            streams.put(id, stream);
            sources.add(placement.source());
        }
        var original = stream.first;
        if (!hostId.equals(stream.hostId) || placement.source() != original.source()
                || placement.fragmentCount() != original.fragmentCount()
                || !placement.fonts().equals(original.fonts()) || !placement.roles().equals(original.roles())
                || placement.fragmentIndex() != stream.next) {
            throw new IllegalArgumentException("semantic source requires one complete ordered continuation stream");
        }
        stream.next++;
        return first;
    }

    /**
     * Requires every registered stream to be complete and returns source snapshots in first-presentation order.
     * @throws IllegalArgumentException when any measured stream is incomplete
     */
    public List<Table> sources() {
        for (var stream : streams.values()) {
            if (stream.next != stream.first.fragmentCount()) {
                throw new IllegalArgumentException("semantic continuation stream is incomplete");
            }
        }
        return List.copyOf(sources);
    }
}
