# 单向长表富文本 API

单向长表复用 `TableCell` 的纯文字富文本，逐行读取，默认整行分页，逐页交付；显式完整行续排见[拆行 API](streaming-row-split-api.md)。可在固定列中使用真实粗体／斜体、颜色、字号、基线偏移和装饰线，无须先构造完整 `Table`。决策见 [ADR-0036](adr/0036-streaming-rich-text.md)，本地证据见[阶段二十九验收](verification/phase-29-streaming-rich-text.md)。源所有权、原子输出和进度契约沿用[单向长表 API](streaming-table-api.md)。

## 定义样式与字体角色

字体由调用方显式加载为 `FontFace`；以下 `regularFace` 和 `boldFace` 分别是真实的常规体和粗体。引擎不会扫描系统字体，也不会合成粗体或斜体。

~~~java
var engine = DocumentEngine.builder(FontGroup.strict(List.of(regularFace)))
        .fontRole("strong", FontGroup.strict(List.of(boldFace)))
        .build();

var cellFormat = CellFormat.builder().fontSize(10).lineHeight(18).build();
var ordinary = InlineStyle.builder().fontSize(10).build();
var strong = InlineStyle.builder().fontRole("strong").fontSize(10)
        .rgb(0x224488).underline(true).build();

var stateCell = TableCell.builder().format(cellFormat)
        .content(List.of(new InlineText("状态：", ordinary),
                new InlineText("已完成", strong)))
        .build();
var row = new TableRow(List.of(new TableCell("A-001", cellFormat), stateCell));
~~~

`TableCell.rich(List<InlineText>, CellFormat)` 是等价的短入口。普通文字格和富文本格可出现在同一行；`text()` 与 `content()` 相互替换，后调用者生效。富文本运行的字号、RGB、偏移和装饰均为显式值，不逐属性继承 `CellFormat`。空字体角色使用引擎的默认 `FontGroup`；非空角色必须提前注册。未知角色会失败，诊断定位到该运行在格内的原始 UTF-16 起点。

`baselineShift` 使用 point，正值向下，负值向上。`CellFormat.lineHeight` 仍是固定行框，必须同时容纳所有运行的字号、字体度量及偏移。超出行框或新页正文高度时明确失败，不缩小字号或裁切内容。下划线、删除线跟随各运行的实际宽度和颜色。

## 链式导出

~~~java
var spec = StreamingTable.builder("orders")
        .pageSettings(PageSettings.A4)
        .columns(90, 300)
        .cellFormat(cellFormat)
        .header("编号", "状态")
        .padding(3)
        .build();

var summary = engine.export(spec, () -> openOrderRows(cellFormat, ordinary, strong))
        .backend(PdfBackend.builder()
                .options(options -> options.storage(PdfOptions.Storage.TEMP_FILE))
                .build())
        .limits(limits -> limits.maxCellCodePoints(2_000).maxRowCodePoints(4_000)
                .maxCellInlines(16).maxRowInlines(32))
        .diagnostics(this::recordWarning)
        .to(Path.of("orders.pdf"));
~~~

`openOrderRows` 由应用实现，返回新的 `TableRowSource`。每次 `next()` 构造当前行并返回 `Optional.of(row)`，结束时返回 `Optional.empty()`。不要在源中缓存全部记录。规格的 `cellFormat` 只影响随后添加的便捷表头；源行内的每个格保留自身格式。富文本表头使用 `.header(new TableRow(...))`，每页复用已测量的表头。

每行先检查全部格的结构、角色和预算，再塑形与换行。各格只换行一次，页高不足时移动已测量的整行；最多预读一个待放置行。源行、页和诊断同步交付，调用方回调自行保存的列表不属于引擎的有界驻留保证。高级入口新增带 `Map<String, FontGroup>` 的 `StreamingTableLayouter.layout` 重载，旧重载继续使用空角色映射。

## 运行预算与缺字策略

| 配置 | 默认值 | 计数范围 |
| --- | --- | --- |
| `maxCellInlines` | 4,096 | 一个源格的运行数，包含空运行 |
| `maxRowInlines` | 16,384 | 一个源行所有格的运行数，包含表头行和空运行 |
| `maxCellCodePoints`／`maxRowCodePoints` | 100,000／1,000,000 | 原始 Unicode 码点；拼接原文只计一次 |
| `maxDiagnostics` | 10,000 | 实际呈现的诊断，包含重复表头 |

没有显式运行列表的普通文字格按一个有效运行计入预算，即使文字为空。表头在源预算中只计一次；每次重复呈现的输出行和诊断继续计费。容量报告的 `sourceInlineRuns` 是样例记录的**显式存储运行数**：普通样例为 0，富文本样例为每个正文行 6 个加表头 3 个；它与普通格的有效运行预算计数不同。

每个字体角色保留自己的 `FontGroup` 策略。`FontGroup.strict(...)` 缺字即失败；`FontGroup.placeholder(..., '#')` 先尝试整组，再使用该组支持的占位符并返回 `MISSING_GLYPH_REPLACED`／`WARNING`。诊断保留原始码点、格内 UTF-16 范围及实际字体标识，region 为 `STREAMING_TABLE`。逻辑文字保留原文，不改为占位符。占位符本身缺字、非法 Unicode 和跨样式边界的不可拆字形簇仍失败。复杂文字复用共享塑形与换行，不建立另一套流式文字算法。

旧四参数 `StreamingTableLimits` 构造器继续可用，并填入默认运行预算。record 新增两个分量；依赖 record 反射形状或旧二进制的调用方需要重新编译并检查映射。建议使用 Builder。

## 支持边界

本入口接受普通格和仅含 `InlineText` 的富文本格，支持[固定列横向合并](streaming-table-api.md#固定列横向合并)。显式图片／被动外链见[图文 API](streaming-media-api.md)，正文完整行续排见[拆行 API](streaming-row-split-api.md)；PAGE 与准确声明总数的 PAGES 见[单向字段](streaming-fields-api.md)；块格、纵向合并和自动列宽仍明确拒绝。配置的页模板、导航和注释也会在打开源之前拒绝。未知总页数推断、目录、未来引用、脚注、分栏、DOCX 及自动签名尚未接入；需要这些能力时使用既有完整文档 API。

布局和 Display List 可逐页释放，PDFBox 仍保留累计页树和 COS 对象；`TEMP_FILE` 只改变流存储方式。有限容量实测必须分别报告布局丢弃页与真实 PDF，不代表完整 PDF 恒定内存、长期无泄漏或任意工作负载容量。

## 示例与复核

[StreamingRichTextExample](../examples/src/main/java/com/mirakyux/export4j/examples/StreamingRichTextExample.java)含自然换行、重复表头、真实字体变体、上下标、装饰及阿拉伯／天城文字和缺字警告。小样验证检查最终 PDF 字体、文字／格边界、SVG 来源、源关闭顺序和最多一行预读，再用 Poppler 与 PDFium 渲染全部页面。

~~~powershell
.\gradlew.bat :examples:streamingRichText
build/verification/python-phase13/Scripts/python.exe scripts/verify_streaming_rich_text.py

# 先由 :examples:writeStreamingTableClasspath 刷新运行路径，再在独立 JVM 采样。
.\scripts\measure_streaming_table.ps1 -SkipBuild -RowCounts 1000,10000
.\scripts\measure_streaming_table.ps1 -SkipBuild -RichText -RowCounts 1000,10000 `
        -IncludeSmallHeapControl -SmallHeapRows 10000
~~~

Python 使用项目锁定的[验证依赖](../scripts/requirements-verification.txt)，隔离调用方目录默认读取本次成功指针，也可显式传 `--consumer-build-directory`。采样默认仍为 1,000／10,000／100,000 行；小堆对照默认 100,000 行、64 MiB，以上命令显式限制为本批比较规模。采样器同时核对实际运行字体的 SHA-256、显式运行数、退出码和临时文件清理，不复用普通文字结果冒充富文本结果。

AES-256 与静态 PDF/A-2b 已接入逐页后端，见[单向安全输出](streaming-protection-api.md)；归档仍拒绝链接，签名在最终文件上显式处理。
