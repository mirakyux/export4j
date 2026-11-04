// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Finite fictional reference specimens selected from explicit catalog facts; not a CSL/BibTeX engine. */
final class ManuWeaveReferences {
    private static final String ROOT = "extracted_format.references.";
    private static final String CITATIONS = "extracted_format.citations.";
    private static final List<Article> ARTICLES = List.of(
            new Article("Alex", "Example", "Reproducible document layout", "Fictional Engineering Review", "1", "1", "1-8", "2026"),
            new Article("Morgan", "Sample", "Explicit fonts and bounded pagination", "Synthetic Systems Journal", "2", "2", "9-16", "2026"),
            new Article("Robin", "Demo", "Independent verification of generated documents", "Example Proceedings", "3", "1", "17-24", "2026"));
    private ManuWeaveReferences() { }

    static Result references(ManuWeaveProfile profile) { return references(profile.facts); }

    static Result references(Properties facts) {
        var limitations = new ArrayList<String>();
        String key = first(facts, ROOT + "default_citation_style.value", ROOT + "citation_mode.value",
                ROOT + "scheme.value", ROOT + "in_text_citation_scheme.value", ROOT + "style.value",
                CITATIONS + "journal_reference_family.value", CITATIONS + "bibliography_style.value",
                ROOT + "bibliography_style.value", ROOT + "label_pattern.value", ROOT + "numbering.value");
        String value = key.isEmpty() ? "" : facts.getProperty(key);
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        String mode;
        if (normalized.contains("textual footnote")) { mode = "textual-footnote"; }
        else if (normalized.contains("author") || normalized.contains("year-label")
                || List.of("apa", "harvard", "chicago", "mla", "apacite").contains(normalized)) {
            mode = "author-year";
        } else if (normalized.equals("eg-alpha-doi")) { mode = "alphabetic-label"; }
        else { mode = "numeric"; }
        String source = key.isEmpty() ? "no-explicit-citation-mode;example-default=numeric" : key + "=" + value;
        if (key.isEmpty()) { limitations.add("The source does not select a citation mode; this fictional specimen selects numeric labels."); }
        if ("true".equals(facts.getProperty(CITATIONS + "initial_submission_any_consistent_style.value"))) {
            limitations.add("The source allows any consistent style at initial submission; later-stage style requirements are not applied to it.");
        }
        if (mode.equals("textual-footnote")) {
            limitations.add("The source requests textual footnote references. This specimen formats entries but does not place them as page footnotes.");
        }
        if (mode.equals("alphabetic-label")) {
            limitations.add("Alphabetic sample labels demonstrate the selected family; the external eg-alpha-doi bibliography program is not executed.");
        }
        String order = facts.getProperty(ROOT + "order.value", facts.getProperty(ROOT + "ordering.value",
                facts.getProperty(ROOT + "entry_order.value", "")));
        boolean alphabetical = mode.equals("author-year") || mode.equals("alphabetic-label")
                || order.contains("author") || order.contains("alphabetical");
        if (order.equals("citation") || order.equals("citation_order")) { alphabetical = false; }
        var articles = new ArrayList<>(ARTICLES);
        if (alphabetical) { articles.sort(Comparator.comparing(Article::surname).thenComparing(Article::year)); }
        var entries = new ArrayList<String>();
        for (int index = 0; index < articles.size(); index++) {
            Article article = articles.get(index);
            String label = switch (mode) {
                case "author-year" -> "";
                case "alphabetic-label" -> "[" + article.surname().substring(0, 2) + article.year().substring(2) + "] ";
                default -> "[" + (index + 1) + "] ";
            };
            entries.add(label + entry(article, facts, mode));
        }
        limitations.add("Three fictional single-author articles demonstrate explicit label, ordering, name, title and year-position facts; this is not full CSL, APA, IEEEtran, ACM or publisher bibliography compliance.");
        limitations.add("Journal/volume emphasis, DOI generation, citation sorting/compression and external bibliography macros are not implemented by this text specimen.");
        return new Result(List.copyOf(entries), mode, source, "finite-fictional-reference-formatting-v1",
                List.copyOf(limitations));
    }

    private static String entry(Article article, Properties facts, String mode) {
        String nameOrder = facts.getProperty(ROOT + "author_name_order.value", "");
        String author = nameOrder.contains("given_names") ? article.given() + " " + article.surname()
                : nameOrder.contains("initials_surname") || nameOrder.contains("initials_then_surname")
                        ? article.given().charAt(0) + ". " + article.surname()
                        : article.surname() + ", " + article.given().charAt(0) + ".";
        String fields = facts.getProperty(ROOT + "article_fields.value",
                facts.getProperty(ROOT + "article_field_order.value", ""));
        boolean includeTitle = !"false".equals(facts.getProperty(ROOT + "article_title_included.value"))
                && (fields.isEmpty() || fields.contains("title"));
        String title = includeTitle ? article.title() : "";
        if (!title.isEmpty() && "quoted".equals(facts.getProperty(ROOT + "article_title.value"))) { title = "\"" + title + "\""; }
        String yearPosition = facts.getProperty(ROOT + "year_position.value", "");
        if (yearPosition.isEmpty() && fields.contains("\"year\"") && fields.contains("\"journal\"")) {
            yearPosition = fields.indexOf("\"year\"") < fields.indexOf("\"journal\"") ? "after_authors"
                    : fields.indexOf("\"pages\"") > fields.indexOf("\"year\"") ? "before_pages" : "after_pages";
        }
        if (yearPosition.isEmpty()) { yearPosition = mode.equals("author-year") ? "after_authors" : "before_pages"; }
        String prefix = author + (yearPosition.equals("after_authors") ? " (" + article.year() + ")." : author.endsWith(".") ? "" : ".");
        String journal = article.journal() + ", " + article.volume();
        if (fields.contains("number")) { journal += "(" + article.number() + ")"; }
        if (yearPosition.equals("before_pages")) { journal += " (" + article.year() + ")"; }
        journal += ", " + article.pages();
        if (yearPosition.equals("after_pages")) { journal += " (" + article.year() + ")"; }
        return prefix + (title.isEmpty() ? " " : " " + title + ". ") + journal + ".";
    }

    private static String first(Properties facts, String... keys) {
        for (String key : keys) { if (!facts.getProperty(key, "").isBlank()) { return key; } }
        return "";
    }
    record Result(List<String> entries, String mode, String source, String implementation, List<String> limitations) { }
    private record Article(String given, String surname, String title, String journal, String volume,
            String number, String pages, String year) { }
}
