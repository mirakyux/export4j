# 变宽区域内的表格拆行续排

语义表格显式设置 `allowRowSplit(true)` 后，普通文字、富文本、行内图片及字段可以按完整行在不同宽度的栏之间续排。每个格保持完整原文，按实际所属区域的列宽换行，不把宽栏的行直接搬到窄栏，也不裁切或缩放内容。

```java
var table = Table.builder("details")
        .cellFormat(CellFormat.builder().fontSize(10).lineHeight(18).build())
        .format(format -> format.padding(3).allowRowSplit(true))
        .header("DETAIL")
        .row("Content that may continue from a narrow column into a wider column.")
        .build();
var flow = FlowColumn.builder().padding(2).semanticTable(table).build();
var columns = ContinuousColumns.builder(flow)
        .columnWeights(List.of(1.0, 2.0)).gap(12).build();
```

使用方式及输出沿用[变宽语义表格 API](variable-tables-api.md)。所有长度为 pt，原文位置为格内 UTF-16；逻辑区域序号、物理页码和来源行号保持独立。

## 内容与推进

- 支持固定／比例／有界 Auto 列和横向合并；列宽在实际区域重新解析。各格可使用不同固定行高，每片段重复格内边距，按最高格片段确定行高。
- 已完成的邻格在后续片段保持空续接格，不重复原文字。`firstLineIndex` 是该格实际续排行的累计偏移，`continuedRow` 表示同一原始行的后续片段。
- 图片、字段和字形簇不可拆。图片按实际行宽检查；未在窄栏呈现的图片不会因窄栏的完整正文预排而被拒绝。Auto 首选宽度探测仍遵循自身的有界测量契约。
- 表头在每个实际占用区域完整重复，按其实际宽度换行。正文必须至少放下每格一个行框；过短初始区域可在起排或同一行续排途中跳过，所有格共同跳过，不消费正文或生成仅含表头的片段，也不计入占用区域预算。完整续区无法推进时返回 `CONTENT_TOO_TALL`。
- PAGE、PAGES 和引用保持延迟解析，由最终文档物理分页确定。图片及文字链接使用最终格位置；缺字诊断保留原始 UTF-16，未选宽度的准备诊断不混入结果。
- 支持 `FlowColumn` 内边距、字体作用域及连续栏的有界平衡。重复测量必须使用同一不可变来源，不共享文档级游标状态。

## 预算与失败

源结构、码点和槽预算检查一次。每种宽度的列／表头准备最多一次，最多 16 种；每个正文格再按完整源准备实际行。候选与实际准备累计计入 4,000,000 源 UTF-16 单位的工作上限。最终输出行另计，仅包含已选正文、空续接行和实际重复表头；区域仍受 `maxPages`、100,000 占用片段及 100,016 逻辑区域上限约束。

取消、预算、非法内容及几何错误保持明确异常，不改成文字缺字错误，不返回部分成功。布局不执行 I/O，不拥有字体或图片资源，不启动线程。输出继续遵守引擎原子写入契约。

## 保留边界

不同宽度下，允许拆行的行内格现也支持纵向 owner：每个真实锚点按其实际区域容量从完整原文准备一次，跨栏按完整行续接；覆盖行不重新塑形或复制已消费内容。保留原始 owner 行／物理列、UTF-16、行号、图片、链接及最终字段。所有相邻格共同跳过短区，同一区域中已完成 owner 的空覆盖与此前矩形合并，不绘制内部横线；全覆盖行不生成虚假正文或空白片段。行内格的横纵跨度可组合。

Paragraph／Table 块格现也支持递归变宽续排。每个叶段落保留完整原文和已接受行的实际宽度，仅为剩余行选择当前区域宽度；递归游标保留原始块序号、行号和纵向 owner。字段、图片、链接和诊断仍归属原始叶来源。短区的尝试只在成功后推进游标及输出计数。

`DocumentLayouter.prepareVariableTable(table, context)` 返回 `VariableTableCursor`：先以 `width(actualWidth)` 选择本次宽度，再读取 `remainingHeight()` 或调用 `take(height)`；空结果不消费来源。游标是非线程安全的布局局部状态，不拥有资源。重复宽度变化的完整源测量累计最多 4,000,000 UTF-16 单位，实际输出行另计。

组件 `GridTable` 也实现 `WidthAwareRegionFlow`，支持其标准文字、OpenType、表单文字、语义表格和混合列按实际格宽续排；详见[网格续页](grid-continuation-api.md)。默认原子行／合并行组路径及等宽缓存路径保持独立；本项不自动扩大单向长表，也不承诺 DOCX 与 PDF 同分页。设计见 [ADR-0099](adr/0099-variable-recursive-grid-and-contour-flow.md)。

运行 `:examples:variableSplitTables` 及新隔离调用方，再执行 `python scripts/verify_variable_split_tables.py`。设计见 [ADR-0082](adr/0082-variable-width-inline-continuation.md)。

纵向 owner 的额外验收运行 `python scripts/verify_variable_vertical_spans.py --consumer-build-directory <成功调用方目录>`；检查非平衡／平衡栏、完整来源、全覆盖行、最终字段及图片／外链，不用缩放旧宽度结果代替重新排布。

本单元的构建、独立输出、调用方及容量覆盖见[阶段九十八](verification/phase-98-layout-text-streaming-and-graphics.md)。
