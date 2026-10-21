# 长合并格的语义内容续排

GridTable 可以在长合并格内续排 SemanticTable，保留富文本、行内图片、外链、最终页码／总页数／引用和来源。网格表头中的小语义表也可合法重复，字段按每次呈现的实际页面解析。字体由调用方显式提供，尺寸使用 point，坐标原点在左上角。

## 编写

~~~java
var detail = Table.builder("detail")
        .cellFormat(new CellFormat(10, 20, ParagraphAlignment.LEFT))
        .format(format -> format.padding(3).allowRowSplit(true))
        .row(TableCell.mixed(List.of(
                new InlineText(longDescription, InlineStyle.of(10)),
                new InlineText("\n所在页：", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(10))),
                new CellFormat(10, 20, ParagraphAlignment.LEFT)))
        .build();
var grid = GridTable.builder().columns(2, 1)
        .headerRows(1).allowRowSplit(true)
        .defaults(cell -> cell.padding(4))
        .row(row -> row.cells("详情", "状态"))
        .row(row -> row.semanticTable(detail, cell -> cell.rowSpan(2))
                .cell("已检查"))
        .row(row -> row.cell("已确认"))
        .build();
var document = Documents.builder().component(grid).build();
engine.write(document, new PdfBackend(), Path.of("report.pdf"));
~~~

`longDescription`、字体和 engine 由调用方提供。完整 imports、字体配置、虚构数据及两栏组合见 [GridSemanticFlowExample](../examples/src/main/java/com/mirakyux/export4j/examples/GridSemanticFlowExample.java)。复合内容可以用 `row.cell(FlowColumn.builder().padding(2).semanticTable(detail).text("后续说明").build(), cell -> cell.rowSpan(2))`，保留嵌套列内边距和后续内容。

| 设置 | 控制范围 |
| --- | --- |
| `GridTable.allowRowSplit(true)` | 外层网格按区域分配合并格，默认仍整组移动 |
| `TableFormat.allowRowSplit(true)` | 内层语义表的超长行按完整文字行续排，默认整行移动 |
| `row.semanticTable(table, callback)` | 在格内使用标准语义组件；回调配置跨度、内边距及外观 |
| `grid.headerRows(n)` | 重复完整缓存表头，包括其中小语义表的逐页字段 |

内语义表可以有自己的重复表头。它必须与最小正文片段一起放入当前区域；短初始区放不下可跳过，完整续区不能推进则失败。重复两层表头和各层内边距都会占用实际空间。

## 测量与来源

每个来源固定宽度测量一次，游标根据格的实际剩余高度取片段。完整行框、真实字体变体、缺字策略和图片链接矩形保持。字段在目录偏移、分节编号及总页数确定后解析，不改变行宽或分页。字段预留宽度不足仍失败。

`ComponentFragment.layout().semanticPlacements()` 保存原 Table 和 TableFragment。`CellFragment.firstLineIndex()`、行列及原文 UTF-16 范围均来自内层 Table；外层 GridTable 的合并位置是另一层几何。缺字诊断继续报告内层表 ID、实际物理页、源行列和原文范围。

网格局部验证准备来源和实际呈现，再生成完整连续的 `fragmentIndex/fragmentCount`。外层缓存表头重复也属于这个已验证序列。独立地向两个格、两个网格或同一列插入同一来源仍失败；需要另一张内容相同的表时，创建独立 Table 并给出不同 ID。全局 ID 不能与其他文档节点冲突。

核心自定义组合可使用 `new DocumentLayouter().prepareTable(table, context)`，获得 `TableCursor`：`remainingHeight()`、`done()`、`take(height)`。高度必须有限、非负且不超过准备视口；空 Optional 不消费内容和输出预算。游标可变、非线程安全，只用于一个同步布局调用；字体及图片资源仍由调用方持有。调用方必须正确保存语义来源、片段顺序和最终完整序号，不能把未解析字段转成普通绘制命令。

## 限制与验证

只为标准 SemanticTable、FlowColumn 及字体／样式作用域增加格内语义续排。自定义组件、嵌套 GridTable 和固定文字仍按原子组件处理；固定 `layout()` 仍要求一个片段。内语义表已有[列合并](column-spans-api.md)与[跨行合并](row-spans-api.md)，纵向连通组整体消费，内／外拆行开关保持独立；DOCX 嵌套仍未映射。页区语义、旋转、缩放和部分裁切沿用拒绝边界。

源码结构、原文和图片按来源计一次，输出行、重复表头、字段和链接按实际呈现计入。单表默认最多 1,000 个游标片段；网格另限 100,000 个语义准备片段和实际呈现，保留原工作预算。全局限额随后复核；所有模型与 IR 仍驻留内存，不构成流式输出。

运行 `:examples:gridSemanticFlow` 和 `scripts/verify_grid_semantic_flow.py`，独立核对全部最终 PDF 页、SVG 顺序、字段、链接、图片、来源及调用方输出。设计见 [ADR-0024](adr/0024-grid-semantic-cell-flow.md)，证据见[阶段二十](verification/phase-20-grid-semantic-flow.md)。
