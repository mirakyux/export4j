// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FormText;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.GridTable;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;

/**
 * Capability/authoring-cost probe for a dense three-page ADR form. All case values are fictional.
 * Uses fluent document, flow, table, public static form controls and export APIs.
 * Dimensions are points with local top-left coordinates. Components are immutable and reusable.
 */
public final class AdrFormExample {
    private static final Path OUTPUT = Path.of("examples/build/adr-form");
    private static final PageSettings PAGE = PageSettings.builder().size(new PageSize(595.3, 841.9))
            .margins(42.5, 30, 28.5, 28).build();
    private static final RgbColor GRAY = new RgbColor(217.0 / 255, 217.0 / 255, 217.0 / 255);
    private static final ParagraphStyle BODY = ParagraphStyle.builder().fontSize(10.5).lineHeight(16).build();
    private static final ParagraphStyle SMALL = ParagraphStyle.builder().fontSize(9).lineHeight(14).build();
    private static final String NOTE1 = "注1：1-假药  2-用药过量  3-父源暴露  4-使用了超出有效期的药品  "
            + "5-检测并合格的批号  6-检测并不合格的批号  7-";
    private static final String NOTE1_CONTINUED = "用药错误  8-误用  9-滥用  10-职业暴露  11-超说明书使用";
    private static final String NOTE2 = "注2：1-停止用药  2-减少剂量  3-增加剂量  4-剂量不变  0-不详  9-不适用";
    private static final String NARRATIVE = "本页为虚构的排版能力验证数据，不用于医疗判断。"
            + "示例患者服药后报告皮肤瘙痒，停药后症状好转；相关信息记录于本表。";

    private AdrFormExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LXGWWenKaiTC-Regular.ttf"))
                .font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var template = PageTemplate.builder().footer(footer -> footer.height(16).gap(6)
                .content(PageNumberComponent.builder("- {page} -")
                        .style(ParagraphStyle.builder().fontSize(12).lineHeight(16).alignment(TextAlignment.CENTER).build()).build())).build();
        var engine = DocumentEngine.builder(fonts).pageTemplate(template).build();
        var layout = engine.export(document(NARRATIVE)).backend(PdfBackend.builder().build()).to(OUTPUT.resolve("adr-form.pdf"));
        if (layout.display().pages().size() != 3 || layout.diagnostics().stream().anyMatch(
                d -> d.severity() == TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("compact form: pages=" + layout.display().pages().size()
                    + ", diagnostics=" + layout.diagnostics().size());
        }
        // Longer content grows/moves rows as units; merged cells still cannot split internally.
        var expanded = engine.export(document(NARRATIVE.repeat(12))).backend(new PdfBackend()).to(OUTPUT.resolve("adr-form-expanded.pdf"));
        if (expanded.display().pages().size() <= 3 || expanded.diagnostics().stream().anyMatch(
                d -> d.severity() == TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("long form must paginate without lost diagnostics");
        }
        // A merged component cell cannot continue its own text across pages. Require explicit failure.
        try {
            engine.layout(document(NARRATIVE.repeat(100)));
            throw new IllegalStateException("oversized merged component cell unexpectedly accepted");
        } catch (DocumentLayoutException exception) {
            if (exception.code() != DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED) { throw exception; }
            Files.writeString(OUTPUT.resolve("oversized-cell.txt"), exception.code().name());
        }
        var header = medication(false);
        var spansFirst = new GridTable(header.rowCount(), header.columnWeights(), header.headerRows(),
                header.cells().stream().sorted(Comparator.comparingInt(GridTable.Cell::rowSpan).reversed()).toList(), header.minimumRowHeights());
        var context = new ComponentContext(537.3, 748.9, fonts, 0, 0, 1);
        Files.writeString(OUTPUT.resolve("results.txt"), "compactPages=" + layout.display().pages().size()
                + "\nexpandedPages=" + expanded.display().pages().size() + "\nwarnings=0\n"
                + "headerHeight=" + header.layout(context).height() + "\nspansFirstHeight=" + spansFirst.layout(context).height() + "\n");
        System.out.println("ADR form capability fixtures: " + OUTPUT.toAbsolutePath());
    }

    private static Document document(String narrative) {
        return Documents.builder().title("ADR form capability probe - fictional data").author("Export4j").pageSettings(PAGE)
                .component("patient-and-drug", firstPage())
                .pageBreak()
                .component("reaction-and-reporter", secondPage(narrative))
                .pageBreak()
                .grid(table -> table.columns(60, 477.3)
                        .defaults(cell -> cell.textStyle(BODY).padding(new PageMargins(2, 4, 2, 4)))
                        .row(row -> row.minHeight(40).cells("备    注", "其他需说明的情况：\n本表全部病例数据均为虚构，仅用于测试文档引擎。")))
                .build();
    }

    private static Component firstPage() {
        return FlowColumn.builder()
                .space(16)
                .fixedText("上市许可持有人药品不良反应报告表", text -> text.minHeight(27).style(style -> style.fontSize(18).lineHeight(27).alignment(TextAlignment.CENTER)))
                .space(6)
                .formText("严重报告□          境外报告□          首次报告☑          跟踪报告□          病例编号*", text -> text.style(BODY))
                .space(2)
                .fixedText("DEMO-2025-000001（能力验证样例；虚构数据）", text -> text.style(BODY).minHeight(16))
                .formText("报告来源*  医疗机构□  经营企业□  个人□  文献□  研究□  项目□  其他□  监管机构☑", text -> text.style(BODY))
                .space(9)
                .add(formTable().defaults(cell -> cell.background(GRAY))
                        .row(row -> row.minHeight(22).cells("患者信息")).build())
                .add(formTable().columns(52.0, 46.0, 71.0, 49.0, 48.0, 64.0, 57.0, 65.0, 85.3)
                        .row(row -> row.minHeight(31).cells("姓名*", "性别*", "出生日期*", "年龄", "国籍", "民族/种族", "身高\n(cm)", "体重\n(kg)", "联系电话")).build())
                .add(formTable().columns(52.0, 46.0, 71.0, 49.0, 48.0, 64.0, 57.0, 65.0, 85.3)
                        .row(row -> row.minHeight(32).cells("示例患者", "男", "1950-06-27", "75岁", "中国", "汉族", "170", "70", "未提供")).build())
                .add(identity())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(81).cells("相关重要信息：\n吸烟    有□          无□          不详☑\n"
                        + "饮酒    有□          无□          不详☑\n过敏史  有□          无□          不详☑\n"
                        + "其他（如肝病史、肾病史、家族史）    有□          无□          不详☑")).build())
                .add(formTable().defaults(cell -> cell.background(GRAY))
                        .row(row -> row.minHeight(18).cells("相关疾病信息（可重复）")).build())
                .add(formTable().columns(46.0, 65.0, 95.0, 139.0, 192.3)
                        .row(row -> row.minHeight(16).cells("序号", "疾病名称", "开始日期", "结束日期", "报告当时疾病是否仍存在")).build())
                .add(formTable().columns(46.0, 65.0, 95.0, 139.0, 192.3)
                        .row(row -> row.minHeight(48).cells("1", "示例疾病\n(10000000)", "", "", "是□  否□  不详☑")).build())
                .add(formTable().defaults(cell -> cell.background(GRAY))
                        .row(row -> row.minHeight(18).cells("怀疑用药（可重复）")).build())
                .add(medication(true))
                .add(formTable().defaults(cell -> cell.textFactory(text -> FormText.builder(text).style(SMALL).build()))
                        .row(row -> row.minHeight(28).cells(NOTE1)).build())
                .build();
    }

    private static Component secondPage(String narrative) {
        return FlowColumn.builder()
                .add(formTable().defaults(cell -> cell.textFactory(text -> FormText.builder(text).style(SMALL).build()))
                        .row(row -> row.minHeight(29).cells(NOTE1_CONTINUED)).build())
                .add(formTable().defaults(cell -> cell.textFactory(text -> FormText.builder(text).style(SMALL).build()))
                        .row(row -> row.minHeight(18).cells(NOTE2)).build())
                .add(formTable().defaults(cell -> cell.background(GRAY))
                        .row(row -> row.minHeight(17).cells("合并用药（可重复）")).build())
                .add(medication(false))
                .add(formTable().defaults(cell -> cell.textFactory(text -> FormText.builder(text).style(SMALL).build()))
                        .row(row -> row.minHeight(32).cells(NOTE1 + "\n" + NOTE1_CONTINUED)).build())
                .add(formTable().defaults(cell -> cell.textFactory(text -> FormText.builder(text).style(SMALL).build()))
                        .row(row -> row.minHeight(18).cells(NOTE2)).build())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(32).cells("相关器械：    暂无")).build())
                .add(formTable().defaults(cell -> cell.background(GRAY))
                        .row(row -> row.minHeight(16).cells("不良反应（可重复）")).build())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(196).cells("怀疑药品—不良反应术语*：    示例口服胶囊—瘙痒\n"
                        + "发生时间*：2025年12月19日    结束时间：    年    月    日    持续时间：    天\n"
                        + "严重性*    严重□  非严重☑\n"
                        + "死亡□  先天异常或出生缺陷□  危及生命□  功能丧失/致残□  住院或延长住院□  其他□\n"
                        + "非预期*    是□  否☑\n"
                        + "停药或减量后，反应是否消失或减轻*          是☑  否□  不详□  不适用□\n"
                        + "再次使用可疑药品后是否再次出现同样反应*    是□  否□  不详□  不适用☑\n"
                        + "结果*    治愈□  好转☑  未好转□  有后遗症□  死亡□  不详□\n"
                        + "初始报告人评价*      肯定□  很可能☑  可能□  可能无关□  无法评价□\n"
                        + "上市许可持有人评价*  肯定□  很可能☑  可能□  可能无关□  无法评价□")).build())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(80).cells("不良反应过程描述*（包括发生场所、症状、体征、临床检验等）及处理情况（可附页）：\n" + narrative)).build())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(38).cells("死亡时间：    年    月    日    直接死因：\n是否尸检：是□  否□  不详□    尸检结果：")).build())
                .add(formTable().defaults(cell -> cell.background(GRAY))
                        .row(row -> row.minHeight(20).cells("相关实验室检查信息（可重复）")).build())
                .add(formTable().columns(75.0, 73.0, 86.0, 139.0, 164.3)
                        .row(row -> row.minHeight(18).cells("序号", "检查项目", "检查日期", "结果（单位）", "正常值范围（低值-高值）")).build())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(48).cells("初始报告人姓名*：示例报告人    职业*：医生☑  药师□  护士□  其他医务人员□\n"
                        + "消费者□  其他人员□\n所在单位：示例医疗机构    联系电话：未提供    电子邮箱：未提供")).build())
                .add(formTable().columns(1.0)
                        .row(row -> row.minHeight(72).cells("事件发生国家/地区*：中国    首次获知时间*：2025-12-28    企业病例编码*：DEMO-001\n"
                        + "最近一次获知时间*（仅适用于跟踪报告）：\n"
                        + "上市许可持有人名称*：示例制药有限公司    联系人*：示例联系人    电话*：未提供\n"
                        + "地址*：示例地址（虚构，非真实机构或个人信息）")).build())
                .build();
    }

    private static GridTable identity() {
        return formTable().columns(217, 320.3)
                .row(row -> row.minHeight(20)
                        .cell("医疗机构/经营企业名称：示例医疗机构")
                        .cell("既往药品不良反应及药物过敏史\n有□          无□          不详☑", cell -> cell.rowSpan(2)))
                .row(row -> row.minHeight(20).cell("病历号/门诊号：DEMO-H00001"))
                .build();
    }

    private static GridTable medication(boolean populated) {
        var style = ParagraphStyle.builder().fontSize(10).lineHeight(15).alignment(TextAlignment.CENTER).build();
        var table = GridTable.builder()
                .columns(26, 27, 29, 28, 28, 29, 39, 24, 33, 27, 28, 29, 27, 27, 29, 29, 42, 36)
                .defaults(cell -> cell.textStyle(style).padding(new PageMargins(2, 5, 2, 5))
                        .verticalAlignment(FlowColumn.Alignment.CENTER))
                .row(row -> row.minHeight(32)
                        .cell("序\n号", cell -> cell.rowSpan(2))
                        .cell("批\n准\n文\n号*", cell -> cell.rowSpan(2))
                        .cell("商\n品\n名", cell -> cell.rowSpan(2))
                        .cell("通\n用\n名\n称*", cell -> cell.rowSpan(2))
                        .cell("剂\n型*", cell -> cell.rowSpan(2))
                        .cell("规\n格", cell -> cell.rowSpan(2))
                        .cell("上市\n许可\n持有\n人/生\n产企\n业*", cell -> cell.rowSpan(2))
                        .cell("产\n品\n批\n号", cell -> cell.rowSpan(2))
                        .cell("失效\n日期\n/有效\n期至", cell -> cell.rowSpan(2))
                        .cell("用法用量", cell -> cell.columnSpan(3))
                        .cell("用药起止\n日期*", cell -> cell.columnSpan(2))
                        .cell("用\n药\n时\n间", cell -> cell.rowSpan(2))
                        .cell("治\n疗\n疾\n病*", cell -> cell.rowSpan(2))
                        .cell("是否\n存在\n以下\n情况\n(可多\n选)注1", cell -> cell.rowSpan(2))
                        .cell("对药\n品采\n取的\n措施\n注2", cell -> cell.rowSpan(2)))
                .row(row -> row.minHeight(62).cells("给\n药\n途\n径", "单\n次\n剂\n量", "给\n药\n频\n次", "起", "止"));
        if (populated) {
            table.headerRows(2).row(row -> row.minHeight(188).cells("1", "国药准字DEMO0001", "", "示例口服胶囊",
                    "胶囊", "每粒装0.44g", "示例制药有限公司", "DEMO2501", "", "口服", "0.88g", "每日3次",
                    "2025-12-17", "2025-12-19", "3.0天", "【示例疾病(10000000)】", "", "1"));
        }
        return table.build();
    }

    /** Named defaults for this form; rows/cells remain visible at each call site. */
    private static GridTable.Builder formTable() {
        return GridTable.builder().defaults(cell -> cell.padding(new PageMargins(2, 4, 2, 4))
                .textFactory(text -> FormText.builder(text).style(BODY).build()));
    }
}
