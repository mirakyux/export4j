// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;

/** Compiles a self-authored XML template with explicit data and resources into one semantic report.
 * All report data are fictional. Files are written only inside the project build directory.
 */
public final class XmlTemplateExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-templates");
    private XmlTemplateExample() { }

    /** Generates PDF, DOCX and SVG from the same rendered semantic document. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var parser = XmlTemplate.builder()
                .pageSettings(PageSettings.builder().size(420, 480).margins(28).build())
                .bodyFormat(ParagraphFormat.builder().fontSize(11).lineHeight(18).build())
                .cellFormat(new CellFormat(11, 18, ParagraphAlignment.LEFT))
                .image("reviewMark", icon())
                .bindingLimits(new DocumentTemplate.Limits(2_000, 100, 100, 20_000, 16)).build();
        DocumentTemplate template;
        try (var input = Objects.requireNonNull(XmlTemplateExample.class.getResourceAsStream("/templates/report.xml"),
                "report template resource"); var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            template = parser.compile(reader);
        }
        var report = template.render(data());
        var fonts = FontGroup.builder().font(AuthoringExample.face("LXGWWenKaiTC-Regular.ttf"))
                .font(AuthoringExample.face("LiberationSans-Regular.ttf")).build();
        var layout = DocumentEngine.builder(fonts).build().export(report).backend(new PdfBackend()).to(OUTPUT.resolve("report.pdf"));
        if (!layout.diagnostics().isEmpty()) { throw new IOException("template report produced unexpected text diagnostics"); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.docx"))) { new DocxAdapter().write(report, output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("report.svg"))) { new SvgBackend().write(layout.display(), output); }
        System.out.println("XML template report: " + layout.display().pages().size() + " PDF pages, " + report.blocks().size() + " semantic blocks");
    }

    private static Map<String, Object> data() {
        var records = new ArrayList<Map<String, Object>>();
        var areas = List.of("东门仓", "西侧库", "南楼层", "北通道", "中央厅");
        var observations = List.of("现场标识已核对。", "通行路线保持畅通。", "设备外观未见明显损坏。", "台账与现场编号一致。",
                "发现一处标签需更新。", "责任人已确认处理日期。", "复查将在下月首周完成。", "原始记录已归档备查。", "后续按计划持续跟踪。");
        for (int index = 1; index <= areas.size(); index++) {
            final int number = index;
            String notes = IntStream.rangeClosed(1, 9).mapToObj(line -> String.format(Locale.ROOT,
                    "OBS%02d-%02d %s%s", number, line, line == 1 ? areas.get(number - 1) + "：" : "", observations.get(line - 1)))
                    .collect(Collectors.joining("\n"));
            records.add(Map.of("code", String.format(Locale.ROOT, "QH-%02d", number), "notes", notes));
        }
        return Map.of("report", Map.of("title", "青禾观察站月度巡检报告", "author", "青禾观察站（虚构）",
                        "period", "2026年9月", "unit", "青禾观察站（虚构）", "summary", "本期完成五个区域巡检，记录与处理建议如下。"),
                "needsFollowUp", true,
                "actions", List.of(Map.of("text", "重点一：对照记录核查现场标识。"), Map.of("text", "重点二：由责任人跟踪复查安排。")),
                "records", records);
    }

    private static ImageResource icon() throws IOException {
        var pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) { pixels.setRGB(x, y, x < 4 ? 0x285080 : 0x64a0c8); }
        }
        var encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", encoded)) { throw new IOException("PNG encoder is unavailable"); }
        return ImageResource.load(encoded.toByteArray());
    }
}
