// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Heading;

/** Absolute-indexed current-page/keep-chain window. Owns neither its source nor emitted snapshots. */
final class FlowBlockInput {
    private record Entry(Block original, Block layout) { }

    /** Only this bridge is unwrapped by the public layout entry point. */
    static final class SourceFailure extends UncheckedIOException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private SourceFailure(IOException cause) { super(cause); }
    }

    private final List<Block> fixed;
    private final List<Block> fixedLayout;
    private final BlockSource source;
    private final BiConsumer<Block, Integer> validator;
    private final ArrayList<Entry> window = new ArrayList<>();
    private int base;
    private int head;
    private int nextIndex;
    private boolean ended;

    private FlowBlockInput(List<Block> fixed, BlockSource source, BiConsumer<Block, Integer> validator) {
        this.fixed = fixed;
        fixedLayout = fixed == null ? null : fixed.stream().map(FlowBlockInput::normalized).toList();
        this.source = source;
        this.validator = validator;
    }

    static FlowBlockInput fixed(List<Block> values) {
        return new FlowBlockInput(List.copyOf(values), null, null);
    }

    static FlowBlockInput streaming(BlockSource source, BiConsumer<Block, Integer> validator) {
        return new FlowBlockInput(null, Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(validator, "validator"));
    }

    boolean streaming() { return source != null; }
    int totalHint() { return fixed == null ? -1 : fixed.size(); }

    boolean has(int index) {
        if (index < 0) { throw new IndexOutOfBoundsException("negative block index"); }
        if (fixed != null) { return index < fixed.size(); }
        if (index < base + head) { throw new IllegalStateException("completed block was already released"); }
        while (index >= nextIndex && !ended) {
            ExecutionContext.checkpoint();
            java.util.Optional<Block> supplied;
            try { supplied = Objects.requireNonNull(source.next(), "block source result"); }
            catch (IOException failure) { throw new SourceFailure(failure); }
            ExecutionContext.checkpoint();
            if (supplied.isEmpty()) { ended = true; break; }
            Block original = supplied.orElseThrow();
            validator.accept(original, nextIndex);
            window.add(new Entry(original, normalized(original)));
            nextIndex++;
        }
        return index < nextIndex;
    }

    Block get(int index) {
        if (!has(index)) { throw new IndexOutOfBoundsException("block index exceeds source"); }
        return fixedLayout != null ? fixedLayout.get(index) : window.get(index - base).layout();
    }

    List<Block> sources(LayoutPage page) {
        var result = new ArrayList<Block>();
        var included = new HashSet<Integer>();
        for (var element : page.elements()) {
            int index = element.blockIndex();
            if (included.add(index)) {
                if (!has(index)) { throw new IllegalStateException("page source block is unavailable"); }
                result.add(fixed != null ? fixed.get(index) : window.get(index - base).original());
            }
        }
        return List.copyOf(result);
    }

    void releaseBefore(int index) {
        if (fixed != null) { return; }
        while (base + head < index && head < window.size()) { window.set(head++, null); }
        if (head == window.size()) {
            window.clear();
            base = nextIndex;
            head = 0;
        } else if (head >= 1024 && head >= window.size() / 2) {
            window.subList(0, head).clear();
            base += head;
            head = 0;
        }
    }

    private static Block normalized(Block original) {
        return original instanceof Heading heading ? heading.paragraph() : original;
    }
}
