// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.FontScope;
import com.mirakyux.export4j.components.ImageComponent;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Self-authored, fictional article/report/thesis content shared by the formatting specimens. */
final class ManuWeaveSample {
    private final ManuWeaveProfile profile;
    private final ManuWeaveFonts fonts;
    private final List<SampleText> texts = new ArrayList<>();

    ManuWeaveSample(ManuWeaveProfile profile, ManuWeaveFonts fonts) {
        this.profile = profile;
        this.fonts = fonts;
    }

    SectionedDocument document() {
        var front = new ManuWeaveFlow();
        var body = new ManuWeaveFlow();
        String kind = profile.facts.getProperty("example.kind", "article");
        boolean thesis = kind.endsWith("thesis") || kind.equals("doctoral-dissertation");
        boolean anonymous = Boolean.parseBoolean(profile.facts.getProperty("example.anonymous", "false"));
        text(front, profile.role("title"), "MW-TITLE " + language(
                "可复现文档排版的实验研究", kind.equals("technical-report")
                        ? "Technical report on reproducible document layout" : "An experimental study of reproducible document layout"));
        if (profile.hasRole("title_en")) {
            text(front, "title_en", "MW-TITLE-EN An experimental study of reproducible document layout");
        }
        if (profile.hasRole("subtitle")) {
            text(front, "subtitle", "MW-SUBTITLE " + language("排版模板实验", "A formatting specimen"));
        }
        if (thesis) {
            text(front, profile.role("heading1"), "MW-DEGREE " + switch (kind) {
                case "doctoral-dissertation" -> "博士学位论文";
                case "masters-thesis" -> "硕士学位论文";
                default -> "本科毕业论文";
            });
        }
        text(front, profile.role("author"), "MW-AUTHOR " + (anonymous ? "Anonymous authors" : language("示例作者甲，示例作者乙", "Alex Example and Morgan Sample")));
        text(front, profile.role("affiliation"), "MW-AFFILIATION " + (anonymous ? "Affiliations withheld for anonymous review" : language("示例大学 文档工程实验室（虚构）", "Document Engineering Laboratory, Example University (fictional)")));
        if (profile.hasRole("author_en")) { text(front, "author_en", "MW-AUTHOR-EN Alex Example and Morgan Sample"); }
        if (profile.hasRole("affiliation_en")) { text(front, "affiliation_en", "MW-AFFILIATION-EN Example University, Fictional Laboratory"); }
        if (profile.hasRole("correspondence") || profile.hasRole("correspondence_zh")) {
            text(front, profile.role("correspondence"), "MW-CONTACT " + (anonymous ? "Contact withheld for review" : "author@example.invalid"));
        }

        ManuWeaveFlow abstracts = profile.bodyStartsAt.equals("first_abstract") ? body : front;
        abstractText(abstracts);
        if (thesis) {
            text(front, "toc", "MW-CONTENTS 目录：研究背景、实验方法、结果分析、结论与参考文献");
            profile.limitations.add("The thesis front matter includes a structural contents outline, not generated contents page fields.");
        }
        text(body, profile.role("heading1"), "MW-H1 " + language("1 研究背景", kind.equals("technical-report") ? "1 Scope and objectives" : "1 Introduction"));
        for (int index = 1; index <= 18; index++) {
            if (index == 7) { text(body, profile.role("heading1"), "MW-H2 " + language("2 实验方法", "2 Method")); }
            if (index == 10) {
                text(body, profile.role("heading2"), "MW-H21 " + language("2.1 确定性测量", "2.1 Deterministic measurement"));
            }
            if (index == 13) {
                text(body, profile.role("heading1"), "MW-H3 " + language("3 结果与讨论", "3 Results and discussion"));
                text(body, profile.role("table_caption"), "MW-TABLE " + language("表1 合成样本的处理结果", "Table 1. Results for synthetic records"));
                var results = new ManuWeaveFlow();
                table(results);
                body.addAtomic(results.build(), "table and caption");
                double width = columnWidth(profile.bodyPage, profile.bodyColumns);
                var figure = new ManuWeaveFlow();
                figure.add(new BoxComponent(width, 56, new ImageComponent(chart(), ImageComponent.Fit.STRETCH)));
                text(figure, profile.role("figure_caption"), "MW-FIGURE " + language("图1 合成数据示意图", "Figure 1. Synthetic measurement chart"));
                body.addAtomic(figure.build(), "figure and caption");
                String tex = "\\frac{a+b}{c+d}";
                text(body, "equation", "MW-EQUATION");
                body.add(alignedEquation(profile, fonts, tex));
                texts.add(new SampleText("equation_tex", tex));
                profile.report.setProperty("sample.equation.tex", tex);
            }
            text(body, "body", String.format(Locale.ROOT, "MW-P%02d ", index) + language(
                    "本研究使用虚构数据验证文档的可复现排版。每条记录只参与一次统计，文字采用显式字体资源，页面尺寸和分栏参数来自对应的格式档案。实验同时检查标题、摘要、表格和图题的样式，并保留原始内容的顺序。不同模板可以共享相同的数据与段落结构，但分别控制字号、行距、缩进和页边距，以便观察实际的分页结果。所有数值仅用于演示，不代表真实研究结论。",
                    "This study uses fictional records to examine reproducible document layout. Each record contributes once to the analysis. Explicit font resources keep measurement and painting consistent. The page size and column geometry are selected by the formatting profile. The experiment checks headings, abstracts, tables and captions while preserving the original content order. Shared content makes differences in typography and pagination visible without introducing any real research results."));
        }
        text(body, profile.role("heading1"), "MW-CONCLUSION " + language("4 结论", "4 Conclusion"));
        text(body, "body", "MW-SUMMARY " + language("显式资源和确定性布局使合成文档可以离线生成与检查。", "Explicit resources and deterministic layout make the synthetic document reproducible offline."));
        text(body, profile.role("heading1"), "MW-ACK " + language("致谢", "Acknowledgments"));
        text(body, "body", "MW-THANKS " + language("感谢开源基础设施与模板格式资料的贡献者。", "The authors acknowledge contributors of open-source infrastructure and formatting facts."));
        var references = new ManuWeaveFlow();
        text(references, "references_heading", "MW-REFS " + language("参考文献", "References"));
        var referenceResult = ManuWeaveReferences.references(profile);
        var referenceMarkers = List.of("MW-R1 ", "MW-R2 ", "MW-END ");
        for (int index = 0; index < referenceResult.entries().size(); index++) {
            text(references, "reference", referenceMarkers.get(index) + referenceResult.entries().get(index));
        }
        profile.report.setProperty("effective.references.mode", referenceResult.mode());
        profile.report.setProperty("effective.references.source", referenceResult.source());
        profile.report.setProperty("effective.references.implementation", referenceResult.implementation());
        profile.limitations.addAll(referenceResult.limitations());
        body.addAtomic(references.build(), "references heading and three entries");
        profile.report.setProperty("effective.pagination.min-lines-before", "2");
        profile.report.setProperty("effective.pagination.min-lines-after", "2");
        profile.report.setProperty("effective.pagination.heading-adjacency", "atomic-with-following-content");
        profile.report.setProperty("effective.pagination.columns-balanced", "true");
        var frontRunning = ManuWeaveRunning.configure(profile, fonts, profile.frontPage,
                "sections.front_matter_page.", "effective.front-page.");
        var bodyRunning = ManuWeaveRunning.configure(profile, fonts, profile.bodyPage,
                "sections.body_page.", "effective.page.");
        var sections = new ArrayList<Section>();
        if (!frontRunning.settings().equals(bodyRunning.settings())
                || !ManuWeaveRunning.sameGeometry(frontRunning.regions(), bodyRunning.regions()) || thesis) {
            sections.add(new Section("front", frontRunning.settings(),
                    List.of(block("front-content", columns(front.build(), profile.frontColumns))), 0, frontRunning.regions()));
            sections.add(new Section("body", bodyRunning.settings(),
                    List.of(block("body-content", columns(body.build(), profile.bodyColumns))), 0, bodyRunning.regions()));
        } else {
            List<Block> blocks;
            if (profile.bodyStartsAt.equals("document_start")) {
                var all = FlowColumn.builder().add(front.build()).add(body.build()).build();
                blocks = List.of(block("all-content", columns(all, profile.bodyColumns)));
            } else {
                blocks = List.of(block("front-content", columns(front.build(), profile.frontColumns)),
                        block("body-content", columns(body.build(), profile.bodyColumns)));
            }
            sections.add(new Section("document", bodyRunning.settings(), blocks, 0, bodyRunning.regions()));
        }
        return new SectionedDocument(new DocumentMetadata("ManuWeave specimen / " + profile.id,
                anonymous ? "" : "Export4j fictional examples"), sections);
    }

    private void abstractText(ManuWeaveFlow target) {
        text(target, "abstract_heading", "MW-ABSTRACT-H " + language("摘要", "Abstract"));
        text(target, profile.role("abstract"), "MW-ABSTRACT " + language(
                "本文演示学术文档的离线排版流程。实验使用完全虚构的数据和明确指定的字体，比较页面设置、分栏与文字样式对输出的影响，并通过独立工具核对生成结果。",
                "This fictional study demonstrates offline academic document generation. Explicit fonts and profile-specific page geometry are combined with structured content. Independent tools can inspect the resulting text, columns, images and typography."));
        text(target, profile.role("keywords"), "MW-KEYWORDS " + language("关键词：文档排版；可复现性；字体；分栏", "Keywords: document layout; reproducibility; fonts; columns"));
        if (profile.hasRole("abstract_en")) {
            text(target, "abstract_en", "MW-ABSTRACT-EN This bilingual specimen compares explicit academic formatting with deterministic offline output.");
        }
        if (profile.hasRole("keywords_en")) {
            text(target, "keywords_en", "MW-KEYWORDS-EN Keywords: explicit resources; deterministic layout; verification");
        }
    }

    private void text(ManuWeaveFlow flow, String role, String value) {
        var style = profile.style(role, fonts);
        texts.add(new SampleText(role, value));
        var paragraph = FlowColumn.builder();
        if (style.before() > 0) { paragraph.space(style.before()); }
        Component component = new FontScope(FlowText.builder(value).style(style.paragraph()).color(style.color())
                .pagination(PaginationPolicy.BALANCED).build(), style.fonts());
        if (style.left() > 0 || style.right() > 0) {
            component = FlowColumn.builder().padding(new PageMargins(0, style.right(), 0, style.left())).add(component).build();
        }
        paragraph.add(component);
        if (style.after() > 0) { paragraph.space(style.after()); }
        flow.add(paragraph.build(), role.startsWith("heading") || role.equals("abstract_heading")
                || role.equals("references_heading") || value.startsWith("MW-TABLE ") || value.equals("MW-EQUATION"));
    }

    private void table(ManuWeaveFlow flow) {
        String role = profile.role("table_body");
        var style = profile.style(role, fonts);
        var format = new CellFormat(style.paragraph().fontSize(), style.paragraph().lineHeight(),
                ParagraphAlignment.valueOf(style.paragraph().alignment().name()));
        var rows = new ArrayList<TableRow>();
        String[][] cells = {{"MW-C11 " + language("样本组", "Group"), "MW-C12 " + language("记录数", "Records")},
                {"MW-C21 " + language("实验组甲", "Set A"), "MW-C22 120"},
                {"MW-C31 " + language("实验组乙", "Set B"), "MW-C32 96"},
                {"MW-C41 " + language("对照组", "Control"), "MW-C42 108"}};
        for (String[] values : cells) {
            var row = new ArrayList<TableCell>();
            for (String value : values) {
                int rgb = Integer.parseInt(profile.report.getProperty("effective.role." + role + ".rgb"), 16);
                row.add(TableCell.mixed(List.of(new InlineText(value,
                        new InlineStyle("", format.fontSize(), rgb, 0, false, false))), format));
                texts.add(new SampleText(role, value));
            }
            rows.add(new TableRow(row));
        }
        var table = new Table("results-table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                rows, 0, TableFormat.builder().padding(3).spaceAfter(5).build());
        flow.add(new FontScope(FlowColumn.builder().semanticTable(table).build(), style.fonts()));
        profile.limitations.add("The synthetic table uses explicit 3 pt padding and a simple border grid; publisher-specific table rules remain source facts.");
        profile.limitations.add("The table cell projection preserves size, line height, color and alignment; paragraph indents and spacing do not apply inside cells.");
    }

    private Component columns(FlowColumn content, int count) {
        if (count == 1) { return content; }
        var columns = ContinuousColumns.builder(content).columns(count).gap(profile.gap).balanced(true);
        if (count == profile.bodyColumns && !profile.columnWeights.isEmpty()) {
            columns.columnWeights(profile.columnWeights);
        }
        return columns.build();
    }

    static Component alignedEquation(ManuWeaveProfile profile, ManuWeaveFonts fonts, String tex) {
        var style = profile.style("equation", fonts).paragraph();
        String key = "extracted_format.equations.alignment.value";
        String requested = profile.facts.getProperty(key, style.alignment().name()).toLowerCase(Locale.ROOT);
        var alignment = switch (requested) {
            case "center" -> FlowColumn.Alignment.CENTER;
            case "right" -> FlowColumn.Alignment.END;
            default -> FlowColumn.Alignment.START;
        };
        profile.report.setProperty("effective.equation.alignment", switch (alignment) {
            case START -> "LEFT";
            case CENTER -> "CENTER";
            case END -> "RIGHT";
        });
        profile.report.setProperty("effective.equation.alignment-source", profile.facts.containsKey(key)
                ? key : "role.equation.alignment");
        return FlowColumn.builder().alignment(alignment).add(ManuWeaveMath.equation(tex, style.fontSize())).build();
    }

    private static ComponentBlock block(String id, Component content) { return new ComponentBlock(id, content, 0, 0); }

    private double columnWidth(PageSettings settings, int columns) {
        double available = settings.contentWidth() - profile.gap * (columns - 1);
        return profile.columnWeights.isEmpty() ? available / columns : available
                * profile.columnWeights.stream().mapToDouble(Double::doubleValue).min().orElseThrow()
                / profile.columnWeights.stream().mapToDouble(Double::doubleValue).sum();
    }

    private String language(String chinese, String english) { return profile.chinese ? chinese : english; }

    void report() {
        profile.report.setProperty("sample.count", "" + texts.size());
        for (int index = 0; index < texts.size(); index++) {
            profile.report.setProperty("sample." + index + ".role", texts.get(index).role());
            profile.report.setProperty("sample." + index + ".text", texts.get(index).text());
        }
        profile.report.setProperty("image.count", "1");
        profile.report.setProperty("limitation.count", "" + profile.limitations.size());
        int index = 0;
        for (String limitation : profile.limitations) { profile.report.setProperty("limitation." + index++, limitation); }
    }

    String source() { return String.join("\n", texts.stream().map(SampleText::text).toList()) + "\n"; }

    private record SampleText(String role, String text) { }

    private static RgbImage chart() {
        byte[] pixels = new byte[240 * 80 * 3];
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 240; x++) {
                int bar = x / 80;
                boolean painted = x % 80 >= 18 && x % 80 <= 62 && y >= new int[] {12, 30, 21}[bar] && y < 72;
                int color = painted ? new int[] {0x285078, 0x4b7b96, 0x83aab7}[bar] : 0xf5f7f8;
                int offset = (y * 240 + x) * 3;
                pixels[offset] = (byte) (color >> 16);
                pixels[offset + 1] = (byte) (color >> 8);
                pixels[offset + 2] = (byte) color;
            }
        }
        return new RgbImage(240, 80, pixels);
    }

}
