# 语义组件组合 API

适用于组件容器中的语义表格与区域续排。所有长度单位为 pt，原点位于组件左上角。API 位于 `com.mirakyux.export4j.components`。

## 编写

```java
var table = Table.builder("nested-summary")
        .header("项目", "值")
        .row("状态", "完成")
        .build();
var content = FlowColumn.builder()
        .padding(8)
        .text("前面的文字")
        .column(column -> column.padding(6).semanticTable(table))
        .text("后面的文字")
        .build();
```

使用 DocumentEngine 或 ComponentDocumentRenderer 输出。字体仍由调用方显式加载；中文内容需要支持中文的字体。`semanticTable(Table)` 接受与顶层相同的语义模型；合并网格继续使用 `.grid(...)`。

TableCell.mixed 中的富文本、行内图片、PAGE／PAGES、页码引用和编号引用会保留到最终布局。嵌套表格 ID 可以作为书签或引用目标。目录页偏移、分节显示页码、FontScope 和具名字体角色均在最终解析时保持。单元格注释标记沿用语义表格原有拒绝边界。

## 长表和分栏

`SemanticTable` 实现 `RegionFlow`。上面的 `.semanticTable(table)` 会使用当前页剩余高度，并按完整行自然续排；包含语义 rowSpan 时以传递连通行组为单位整体移动，见[跨行 API](row-spans-api.md)。每个占用区域都重复完整表头。过短初始区只在放不下表头和最小正文行／组时跳过，不生成空白片段。完整续区必须推进，否则明确失败。

```java
var content = ContinuousColumns.builder(FlowColumn.builder()
        .padding(4)
        .semanticTable(table)
        .text("表格后的正文")
        .build())
        .columns(2).gap(16).build();
```

同一物理页的两栏表头 PAGE 相同；后续物理页、目录偏移及分节重启按实际页面解析。书签和页码引用定位表格的首个实际片段。固定的 RowComponent、BoxComponent 和默认整组网格仍调用单视口 `layout()`，长表放不下就失败。显式拆行网格可在长合并格中准备语义流，见[长合并格语义 API](grid-semantic-flow-api.md)。

无语义 rowSpan 时，显式 `table.format().allowRowSplit()` 允许按单元格完整文字行拆分超长正文行；与 rowSpan 的组合明确拒绝。字段、行内图片、文字外链、图片外链与原文 UTF-16 位置随实际行移动。固定行框仍须容纳图像。自动列宽沿用顶层测量，合并覆盖 Auto 列仍拒绝；分配区域时不重新塑形或换行。

## 组合与来源

FlowColumn、RowComponent、BoxComponent、GridTable、MultiColumn、ContinuousColumns 和 MultiFloatText 保留语义插槽及绘制顺序。表格移动到后续区域时，字段取实际所在物理页对应的显示编号。字段解析不改变测量结果。

`ComponentDocumentLayout.body()` 中的 ComponentFragment 通过 `layout().semanticPlacements()` 暴露 TableFragment 与源 Table。原始单元格行列、完整行来源和 UTF-16 范围继续保留。`contextualDiagnostics()` 返回嵌套表格自身的 ID、物理页、行、列及原文位置；生成值仍使用 `INLINE_FIELD:<位置>`。

`SemanticPlacement.fragmentIndex()` 是同一测量流的零基片段序号，`fragmentCount()` 是完整片段数，均不等于页码或栏号。组合和字段解析须保留这两个值。源、宿主块、片段总数及实际字体配置必须一致，序号必须完整且连续。旧五参数构造器表示单片段完整表格。

自定义组合器必须使用核心 `ComponentSemantics.append`。只读取 `commands()` 会明确失败，避免丢弃语义内容。读取静态命令时必须同时传递插槽。插槽只支持平移和完整可见的矩形裁剪；旋转、缩放及部分裁切明确失败。

## 当前边界

- 一张表可在正文区域流中连续呈现。嵌套表格 ID 仍全文档唯一，不允许用同一 ID 放置第二个完整表格；跨分节复用也失败。
- 语义表自身的表头可逐区重复；GridTable 也可重复它持有并验证的缓存语义表头。网格局部确定最终呈现序列，独立重复插入完整来源仍失败。
- 语义表格不用于页眉、页脚或前景／背景装饰；这些路径尚无最终语义解析上下文。
- 表格外部段前、段后间距由父容器的 gap、padding 或 Spacer 控制。
- 旧 TableComponent 仍是静态单视口包装，拒绝字段、外链和字体角色；不自动改变既有契约。
- 本功能不增加 DOCX 嵌套表格支持，不保证任意 Word 文档无损互转。

结构、源文字及图片预算与顶层正文合并检查，每个源计一次；实际片段、输出行、重复表头、字段生成和交互逐次计入。`maxParagraphs` 包括顶层块和嵌套语义源。最终绘制文字仍受组合输出预算限制。组件先在内层默认限额内准备，再统一检查调用方配置的更小全局限额；这不等于测量前已继承全部全局限额。

单表准备默认最多 1,000 个占用区域，当前组件文档还检查全局物理页数和片段数；连续分栏可能先达到单表区域上限。核心 `DocumentLayouter.layoutTableRegions` 可使用显式 `DocumentLayoutLimits`，并另有 100,000 个占用片段硬限。最多允许 16 个短初始区域。所有测量仍驻留内存，不等于端到端流式。自定义组件仍为可信、确定的同步代码，预算验证不代表可执行任意不可信回调。

实现依据见 [ADR-0021](adr/0021-component-semantic-insertions.md)和[区域续排决策](adr/0022-semantic-table-region-flow.md)。固定组合证据见[阶段十七](verification/phase-17-semantic-components.md)，区域续排证据见[阶段十八](verification/phase-18-semantic-table-flow.md)。
