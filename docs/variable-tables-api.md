# 语义表格变宽区域 API

`SemanticTable` 实现 `WidthAwareRegionFlow`，支持在连续不同宽度的栏内重新解析列宽和换行。默认完整行和跨行合并的传递连通组保持原子；每个实际呈现的片段保留同一 Table 来源、原始行／物理列、UTF-16、字段、图片和外链。

```java
var table = Table.builder("details")
        .cellFormat(CellFormat.builder().fontSize(10).lineHeight(18).build())
        .header("ITEM", "DETAIL")
        .row("A", "Content wraps at the actual column width.")
        .row("B", "The original row and cell sources remain intact.")
        .build();
var content = FlowColumn.builder().padding(2).semanticTable(table).build();
var columns = ContinuousColumns.builder(content)
        .columnWeights(List.of(1.0, 2.0)).gap(12).build();
var document = Documents.builder().component("details-host", columns).build();
engine.export(document).backend(new PdfBackend()).to(Path.of("details.pdf"));
```

此处所有几何尺寸为 pt。`FlowColumn` 在每个区域扣除自己的左右内边距，并按当前区域旋转子宽度序列；字体与样式作用域透传宽度。`balanced(true)` 仍执行最多 24 次有界公共栏高试排，最终保留实际验证过的布局。

## 核心入口

`DocumentLayouter.layoutTableRegions(table, context, regions, widths)` 返回不可变 `TableRegion` 序列。宽度列表在入口快照，数量为 1～16，每项正有限且不超出上下文宽度，按逻辑区域索引循环；跳过的短区仍占宽度序号。续区高度不得大于上下文高度。

等宽序列复用原有一次测量游标。不同宽度默认逐区安排完整行／合并行组；显式 `allowRowSplit=true` 时，行内格、纵向 owner 及有界 Paragraph／Table 块格可按完整行跨宽度续排，详见[拆行指南](variable-table-continuation-api.md)。初始短区无法容纳表头和最小正文时可跳过，完整续区仍无法推进则报 `CONTENT_TOO_TALL`。固定列超宽、图片或字段实际行宽不足继续明确失败，不缩放、不裁切，也不悄悄移去另一宽度。

支持既有固定／比例列；普通行内格支持有界 Auto 和双跨度。默认原子 Paragraph／Table 块格在每种实际宽度下重新布局；显式拆行时，各层分别按自己的开关准备递归游标，保留叶原文、已接受行和 owner 来源。嵌套宽度逐层扣内边距，语义表深最多 16 层；含块格表自身的 Auto、块格重复表头和格内分页邻接策略仍拒绝，行内内表 Auto 见[内表指南](nested-automatic-tables-api.md)。

组件 `GridTable` 也实现变宽 SPI，标准文字、OpenType、静态表单、语义表格及混合列按实际格宽准备；默认原子合并组与显式完整行续排保持独立，固定组件不会自动获得可拆分能力，见[网格续排](grid-continuation-api.md#不同宽度的连续栏)。这些扩展不改变单向长表源、DOCX 或任意自定义分页组件的契约。

## 来源、预算和资源

- 源结构、原文及图片预算检查一次；完整源身份和连续片段序号由最终文档再次验证。
- 原子路径每个不同宽度最多准备一次完整表格，最多 16 个候选；拆行路径缓存列／表头，再按完整格源准备实际行。累计准备工作不得超过 4,000,000 个源 UTF-16 单位，每个候选另受配置的行预算限制；未选候选不会合并诊断。
- 最终行预算只计已选择正文和实际重复表头。占用区域受 `maxPages` 及 100,000 上限控制；逻辑区域上限 100,016。
- 页码、总页数和引用留到最终物理分页解析，重复表头按每次实际呈现取值。图文外链取最终实际矩形。
- 测量不执行 I/O，不拥有字体／图片资源，不启动线程；观察现有执行范围的取消／超时，调用间没有共享可变表格状态。

原子变宽表格设计及初始证据见 [ADR-0078](adr/0078-variable-width-semantic-tables.md)和[阶段七十一](verification/phase-71-variable-tables.md)。纵向 owner 与递归／网格续排分别见[阶段九十八](verification/phase-98-layout-text-streaming-and-graphics.md)和[阶段九十九](verification/phase-99-layout-fonts-streaming-graphics-closure.md)，不将历史阶段的限制当作当前未实现项。
