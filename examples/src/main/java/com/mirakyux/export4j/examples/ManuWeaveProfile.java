// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Example-only projection of attributed formatting facts onto Export4j's public components. */
final class ManuWeaveProfile {
    static final double MM = 72.0 / 25.4;
    final Properties facts;
    final Properties report = new Properties();
    final Set<String> limitations = new LinkedHashSet<>();
    final String id;
    final boolean chinese;
    final PageSettings frontPage;
    final PageSettings bodyPage;
    final int frontColumns;
    final int bodyColumns;
    final double gap;
    final String bodyStartsAt;
    final List<Double> columnWeights;

    ManuWeaveProfile(Properties facts) {
        this.facts = facts;
        report.putAll(facts);
        id = facts.getProperty("id");
        chinese = facts.getProperty("example.language", "en").equals("zh");
        frontPage = page("sections.front_matter_page.");
        bodyPage = page("sections.body_page.");
        frontColumns = integer("sections.front_matter_columns", integer("page.columns", 1));
        bodyColumns = integer("sections.body_columns", integer("page.columns", 1));
        gap = number("sections.body_column_gap_mm", number("page.column_gap_mm", 6)) * MM;
        bodyStartsAt = facts.getProperty("sections.body_starts_at", "document_start");
        String widthValues = facts.getProperty("sections.body_page.column_widths_mm", facts.getProperty("page.column_widths_mm", "[]"));
        columnWeights = widthValues.equals("[]") ? List.of() : java.util.Arrays.stream(
                widthValues.substring(1, widthValues.length() - 1).split(",")).map(String::trim).map(Double::valueOf).toList();
        if (frontColumns < 1 || frontColumns > 3 || bodyColumns < 1 || bodyColumns > 3
                || gap < 0 || bodyPage.contentWidth() <= gap * (bodyColumns - 1)) {
            throw new IllegalArgumentException("Invalid example column geometry: " + id);
        }
        if (!columnWeights.isEmpty() && (columnWeights.size() != bodyColumns
                || columnWeights.stream().anyMatch(value -> !Double.isFinite(value) || value <= 0))) {
            throw new IllegalArgumentException("Invalid explicit column widths: " + id);
        }
        pageReport("effective.page.", bodyPage);
        pageReport("effective.front-page.", frontPage);
        report.setProperty("effective.front-columns", "" + frontColumns);
        report.setProperty("effective.body-columns", "" + bodyColumns);
        report.setProperty("effective.column-gap", "" + gap);
        report.setProperty("effective.column-weights", columnWeights.toString());
        if (!columnWeights.isEmpty()) {
            limitations.add("Explicit column widths are used as relative weights within the declared page margins and gap.");
        }
        if (!facts.containsKey("page.columns") && !facts.containsKey("sections.body_columns")) {
            limitations.add("Unspecified column count uses the single-column example default.");
        }
        if (!facts.containsKey("page.column_gap_mm") && !facts.containsKey("sections.body_column_gap_mm")) {
            limitations.add("Unspecified column gap uses the 6 mm example default.");
        }
        if (!facts.containsKey("sections.body_starts_at")) {
            limitations.add("Unspecified front/body boundary applies the page column setting to the entire specimen.");
        }
        limitations.add("Example content is fictional; the PDF is a formatting specimen, not publisher certification.");
        limitations.add("Fonts are explicit physical resources; requested and actual families and external mappings are reported for every role.");
        limitations.add("TeX class macros, external bibliography engines and publisher artwork are not executed.");
        limitations.add("Paragraph spacing is explicit flow space; Word automatic spacing and page grids are not reproduced.");
        if (chinese) { limitations.add("Chinese glyphs use an explicit physical font; no synthetic bold or italic is applied."); }
        if (!frontPage.equals(bodyPage)) {
            limitations.add("Different front/body page settings require an explicit new Export4j section and page break.");
        }
    }

    static ManuWeaveProfile load(String id) throws IOException {
        var properties = new Properties();
        try (var input = ManuWeaveProfile.class.getResourceAsStream("/templates/manuweave/profiles/" + id + ".xml")) {
            if (input == null) { throw new IOException("Missing ManuWeave profile: " + id); }
            properties.loadFromXML(input);
        }
        if (!id.equals(properties.getProperty("id"))) { throw new IOException("Mismatched ManuWeave profile: " + id); }
        return new ManuWeaveProfile(properties);
    }

    private PageSettings page(String prefix) {
        double width = pageNumber(prefix, "width_mm", 210);
        double height = pageNumber(prefix, "height_mm", 297);
        return new PageSettings(new PageSize(width * MM, height * MM), new PageMargins(
                pageNumber(prefix, "margin_top_mm", 25.4) * MM,
                pageNumber(prefix, "margin_right_mm", 25.4) * MM,
                pageNumber(prefix, "margin_bottom_mm", 25.4) * MM,
                pageNumber(prefix, "margin_left_mm", 25.4) * MM));
    }

    private double pageNumber(String prefix, String key, double fallback) {
        if (facts.containsKey(prefix + key)) { return number(prefix + key, fallback); }
        if (facts.containsKey("page." + key)) { return number("page." + key, fallback); }
        limitations.add("Unspecified page." + key + " uses example default " + fallback + " mm.");
        return fallback;
    }

    private void pageReport(String prefix, PageSettings settings) {
        report.setProperty(prefix + "width", "" + settings.size().width());
        report.setProperty(prefix + "height", "" + settings.size().height());
        report.setProperty(prefix + "margin-top", "" + settings.margins().top());
        report.setProperty(prefix + "margin-right", "" + settings.margins().right());
        report.setProperty(prefix + "margin-bottom", "" + settings.margins().bottom());
        report.setProperty(prefix + "margin-left", "" + settings.margins().left());
    }

    String role(String base) {
        if (chinese && hasRole(base + "_zh")) { return base + "_zh"; }
        if (!hasRole(base)) {
            if ((base.equals("figure_caption") || base.equals("table_caption")) && hasRole("caption")) { return "caption"; }
            if (base.equals("table_body") && hasRole("table")) { return "table"; }
        }
        return base;
    }

    boolean hasRole(String role) {
        return facts.stringPropertyNames().stream().anyMatch(key -> key.startsWith("role." + role + "."));
    }

    Set<String> roles() {
        var roles = new LinkedHashSet<String>();
        facts.stringPropertyNames().stream().filter(key -> key.matches("role\\.[^.]+\\..+"))
                .map(key -> key.substring(5).split("\\.", 2)[0]).sorted().forEach(roles::add);
        return roles;
    }

    Style style(String role, ManuWeaveFonts fonts) {
        String prefix = "role." + role + ".";
        String fallback = "role.body.";
        if (!hasRole(role)) { limitations.add("Unspecified role " + role + " uses example body-derived typography."); }
        var unspecified = new java.util.ArrayList<String>();
        for (String field : List.of("size_pt", "line_spacing_pt", "bold", "italic", "alignment",
                "first_line_pt", "left_indent_pt", "right_indent_pt", "space_before_pt", "space_after_pt", "font_color")) {
            if (!facts.containsKey(prefix + field)) { unspecified.add(field); }
        }
        if (!unspecified.isEmpty()) {
            limitations.add(role + " unspecified fields use reported example defaults: " + String.join(", ", unspecified) + ".");
        }
        double defaultSize = role.startsWith("title") ? 18 : role.startsWith("heading") ? 12 : 10;
        double size = number(prefix + "size_pt", number(fallback + "size_pt", defaultSize));
        boolean bold = Boolean.parseBoolean(facts.getProperty(prefix + "bold", "false"));
        boolean italic = Boolean.parseBoolean(facts.getProperty(prefix + "italic", "false"));
        String requested = facts.getProperty(prefix + "font", facts.getProperty(fallback + "font", "Liberation Sans"));
        String eastAsian = facts.getProperty(prefix + "east_asia_font", facts.getProperty(fallback + "east_asia_font", ""));
        var selection = fonts.selection(requested, eastAsian, bold, italic, chinese);
        FontGroup group = selection.fonts();
        if (selection.substituted()) { limitations.add(role + " source font is substituted: " + requested + " -> " + selection.actual() + "."); }
        double requestedLine = number(prefix + "line_spacing_pt", size * number(prefix + "line_spacing", 1.4));
        double minimumLine = group.fonts().stream()
                .mapToDouble(face -> (face.metrics().ascent() + face.metrics().descent()) * size).max().orElseThrow();
        double line = Math.max(requestedLine, Math.nextUp(minimumLine));
        if (requestedLine < minimumLine) {
            limitations.add(role + " line spacing increased to fit the explicitly substituted font metrics.");
        }
        String rule = facts.getProperty(prefix + "line_spacing_rule", "fixed");
        if (!rule.equals("exact") && !rule.equals("fixed")) {
            limitations.add(role + " " + rule + " spacing is represented by the reported fixed baseline interval.");
        }
        double first = number(prefix + "first_line_pt", 0);
        double left = number(prefix + "left_indent_pt", 0);
        double right = number(prefix + "right_indent_pt", 0);
        double firstIndent = Math.max(0, first);
        double nextIndent = Math.max(0, -first);
        double leftPadding = left + Math.min(0, first);
        if (leftPadding < 0 || right < 0) {
            limitations.add(role + " negative external indent is clamped to the example column boundary.");
        }
        leftPadding = Math.max(0, leftPadding);
        right = Math.max(0, right);
        String alignment = facts.getProperty(prefix + "alignment", role.startsWith("title") ? "center" : "left");
        TextAlignment align = switch (alignment.toLowerCase(Locale.ROOT)) {
            case "justify", "both" -> TextAlignment.JUSTIFY;
            case "center" -> TextAlignment.CENTER;
            case "right" -> TextAlignment.RIGHT;
            default -> TextAlignment.LEFT;
        };
        int color = color(prefix + "font_color");
        var style = new ParagraphStyle(size, line, firstIndent, nextIndent, align);
        String effective = "effective.role." + role + ".";
        report.setProperty(effective + "font-size", "" + size);
        report.setProperty(effective + "line-height", "" + line);
        report.setProperty(effective + "first-line-indent", "" + firstIndent);
        report.setProperty(effective + "subsequent-line-indent", "" + nextIndent);
        report.setProperty(effective + "left-padding", "" + leftPadding);
        report.setProperty(effective + "right-padding", "" + right);
        report.setProperty(effective + "alignment", align.name());
        report.setProperty(effective + "rgb", String.format(Locale.ROOT, "%06X", color));
        report.setProperty(effective + "font", selection.actual());
        report.setProperty(effective + "requested-font", requested);
        report.setProperty(effective + "requested-east-asian-font", eastAsian);
        report.setProperty(effective + "font-substituted", "" + selection.substituted());
        report.setProperty(effective + "font-external", "" + selection.external());
        double before = Math.max(0, number(prefix + "space_before_pt", 0));
        double after = Math.max(0, number(prefix + "space_after_pt", 0));
        report.setProperty(effective + "space-before", "" + before);
        report.setProperty(effective + "space-after", "" + after);
        return new Style(style, group, new RgbColor((color >> 16 & 255) / 255.0,
                (color >> 8 & 255) / 255.0, (color & 255) / 255.0), leftPadding, right, before, after);
    }

    private int color(String key) {
        String value = facts.getProperty(key, "000000").replace("#", "");
        if (!value.matches("[0-9a-fA-F]{6}")) {
            limitations.add(key + " is not an RGB hex color; example uses black.");
            return 0;
        }
        return Integer.parseInt(value, 16);
    }

    double number(String key, double fallback) {
        String value = facts.getProperty(key);
        if (value == null) { return fallback; }
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) { throw new IllegalArgumentException("Invalid formatting number " + key + ": " + id); }
        return parsed;
    }

    private int integer(String key, int fallback) {
        double value = number(key, fallback);
        if (value != Math.rint(value)) { throw new IllegalArgumentException("Nonintegral columns: " + id); }
        return Math.toIntExact((long) value);
    }

    record Style(ParagraphStyle paragraph, FontGroup fonts, RgbColor color, double left, double right,
            double before, double after) { }
}
