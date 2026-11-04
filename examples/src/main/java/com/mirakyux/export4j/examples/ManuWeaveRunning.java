// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.components.PageRegionTemplates;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphLayouter;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the catalog's physical running-band facts to explicit semantic page regions. */
final class ManuWeaveRunning {
    record Configuration(PageSettings settings, PageRegions regions) { }

    private ManuWeaveRunning() { }

    static Map<String, FontGroup> fontRoles(ManuWeaveProfile profile, ManuWeaveFonts fonts) {
        var roles = new LinkedHashMap<String, FontGroup>();
        for (String name : List.of("header", "footer")) {
            if (enabled(profile, "sections.front_matter_page.", name)
                    || enabled(profile, "sections.body_page.", name)) {
                roles.put("mw-running-" + name, selection(profile, fonts, name).fonts());
            }
        }
        return Map.copyOf(roles);
    }

    private static ManuWeaveFonts.Selection selection(ManuWeaveProfile profile, ManuWeaveFonts fonts, String name) {
        String prefix = "running." + name + ".";
        return fonts.selection(profile.facts.getProperty(prefix + "font", ""), "",
                Boolean.parseBoolean(profile.facts.getProperty(prefix + "bold", "false")),
                Boolean.parseBoolean(profile.facts.getProperty(prefix + "italic", "false")), false);
    }

    static boolean sameGeometry(PageRegions left, PageRegions right) {
        return sameGeometry(left.header(), right.header()) && sameGeometry(left.footer(), right.footer());
    }

    private static boolean sameGeometry(PageRegion left, PageRegion right) {
        return left.height() == right.height() && left.gap() == right.gap()
                && left.edgeOffset() == right.edgeOffset() && left.leftInset() == right.leftInset()
                && left.rightInset() == right.rightInset();
    }

    static Configuration configure(ManuWeaveProfile profile, ManuWeaveFonts fonts,
            PageSettings source, String sourcePrefix, String effectivePrefix) {
        boolean header = enabled(profile, sourcePrefix, "header");
        boolean footer = enabled(profile, sourcePrefix, "footer");
        if (!header && !footer) { return new Configuration(source, PageRegions.EMPTY); }
        String key = sourcePrefix.contains("front_matter") ? "front" : "body";
        var regions = PageRegions.builder();
        if (header) { regions.header(band(profile, fonts, source, sourcePrefix, key, "header", false)); }
        if (footer) { regions.footer(band(profile, fonts, source, sourcePrefix, key, "footer", false)); }
        if (Boolean.parseBoolean(profile.facts.getProperty("sections.different_odd_even_headers", "false"))) {
            if (header) { regions.header(PageRegions.Variant.EVEN,
                    band(profile, fonts, source, sourcePrefix, key, "header", true)); }
            if (footer) { regions.footer(PageRegions.Variant.EVEN,
                    band(profile, fonts, source, sourcePrefix, key, "footer", true)); }
        }
        if (Boolean.parseBoolean(profile.facts.getProperty("sections.different_first_page", "false"))) {
            regions.variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY);
            profile.limitations.add("The source declares a different first-page region without its content; the specimen explicitly leaves that first-page pair empty.");
        }
        var value = regions.build();
        var templates = PageRegionTemplates.sectionFrom(value);
        var reserved = templates.bodySettings(source);
        profile.report.setProperty(effectivePrefix + "margin-top", "" + reserved.margins().top());
        profile.report.setProperty(effectivePrefix + "margin-bottom", "" + reserved.margins().bottom());
        profile.report.setProperty("effective.running." + key + ".enabled", "true");
        profile.limitations.add("Running-region text is fictional; source distances and available running typography are applied, with explicit PAGE/PAGES fields in the footer.");
        if (!reserved.equals(source)) {
            profile.limitations.add("Running bands require explicitly reported extra body clearance beyond the source page margins; the original source geometry remains in the report.");
        }
        return new Configuration(reserved, value);
    }

    private static boolean enabled(ManuWeaveProfile profile, String prefix, String name) {
        return profile.facts.containsKey(prefix + name + "_distance_mm")
                || profile.facts.containsKey("page." + name + "_distance_mm")
                || profile.facts.stringPropertyNames().stream().anyMatch(key -> key.startsWith("running." + name + "."));
    }

    private static PageRegion band(ManuWeaveProfile profile, ManuWeaveFonts fonts,
            PageSettings source, String prefix, String section, String name, boolean even) {
        String running = "running." + name + ".";
        boolean hasTypography = profile.facts.containsKey(running + "size_pt");
        double size = profile.number(running + "size_pt", 8);
        var selected = selection(profile, fonts, name);
        var group = selected.fonts();
        double minimum = ParagraphLayouter.minimumLineHeight(group, size);
        if (name.equals("footer")) { minimum = Math.max(minimum, fieldLineHeight(group, size)); }
        double requestedHeight = profile.number(running + "line_spacing_pt", size * 1.4);
        double height = Math.max(requestedHeight, minimum);
        if (height > requestedHeight) {
            profile.limitations.add("A running " + name + " line is enlarged to fit its explicit physical font"
                    + (name.equals("footer") ? " and full-ascent PAGE/PAGES field graphics." : "."));
        }
        double defaultOffset = name.equals("header") ? source.margins().top() : source.margins().bottom();
        String distance = name + "_distance_mm";
        double offset = profile.number(prefix + distance, profile.number("page." + distance, defaultOffset / ManuWeaveProfile.MM))
                * ManuWeaveProfile.MM;
        String role = "mw-running-" + name;
        var style = new InlineStyle(role, size, 0x404040, 0, false, false);
        var format = ParagraphFormat.builder().fontSize(size).lineHeight(height).spaceAfter(0)
                .alignment(name.equals("header") ? even ? ParagraphAlignment.RIGHT : ParagraphAlignment.LEFT : ParagraphAlignment.CENTER)
                .build();
        boolean anonymous = Boolean.parseBoolean(profile.facts.getProperty("example.anonymous", "false"));
        String authors = anonymous ? "Anonymous authors" : "Alex Example and Morgan Sample";
        List<InlineContent> text = name.equals("header")
                ? List.of(new InlineText(even ? authors : "Reproducible document layout", style))
                : List.of(new InlineText("Page ", style), new InlineField(InlineField.Kind.PAGE, "", size * 6, style),
                        new InlineText(" of ", style), new InlineField(InlineField.Kind.PAGES, "", size * 6, style));
        var paragraph = Paragraph.mixed("mw-running-" + section + "-" + name + (even ? "-even" : "-default"), text, format);
        String report = "effective.running." + section + "." + name + ".";
        profile.report.setProperty(report + "font-size", "" + size);
        profile.report.setProperty(report + "line-height", "" + height);
        profile.report.setProperty(report + "minimum-line-height", "" + minimum);
        profile.report.setProperty(report + "edge-offset", "" + offset);
        profile.report.setProperty(report + "gap", "0.0");
        profile.report.setProperty(report + "font", selected.actual());
        profile.report.setProperty(report + "requested-font", profile.facts.getProperty(running + "font", ""));
        profile.report.setProperty(report + "font-substituted", Boolean.toString(selected.substituted()));
        profile.report.setProperty(report + "font-external", Boolean.toString(selected.external()));
        if (!hasTypography) {
            profile.limitations.add("A running band has a source distance but no typography; the specimen explicitly uses 8 pt regular text with 11.2 pt lines.");
        }
        return new PageRegion(height, 0, offset, -1, -1, List.of(paragraph));
    }

    /** InlineField's Roman-baseline graphic reserves its full font size as ascent.
     * The shared OpenType line can therefore require more than the physical font's own line box. */
    private static double fieldLineHeight(FontGroup fonts, double size) {
        double descent = 0;
        var context = new FontRenderContext(null, true, true);
        for (var face : fonts.fonts()) {
            try (var input = new ByteArrayInputStream(face.data().bytes())) {
                var font = Font.createFont(Font.TRUETYPE_FONT, input).deriveFont((float) size);
                descent = Math.max(descent, Math.max(face.metrics().descent() * size,
                        font.getLineMetrics("Ag", context).getDescent()));
            } catch (IOException | FontFormatException failure) {
                throw new IllegalArgumentException("Explicit running font metrics are unavailable", failure);
            }
        }
        // TextLayout adds its float ascent/descent before comparing to the double line height.
        return Math.nextUp((double) ((float) size + (float) descent));
    }
}
