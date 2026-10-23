// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Immutable repeating bands and page overlays; no font handles, components or backend state.
 * All lengths use points. Overlays use page coordinates and do not reserve body space. At most 64
 * overlay placements per value. No regions is the compatibility default. Owns no resources and is thread-safe.
 */
public record PageRegions(PageRegion header, PageRegion footer, Map<Variant, Bands> variants,
        List<PageOverlay> background, List<PageOverlay> foreground) {
    /** FIRST selects the first body page of each section; EVEN follows displayed page numbering. */
    public enum Variant { FIRST, EVEN }

    /** Complete pair for a variant. An empty band is explicitly blank, without fallback to default. */
    public record Bands(PageRegion header, PageRegion footer) {
        public static final Bands EMPTY = new Bands(PageRegion.EMPTY, PageRegion.EMPTY);
        public Bands { Objects.requireNonNull(header, "header"); Objects.requireNonNull(footer, "footer"); }
    }

    public static final PageRegions EMPTY = new PageRegions(PageRegion.EMPTY, PageRegion.EMPTY);
    /** Compatibility constructor for uniform bands. */
    public PageRegions(PageRegion header, PageRegion footer) { this(header, footer, Map.of()); }
    /** Compatibility constructor without page overlays. */
    public PageRegions(PageRegion header, PageRegion footer, Map<Variant, Bands> variants) {
        this(header, footer, variants, List.of(), List.of());
    }
    public PageRegions {
        Objects.requireNonNull(header, "header"); Objects.requireNonNull(footer, "footer");
        var copy = new EnumMap<Variant, Bands>(Variant.class);
        Objects.requireNonNull(variants, "variants").forEach((key, value) ->
                copy.put(Objects.requireNonNull(key, "variant"), Objects.requireNonNull(value, "bands")));
        variants = Collections.unmodifiableMap(copy);
        background = List.copyOf(background); foreground = List.copyOf(foreground);
        if ((long) background.size() + foreground.size() > 64) {
            throw new IllegalArgumentException("page overlay placement budget exceeded");
        }
    }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        var value = new Builder().header(header).footer(footer).background(background).foreground(foreground);
        value.variants.putAll(variants); return value;
    }

    /** Explicit source bands in default/FIRST/EVEN order, with equal sources counted once per kind. */
    public List<PageRegion> headers() { return sources(true); }
    public List<PageRegion> footers() { return sources(false); }
    /** Unique content sources per overlay layer, in placement order. */
    public List<PageRegion> backgrounds() { return background.stream().map(PageOverlay::content).distinct().toList(); }
    public List<PageRegion> foregrounds() { return foreground.stream().map(PageOverlay::content).distinct().toList(); }
    void requireContained(PageSize page) {
        background.forEach(box -> box.requireContained(page)); foreground.forEach(box -> box.requireContained(page));
    }
    private List<PageRegion> sources(boolean headers) {
        var values = new ArrayList<PageRegion>(); values.add(headers ? header : footer);
        variants.values().forEach(pair -> values.add(headers ? pair.header() : pair.footer()));
        return values.stream().distinct().toList();
    }

    /** Synchronous source mapping in declaration order, preserving variant presence and blank bands.
     * Names are HEADER/FOOTER, HEADER_FIRST/FOOTER_FIRST, HEADER_EVEN/FOOTER_EVEN, BACKGROUND:index
     * and FOREGROUND:index. Mapping order is bands followed by background and foreground. A mapper that needs
     * shared-source accounting must cache immutable input bands. Null results are rejected.
     */
    public PageRegions map(BiFunction<String, PageRegion, PageRegion> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        var value = builder().header(mapper.apply("HEADER", header)).footer(mapper.apply("FOOTER", footer));
        variants.forEach((variant, pair) -> value.variant(variant, new Bands(
                mapper.apply("HEADER_" + variant, pair.header()), mapper.apply("FOOTER_" + variant, pair.footer()))));
        value.background(mapOverlays(background, "BACKGROUND", mapper));
        value.foreground(mapOverlays(foreground, "FOREGROUND", mapper));
        return value.build();
    }
    private static List<PageOverlay> mapOverlays(List<PageOverlay> source, String name,
            BiFunction<String, PageRegion, PageRegion> mapper) {
        var result = new ArrayList<PageOverlay>();
        for (int index = 0; index < source.size(); index++) {
            var box = source.get(index);
            result.add(new PageOverlay(box.x(), box.y(), box.width(), mapper.apply(name + ":" + index, box.content())));
        }
        return List.copyOf(result);
    }

    /** Task-confined builder; callbacks are synchronous and never retained. */
    public static final class Builder {
        private PageRegion header = PageRegion.EMPTY;
        private PageRegion footer = PageRegion.EMPTY;
        private final EnumMap<Variant, Bands> variants = new EnumMap<>(Variant.class);
        private List<PageOverlay> background = new ArrayList<>();
        private List<PageOverlay> foreground = new ArrayList<>();
        private Builder() { }
        public Builder header(PageRegion value) { header = Objects.requireNonNull(value, "header"); return this; }
        public Builder footer(PageRegion value) { footer = Objects.requireNonNull(value, "footer"); return this; }
        /** Replaces a complete variant pair; absence selects the normal odd/even bands on the first page. */
        public Builder variant(Variant variant, Bands value) {
            variants.put(Objects.requireNonNull(variant, "variant"), Objects.requireNonNull(value, "bands")); return this;
        }
        /** Declares one band of a variant; an undeclared peer starts blank. */
        public Builder header(Variant variant, PageRegion value) {
            return variant(variant, new Bands(value, variants.getOrDefault(Objects.requireNonNull(variant, "variant"), Bands.EMPTY).footer()));
        }
        public Builder footer(Variant variant, PageRegion value) {
            return variant(variant, new Bands(variants.getOrDefault(Objects.requireNonNull(variant, "variant"), Bands.EMPTY).header(), value));
        }
        /** Removes the pair declaration. An explicitly empty pair instead suppresses both bands. */
        public Builder inherit(Variant variant) { variants.remove(Objects.requireNonNull(variant, "variant")); return this; }
        /** Replaces all page background placements; an empty list clears the layer. */
        public Builder background(List<PageOverlay> values) { background = new ArrayList<>(List.copyOf(values)); return this; }
        public Builder foreground(List<PageOverlay> values) { foreground = new ArrayList<>(List.copyOf(values)); return this; }
        public Builder background(PageOverlay value) { background.add(Objects.requireNonNull(value, "background")); return this; }
        public Builder foreground(PageOverlay value) { foreground.add(Objects.requireNonNull(value, "foreground")); return this; }
        public Builder header(Consumer<PageRegion.Builder> configure) {
            var value = header.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return header(value.build());
        }
        public Builder footer(Consumer<PageRegion.Builder> configure) {
            var value = footer.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value); return footer(value.build());
        }
        public PageRegions build() { return new PageRegions(header, footer, variants, background, foreground); }
    }
}
