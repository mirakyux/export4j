// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import com.mirakyux.export4j.model.ImageResource;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Self-authored fictional business examples, shared by the UI and reproducible scenarios. */
final class DesignerSamples {
    private record Sample(String name, String description, String resource) {
        Sample(String name, String description) { this(name, description, ""); }
    }
    private static final List<Sample> SAMPLES = List.of(
            new Sample("巡检报告", "图文标题、数据列表与条件内容"),
            new Sample("密集静态表单", "检查项目、填写栏与表格"),
            new Sample("分页长表", "数据行绑定与跨页重复表头"),
            new Sample("目录与交叉引用", "自动目录、题注与页码引用"),
            new Sample("药品不良反应报告表", "三页密集表单、18 列合并表头、内容自然分页与页码", "adverse-reaction"),
            new Sample("服务合同", "Logo 与版本页眉、多章节条款、交付验收矩阵、SLA 与签署附件", "common/service-contract"),
            new Sample("个人简历", "照片与双栏概况、工作履历、成果项目与技能矩阵", "common/resume"),
            new Sample("用户手册", "安装运维、操作流程、权限参数与跨页排障清单", "common/user-manual"),
            new Sample("报价单", "分组长明细、含税计算、交付方案与商务条款", "common/quotation"),
            new Sample("会议纪要", "议题讨论、方案比较、决策与责任行动台账", "common/meeting-minutes"),
            new Sample("项目周报", "健康度、预算资源、工作流进度与风险变更台账", "common/weekly-report"),
            new Sample("双栏学术短文", "通栏标题与摘要、双栏正文、中间通栏图表与双栏续排", "flowing-two-column"),
            new Sample("三栏简报", "三栏资讯、中间通栏图表、三栏续排与数据循环", "flowing-three-column"),
            new Sample("十二轨栅格编辑", "6 + 6、8 + 4、三等分、偏移、通栏图表与前后正文分栏", "grid-editor"));
    static final List<String> NAMES = SAMPLES.stream().map(Sample::name).toList();
    private DesignerSamples() { }
    static List<Map<String, Object>> catalog() {
        var catalog = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < SAMPLES.size(); index++) {
            var sample = SAMPLES.get(index);
            catalog.add(BindingJson.object("index", index, "name", sample.name(), "description", sample.description()));
        }
        return List.copyOf(catalog);
    }
    static DesignerProject blank() throws IOException {
        return new DesignerProject(DesignerService.defaults("""
                <document version="1" title="未命名文档">
                  <page size="A4" margin="42"/>
                </document>
                """).build(), "{}");
    }
    private static String sampleResource(String name) throws IOException {
        return new String(sampleBytes(name), StandardCharsets.UTF_8);
    }
    private static byte[] sampleBytes(String name) throws IOException {
        try (var input = DesignerSamples.class.getResourceAsStream("/designer/samples/" + name)) {
            if (input == null) { throw new IOException("bundled designer sample missing: " + name); }
            return input.readAllBytes();
        }
    }
    static DesignerProject create(int index) throws IOException {
        if (index < 0 || index >= SAMPLES.size()) { throw new IllegalArgumentException("unknown designer sample"); }
        var sample = SAMPLES.get(index);
        if (!sample.resource().isEmpty()) {
            var source = sampleResource(sample.resource() + ".xml");
            var resources = sample.resource().startsWith("common/")
                    ? DesignerService.defaults(source, "LXGWWenKaiTC-Regular.ttf", "LXGWWenKaiTC-OFL.txt")
                    : DesignerService.defaults(source);
            String image = switch (sample.resource()) {
                case "common/resume" -> "resume_portrait";
                case "common/service-contract" -> "contract_logo";
                default -> "";
            };
            if (!image.isEmpty()) {
                resources.image(image, ImageResource.load(sampleBytes("common/assets/" + image + ".png")))
                        .license(image + "-license.txt", sampleResource("common/assets/" + image + "-license.txt"));
            }
            if (sample.resource().startsWith("flowing-") || sample.resource().equals("grid-editor")) {
                resources.image("flow_diagram", ImageResource.load(flowDiagram()))
                        .license("flow-diagram-license.txt", "Self-authored Export4j sample diagram. Apache-2.0.\n");
            }
            return new DesignerProject(resources.build(),
                    sampleResource(sample.resource() + ".json"));
        }
        String page = "<page width='595' height='842' margin='42'/>";
        String source; String data;
        switch (index) {
            case 0 -> {
                source = """
                        <document version="1" title="${title}" author="Export4j">
                        %s
                        <heading id="report-title" level="1" size="24" line-height="34" space-after="12">${title}</heading>
                        <p id="report-summary" size="11" line-height="19" space-after="12">${summary}</p>
                        <p id="report-logo" line-height="32"><image ref="mark" width="40" height="24" alt="蓝色巡检标记"/></p>
                        <heading id="report-items" level="2" size="15" line-height="24" space-before="12">本期事项 / Findings</heading>
                        <list items="items" as="item"><bullet>${item.name}：${item.result}</bullet></list>
                        <if test="approved"><then><p id="approved" color="#245E48">STATUS-APPROVED 已复核</p></then><else><p id="pending">STATUS-PENDING 待复核</p></else></if>
                        <p id="report-end" space-before="12">REPORT-END · 所有业务数据均为虚构样例。</p>
                        </document>
                        """.formatted(page);
                data = """
                        {"title":"季度巡检报告 / Inspection report","summary":"REPORT-START 本季度完成仓库、设备和流程巡检，以下为示例结果。",
                         "approved":true,"items":[{"name":"仓库","result":"记录完整"},{"name":"设备","result":"运行正常"},{"name":"流程","result":"按期复核"}]}
                        """;
            }
            case 1 -> {
                var rows = new StringBuilder();
                for (int n = 1; n <= 14; n++) {
                    rows.append("<row><cell>检查项 ").append(n).append("</cell><cell>[x] 符合  [ ] 待核</cell><cell>FORM-")
                            .append(String.format(java.util.Locale.ROOT, "%02d", n)).append(" · 责任人及处理结果</cell></row>");
                }
                source = "<document version='1' title='密集静态检查表'>" + page
                        + "<heading id='form-title' level='1' size='22' line-height='32'>密集静态检查表 / Checklist</heading>"
                        + "<p id='form-description' space-after='12'>FORM-START 静态填写示例；勾选标记是正文，不是交互表单字段。</p>"
                        + "<table id='checklist' padding-top='6' padding-bottom='6'><columns><column width='80'/><column width='145'/><column weight='1'/></columns>"
                        + "<header><cell>项目</cell><cell>检查结论</cell><cell>记录与说明</cell></header>" + rows
                        + "</table><p id='form-end' space-before='12'>FORM-END 审核人：________    日期：________</p></document>";
                data = "{}";
            }
            case 2 -> {
                source = "<document version='1' title='分页库存台账'>" + page
                        + "<heading id='ledger-title' level='1' size='22' line-height='32'>分页库存台账 / Inventory</heading>"
                        + "<p id='ledger-description'>LEDGER-START 表头随分页重复，正文每条记录只出现一次。</p>"
                        + "<rows id='inventory' items='records' as='record' row-split='true'><columns><column width='70'/><column weight='1'/><column width='70'/></columns>"
                        + "<header><cell>编号</cell><cell>名称与说明</cell><cell>数量</cell></header>"
                        + "<row><cell>${record.code}</cell><cell>${record.name}</cell><cell>${record.quantity}</cell></row></rows>"
                        + "<p id='ledger-end'>LEDGER-END 记录已结束。</p></document>";
                var records = new StringBuilder("{\"records\":[");
                for (int n = 1; n <= 120; n++) {
                    if (n != 1) { records.append(','); }
                    records.append("{\"code\":\"ROW-").append(String.format(java.util.Locale.ROOT, "%03d", n))
                            .append("\",\"name\":\"示例物料 — 库存记录\",\"quantity\":").append(n).append('}');
                }
                data = records.append("]}").toString();
            }
            case 3 -> {
                source = "<document version='1' title='引用与导航'>" + page
                        + "<navigation headings='true' max-level='2'><contents title='目录 / Contents'><typography part='ENTRY' size='11' line-height='20'/></contents></navigation>"
                        + "<heading id='reference-title' level='1' size='22' line-height='32'>引用与导航 / References</heading>"
                        + "<p id='reference-start'>REFERENCE-START 编辑标题后，页码引用按当前布局计算。</p>"
                        + "<cross-reference id='reference-page' kind='PAGE' target='appendix'>附录所在页 / Appendix page: </cross-reference>"
                        + "<caption id='figure-one' kind='Figure'>检查结果</caption>"
                        + "<cross-reference id='reference-number' kind='NUMBER' target='figure-one'>参见图 / See figure: </cross-reference>"
                        + "<page-break id='reference-break'/><heading id='appendix' level='1' size='20' line-height='30'>附录 / Appendix</heading>"
                        + "<p id='reference-end'>REFERENCE-END 引用目标保留。</p></document>";
                data = "{}";
            }
            default -> throw new IllegalArgumentException("unknown designer sample");
        }
        var picture = new BufferedImage(40, 24, BufferedImage.TYPE_INT_RGB); var graphics = picture.createGraphics();
        try { graphics.setColor(new Color(0x1C539A)); graphics.fillRect(0, 0, 40, 24); graphics.setColor(new Color(0x8AB9EA)); graphics.fillRect(8, 7, 24, 3); }
        finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(picture, "png", bytes); picture.flush();
        return new DesignerProject(DesignerService.defaults(source).image("mark", ImageResource.load(bytes.toByteArray())).build(), data);
    }
    private static byte[] flowDiagram() throws IOException {
        var picture = new BufferedImage(640, 160, BufferedImage.TYPE_INT_RGB); var graphics = picture.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 640, 160);
            graphics.setColor(new Color(0xDCE7F8));
            for (int column = 0; column < 3; column++) { graphics.fillRect(16 + column * 208, 12, 192, 42); graphics.fillRect(16 + column * 208, 106, 192, 42); }
            graphics.setColor(new Color(0x315A8A));
            for (int column = 0; column < 3; column++) {
                for (int line = 0; line < 3; line++) { graphics.fillRect(26 + column * 208, 20 + line * 10, 164, 3); graphics.fillRect(26 + column * 208, 114 + line * 10, 164, 3); }
            }
            graphics.setColor(new Color(0xC68A45)); graphics.fillRect(16, 66, 608, 28);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(picture, "png", bytes); picture.flush(); return bytes.toByteArray();
    }
}
