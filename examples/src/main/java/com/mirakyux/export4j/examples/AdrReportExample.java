// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Hand-written semantic model for a dense adverse-reaction case report, using fictional data. */
public final class AdrReportExample {
    private static final Path OUTPUT = Path.of("examples/build/adr-report/java.pdf");
    private static final double[] DRUG_COLUMNS = {
        26, 28.1, 28.35, 28.35, 28.35, 28.35, 39.45, 24.3, 32.2,
        26.7, 28.7, 29.45, 25.9, 27.3, 29.8, 28.35, 42.1, 36.7
    };
    private static final String DRUG_NOTE_ONE = "注1：1-假药  2-用药过量  3-父源暴露  4-使用了超出有效期的药品  "
            + "5-检测并合格的批号  6-检测并不合格的批号  7-用药错误  8-误用  9-滥用  10-职业暴露  11-超说明书使用";
    private static final String DRUG_NOTE_TWO = "注2：1-停止用药  2-减少剂量  3-增加剂量  4-剂量不变  0-不详  9-不适用";

    private AdrReportExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT.getParent());
        var result = AdrReportData.engine().export(document(AdrReportData.data()))
                .backend(new PdfBackend()).to(OUTPUT);
        // Selecting the explicitly configured Chinese fallback font is informational.
        result.contextualDiagnostics().stream()
                .filter(value -> value.diagnostic().severity() == TextDiagnostic.Severity.WARNING)
                .findFirst().ifPresent(value -> { throw new IllegalStateException("ADR code example warning: " + value); });
        System.out.println("ADR code example: " + result.display().pages().size() + " naturally flowing pages");
    }

    /** Builds every block with Java model APIs; no template parser or original PDF is used. */
    static SectionedDocument document(Map<String, Object> data) {
        var report = group(data, "report");
        var patient = group(data, "patient");
        var reaction = group(data, "reaction");
        var reporter = group(data, "reporter");
        var blocks = new ArrayList<Block>();

        blocks.add(paragraph("adr-disclaimer", "虚构样例 · 可编辑模板", 8, 15, 0,
                ParagraphAlignment.LEFT, new InlineStyle("", 8, 0x888888, 0, false, false)));
        var titleFormat = Heading.defaultFormat(1).toBuilder().fontSize(18).lineHeight(28)
                .alignment(ParagraphAlignment.CENTER).spaceBefore(0).spaceAfter(12).build();
        blocks.add(Heading.rich("adr-title", List.of(new InlineText("上市许可持有人药品不良反应报告表",
                InlineStyle.of(18))), 1, titleFormat));
        blocks.add(paragraph("adr-report-type", "严重报告" + value(report, "serious")
                + "           境外报告" + value(report, "overseas") + "           首次报告" + value(report, "initial")
                + "           跟踪报告" + value(report, "followup") + "           病例编号*", 10.5, 15.5, 0));
        blocks.add(paragraph("adr-case-number", value(report, "caseId"), 10.5, 15.5, 8,
                ParagraphAlignment.LEFT, new InlineStyle("", 10.5, 0, 0, true, false)));
        blocks.add(paragraph("adr-report-source", "报告来源*   医疗机构" + value(report, "medicalSource")
                + "   经营企业□   个人□   文献□   研究□   项目□   其他□   监管机构□", 10.5, 15.5, 31));

        blocks.add(patientTable(patient));
        blocks.add(diseaseTable(rows(data, "diseases")));
        blocks.add(single("adr-suspect-heading", "怀疑用药（可重复）", 16, 1));
        blocks.add(suspectDrugTable(rows(data, "suspectDrugs")));
        blocks.add(table("adr-suspect-notes", 1).format(format(1, 1, true))
                .row(cell(DRUG_NOTE_ONE, 26.8, ParagraphAlignment.LEFT))
                .row(cell(DRUG_NOTE_TWO, 16.6, ParagraphAlignment.LEFT)).build());
        blocks.add(single("adr-concomitant-heading", "合并用药（可重复）", 14.1, 1));
        blocks.add(drugHeaders(table("adr-concomitant-drugs", DRUG_COLUMNS), 13.83, false).build());
        blocks.add(table("adr-concomitant-notes", 1)
                .row(cell(DRUG_NOTE_ONE, 14.85, ParagraphAlignment.LEFT))
                .row(cell(DRUG_NOTE_TWO, 14.1, ParagraphAlignment.LEFT)).build());
        blocks.add(single("adr-related-devices", "相关器械：" + value(report, "devices"), 14.85, 15.85));
        blocks.add(reactionTable(reaction));
        blocks.add(single("adr-reaction-description",
                "不良反应过程描述*（包括发生场所、症状、体征、临床检验等）及处理情况（可附页）：\n"
                        + value(reaction, "description"), 15.4, 46.7));
        blocks.add(single("adr-death", "死亡时间：____年____月____日    直接死因：________________\n"
                + "是否尸检：是□  否□  不详□       尸检结果：________________", 17.19, 1));
        blocks.add(single("adr-tests-heading", "相关实验室检查信息（可重复）", 18.25, 1));
        blocks.add(table("adr-tests", 46, 122, 100, 120, 149.5)
                .row(cells(14.1, "序号", "检查项目", "检查日期", "结果（单位）", "正常值范围（低值-高值）")).build());
        int testIndex = 0;
        for (var test : rows(data, "tests")) {
            blocks.add(table("adr-test-result@" + testIndex++, 46, 122, 100, 120, 149.5)
                    .row(cells(14.1, ParagraphAlignment.LEFT, value(test, "number"), value(test, "name"),
                            value(test, "date"), value(test, "result"), value(test, "reference"))).build());
        }
        blocks.add(reporterTable(reporter, report));
        blocks.add(table("adr-remarks", 60, 477.5).format(format(5, 5, false))
                .row(cell("备    注", 15.4, ParagraphAlignment.CENTER),
                        cell("其他需说明的情况：\n" + value(report, "remarks"), 15.4, ParagraphAlignment.LEFT)).build());

        var footerStyle = new InlineStyle("", 10, 0x888888, 0, false, false);
        var footer = Paragraph.mixed("adr-page-number", List.of(new InlineText("- ", footerStyle),
                new InlineField(InlineField.Kind.PAGE, "", 16, footerStyle), new InlineText(" -", footerStyle)),
                paragraphFormat(10, 18, 0, ParagraphAlignment.CENTER));
        var settings = PageSettings.builder().size(595.3, 841.9).margins(42.5, 29.5, 29.7, 28.3).build();
        var section = Section.builder("document").pageSettings(settings)
                .pageRegions(new PageRegions(PageRegion.EMPTY, new PageRegion(18, 0, List.of(footer))))
                .blocks(blocks).build();
        return SectionedDocument.builder().metadata(new DocumentMetadata("药品不良反应报告表（虚构样例）", "Export4j"))
                .section(section).build();
    }

    private static Table patientTable(Map<?, ?> patient) {
        return table("adr-patient", 51, 45, 72, 49, 48, 65, 57, 64, 86.5)
                .headerCells(cell("患者信息", 18.5, ParagraphAlignment.LEFT, 9, 1))
                .row(cells(14.75, "姓名*", "性别*", "出生日期*", "年龄", "国籍", "民族 / 种族", "身高\n（cm）", "体重\n（kg）", "联系电话"))
                .row(cells(14.75, value(patient, "name"), value(patient, "sex"), value(patient, "birthDate"),
                        value(patient, "age"), value(patient, "country"), value(patient, "ethnicity"),
                        value(patient, "height"), value(patient, "weight"), value(patient, "telephone")))
                .row(cell("医疗机构 / 经营企业名称：" + value(patient, "institution"), 14.5, ParagraphAlignment.LEFT, 4, 1),
                        cell("既往药品不良反应及药物过敏史\n有□       无□       不详" + value(patient, "allergyUnknown"),
                                14.5, ParagraphAlignment.LEFT, 5, 2))
                .row(cell("病房号 / 门诊号：" + value(patient, "recordId"), 14.5, ParagraphAlignment.LEFT, 4, 1))
                .row(cell("相关重要信息：\n吸烟    有□________无" + value(patient, "noSmoking") + "       不详□\n"
                        + "饮酒    有□________无" + value(patient, "noDrinking") + "       不详□\n"
                        + "过敏史  有□________无□           不详" + value(patient, "allergyUnknown") + "\n"
                        + "其他（如肝病史、肾病史、家族史）  有□______无□    不详□", 15.8, ParagraphAlignment.LEFT, 9, 1))
                .build();
    }

    private static Table diseaseTable(List<Map<?, ?>> diseases) {
        var result = table("adr-diseases", 46, 65, 95, 139, 192.5)
                .headerCells(cell("相关疾病信息（可重复）", 16.3, ParagraphAlignment.LEFT, 5, 1))
                .headerCells(cells(14.1, "序号", "疾病名称", "开始日期", "结束日期", "报告时疾病是否仍存在"));
        for (var disease : diseases) {
            result.row(cells(15.1, value(disease, "number"), value(disease, "name"), value(disease, "start"),
                    value(disease, "end"), value(disease, "ongoing")));
        }
        return result.build();
    }

    private static Table suspectDrugTable(List<Map<?, ?>> drugs) {
        var result = drugHeaders(table("adr-suspect-drugs", DRUG_COLUMNS), 13.23, true);
        for (var drug : drugs) {
            result.row(cells(15.5, value(drug, "number"), value(drug, "approval"), value(drug, "brand"), value(drug, "name"),
                    value(drug, "form"), value(drug, "strength"), value(drug, "holder"), value(drug, "batch"),
                    value(drug, "expiry"), value(drug, "route"), value(drug, "dose"), value(drug, "frequency"),
                    value(drug, "start"), value(drug, "end"), value(drug, "duration"), value(drug, "indication"),
                    value(drug, "special"), value(drug, "action")));
        }
        return result.build();
    }

    /** Shared physical grid: fourteen vertically merged labels and two horizontally merged groups. */
    private static Table.Builder drugHeaders(Table.Builder table, double height, boolean repeat) {
        var first = new TableCell[] {
            cell("序\n号", height, ParagraphAlignment.CENTER, 1, 2),
            cell("批\n准\n文\n号*", height, ParagraphAlignment.CENTER, 1, 2),
            cell("商\n品\n名", height, ParagraphAlignment.CENTER, 1, 2),
            cell("通\n用\n名\n称*", height, ParagraphAlignment.CENTER, 1, 2),
            cell("剂\n型*", height, ParagraphAlignment.CENTER, 1, 2),
            cell("规\n格", height, ParagraphAlignment.CENTER, 1, 2),
            cell("上市\n许可\n持有\n人/生\n产企\n业*", height, ParagraphAlignment.CENTER, 1, 2),
            cell("产\n品\n批\n号", height, ParagraphAlignment.CENTER, 1, 2),
            cell("失效\n日期\n/有\n效期\n至", height, ParagraphAlignment.CENTER, 1, 2),
            cell("用法用量", height, ParagraphAlignment.CENTER, 3, 1),
            cell("用药起止\n日期*", height, ParagraphAlignment.CENTER, 2, 1),
            cell("用\n药\n时\n间", height, ParagraphAlignment.CENTER, 1, 2),
            cell("治\n疗\n疾\n病*", height, ParagraphAlignment.CENTER, 1, 2),
            cell("是否\n存在\n以下\n情况\n可\n多选\n注1", height, ParagraphAlignment.CENTER, 1, 2),
            cell("对药品采取的措施注2", height, ParagraphAlignment.CENTER, 1, 2)
        };
        var second = cells(height, "给\n药\n途\n径", "单\n次\n剂\n量", "给\n药\n频\n次", "起", "止");
        if (repeat) { return table.headerCells(first).headerCells(second); }
        return table.row(first).row(second);
    }

    private static Table reactionTable(Map<?, ?> reaction) {
        String text = "怀疑药品-不良反应术语*：  " + value(reaction, "term") + "\n"
                + "发生时间*：" + value(reaction, "start") + "      结束时间：" + value(reaction, "end")
                + "      持续时间：" + value(reaction, "duration") + "\n"
                + "严重性*    严重□  非严重" + value(reaction, "nonSerious") + "\n"
                + "死亡□ 先天异常或出生缺陷□  危及生命□  功能丧失/残疾□  住院或延长住院时间□ 其他重要医学事件□\n"
                + "非预期*   是□   否□   不详" + value(reaction, "unexpected") + "\n"
                + "停药或减量后，反应是否消失或减轻*     " + value(reaction, "dechallenge") + "\n"
                + "再次使用可疑药品后是否再次出现同样反应*     " + value(reaction, "rechallenge") + "\n"
                + "结果*   " + value(reaction, "outcome") + "\n"
                + "初始报告人评价*   " + value(reaction, "reporterAssessment") + "\n"
                + "上市许可持有人评价*   " + value(reaction, "holderAssessment");
        return table("adr-reaction", 1).headerCells(cell("不良反应（可重复）", 14.1, ParagraphAlignment.LEFT))
                .row(cell(text, 19.58, ParagraphAlignment.LEFT)).build();
    }

    private static Table reporterTable(Map<?, ?> reporter, Map<?, ?> report) {
        return table("adr-reporter", 1)
                .row(cell("初始报告人姓名*：" + value(reporter, "name") + "    职业*：医生" + value(reporter, "doctor")
                        + "  药师□  护士□  其他医务人员□\n消费者及其他人员□\n所在单位：" + value(reporter, "institution")
                        + "    联系电话：" + value(reporter, "contact") + "    电子邮箱：未提供", 15.1, ParagraphAlignment.LEFT))
                .row(cell("事件发生国家 / 地区*：" + value(report, "country") + "    首次获知时间*：" + value(report, "firstReceived")
                        + "    企业病例编号：" + value(report, "caseId") + "\n最近一次获知时间（仅适用于跟踪报告）：" + value(report, "lastReceived")
                        + "\n上市许可持有人名称*：" + value(report, "holder") + "    联系人：" + value(report, "contact")
                        + "\n电话：" + value(report, "contactDetails") + "    地址：" + value(report, "address"), 17.6, ParagraphAlignment.LEFT))
                .build();
    }

    private static Paragraph paragraph(String id, String text, double size, double lineHeight, double after) {
        return paragraph(id, text, size, lineHeight, after, ParagraphAlignment.LEFT, InlineStyle.of(size));
    }

    private static Paragraph paragraph(String id, String text, double size, double lineHeight, double after,
            ParagraphAlignment alignment, InlineStyle style) {
        return Paragraph.mixed(id, List.of(new InlineText(text, style)), paragraphFormat(size, lineHeight, after, alignment));
    }

    private static ParagraphFormat paragraphFormat(double size, double lineHeight, double after, ParagraphAlignment alignment) {
        return ParagraphFormat.builder().fontSize(size).lineHeight(lineHeight).alignment(alignment).spaceAfter(after).build();
    }

    private static Table single(String id, String text, double lineHeight, double bottomPadding) {
        return table(id, 1).format(format(1, bottomPadding, false))
                .row(cell(text, lineHeight, ParagraphAlignment.LEFT)).build();
    }

    private static Table.Builder table(String id, double... weights) {
        return Table.builder(id).columns(Arrays.stream(weights).mapToObj(ColumnWidth.Fraction::new).toArray(ColumnWidth[]::new))
                .format(format(1, 1, false));
    }

    private static TableFormat format(double topPadding, double bottomPadding, boolean split) {
        return TableFormat.builder().padding(new PageMargins(topPadding, 5, bottomPadding, 5))
                .spaceBefore(0).spaceAfter(0).allowRowSplit(split).build();
    }

    private static TableCell[] cells(double lineHeight, String... values) {
        return cells(lineHeight, ParagraphAlignment.CENTER, values);
    }

    private static TableCell[] cells(double lineHeight, ParagraphAlignment alignment, String... values) {
        return Arrays.stream(values).map(text -> cell(text, lineHeight, alignment)).toArray(TableCell[]::new);
    }

    private static TableCell cell(String text, double lineHeight, ParagraphAlignment alignment) {
        return cell(text, lineHeight, alignment, 1, 1);
    }

    private static TableCell cell(String text, double lineHeight, ParagraphAlignment alignment, int columns, int rows) {
        return TableCell.builder().format(new CellFormat(10.5, lineHeight, alignment)).columnSpan(columns).rowSpan(rows)
                .content(List.of(new InlineText(text, InlineStyle.of(10.5)))).build();
    }

    private static Map<?, ?> group(Map<String, Object> data, String key) {
        if (data.get(key) instanceof Map<?, ?> value) { return value; }
        throw new IllegalArgumentException("Expected an object for " + key);
    }

    private static String value(Map<?, ?> group, String key) {
        if (group.get(key) instanceof String value) { return value; }
        throw new IllegalArgumentException("Expected text for " + key);
    }

    private static List<Map<?, ?>> rows(Map<String, Object> data, String key) {
        if (!(data.get(key) instanceof List<?> values)) { throw new IllegalArgumentException("Expected a list for " + key); }
        var result = new ArrayList<Map<?, ?>>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> row)) { throw new IllegalArgumentException("Expected an object row for " + key); }
            result.add(row);
        }
        return List.copyOf(result);
    }
}
