# 嵌套文字与合并格续页

普通列文字现在按完整行续页。合并表格通过显式 `allowRowSplit(true)` 开启格内续页；默认仍保持合并连通行组完整。长度均为 point，原点在左上角。

## 编写方式

~~~java
var document = Documents.builder()
        .title("检查报告")
        .column(column -> column.gap(6)
                .fixedText("检查结论", text -> text.minHeight(24))
                .text(longDescription, text -> text
                        .style(style -> style.fontSize(11).lineHeight(16)))
                .grid(grid -> grid.columns(1, 2).headerRows(1)
                        .allowRowSplit(true)
                        .defaults(cell -> cell.padding(4)
                                .textStyle(ParagraphStyle.builder().fontSize(11).lineHeight(16).build()))
                        .row(row -> row.cells("分类", "内容"))
                        .row(row -> row.cell(longCategory, cell -> cell.rowSpan(2))
                                .cell("第一项"))
                        .row(row -> row.cell("第二项"))))
        .paragraph("后续正文。")
        .build();
~~~

`longDescription`、`longCategory` 是调用方文字。字体组和导出后端由调用方显式配置，见[链式 API](fluent-api.md)。

| 入口 | 行为 |
| --- | --- |
| `column.text(...)` | 创建 `FlowText`，一次换行，按完整行跨页／栏续排 |
| `column.fixedText(...)` | 创建 `TextComponent`，支持最小高度，整块移动 |
| `column.grid(...)`／`documents.grid(...)` | 组件网格，支持合并与显式格内续页 |
| `column.table(...)` | 原组件网格入口保留兼容，等同 `.grid(...)` |
| `documents.table(...)` | 语义表格，保留字段、来源及 DOCX 路径；当前没有合并格 |

项目尚未发布。原先 `.text(value, text -> text.minHeight(...))` 改为 `.fixedText(...)`；配置回调的类型从 `TextComponent.Builder` 改为 `FlowText.Builder`，显式声明回调类型的调用方也须迁移。短文字的默认字号和颜色保持原值，但长文字现在可以续页。`GridTable` 新增 record 分量 `allowRowSplit`；旧四／五参数构造器保留，默认 `false`。

## 合并格的内容与分页

拆分模式支持标准文字格、`FlowText`、显式 `OpenTypeText`，以及由 `FlowColumn` 组合的文字、图片与普通组件。后续补充 `FormText`／`ChoiceGroup` 的矢量标记完整行续排，见[静态表单指南](static-forms-api.md)。SemanticTable 已支持长合并格内的富文本、字段及图片续排，见[语义格指南](grid-semantic-flow-api.md)。`FontScope` 保留明确的字体组及缺字策略。普通组件、`TextComponent` 和其他自定义分页组件按单片段原子内容处理；自定义组件不会因开关而自动获得格内拆分能力。

各格先按扣除内边距的固定列宽测量一次。文字保留完整行、塑形轮廓、来源及诊断，续页不重新换行。按跨度、行、列的固定顺序计算自然行高；每页分配逻辑行高度，各格独立输出能完整放入的内容。跨区未输出的内容增加该格末行的剩余高度，因此行高不消耗尚未绘制的文字。

每个占用区域重复完整表头、单元格内边距、背景和封闭边框。跨页格的空白续段仍有边框；已经输出的短格文字和图片不重复。垂直对齐作用于当前可见格片段。嵌套列内边距逐片段重复，列间距在片段开始时重置。

图片与普通自定义组件整块输出。放不下下一内容的短首区可跳过；完整续区放不下完整行、原子组件或最低行数约束时明确失败。行高和页数不会通过缩小文字或裁切内容满足。固定页区及 `component.layout(context)` 仍要求单片段。

## 限制与诊断

- 拆分模式仍禁止跨越表头边界的合并格。表头必须能完整放入每个新区域，并留有正文空间。
- 格内 `FlowText.pagination(...)` 的断前／断后最低行数约束保持；嵌套内容仍拒绝语义块邻接策略。
- 每格文字最多 100,000 行，混合列嵌套最多 64 层；拆分分配最多 100,000 个片段、10,000,000 次工作检查。既有输入、布局输出行、页数及执行预算继续适用。
- 缺字诊断保留文字自己的原 UTF-16 范围。表头警告随实际重复呈现返回。普通组件文字拥有组件块和页上下文；格内 SemanticTable 进一步保留自身原表行列，不等于外层网格格坐标。
- 语义 Table 模型合并格、DOCX 合并映射仍在[缺口表](capability-gaps.md)跟踪。语义格续排没有统一两套表格模型。

## 验证与许可

完整样例见 [GridContinuationExample](../examples/src/main/java/com/mirakyux/export4j/examples/GridContinuationExample.java)，包括 120 行中文合并格、图片、短首区、双栏、复杂文字和固定文本。运行 `:examples:gridContinuation` 后，使用 `scripts/verify_grid_continuation.py` 独立检查内容、格矩形、字形和图片，并对全部页面做 Poppler／PDFium 渲染。

本批分页与组合逻辑为项目自主实现，仅使用原有项目代码、JDK 和已审查基础依赖。未加入竞争库代码或运行依赖。证据见[阶段十五验收](verification/phase-15-continuation.md)。

## 不同宽度的连续栏

`GridTable` 实现 `WidthAwareRegionFlow`，可直接作为不同栏宽的 `ContinuousColumns` 内容。每个区域重新解析列权重，格内标准文字、OpenType、FormText／ChoiceGroup、SemanticTable 及 FlowColumn 按实际宽度继续；已经接受的行保留其历史宽度和原始来源，正文不切片、不缩放。具名字体及样式作用域保持。

表头按当前宽度重测，在确认本区可以推进正文后才输出；短区不会提前消费表头或正文。每个区域重新满足剩余最小行高及横纵合并约束；已经输出的短格不会重复。默认原子合并组仍整体分配，`allowRowSplit(true)` 才允许完整行续排。普通或自定义固定组件按当前宽度重新测量，仍保持原子。

每个文字／语义来源的重复测量累计最多 4,000,000 UTF-16 工作单位；原有格数、嵌套深度、片段、取消及总工作预算保持。语义插槽按同一完整来源的连续片段登记，不放宽独立重复来源的错误。底层公共游标见[变宽表格](variable-table-continuation-api.md)，隔离验收任务为 `complexFlows`。
