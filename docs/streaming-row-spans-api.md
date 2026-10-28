# 单向长表的有界纵向合并

`StreamingTable.allowRowSpans(true)` 显式启用固定列正文的纵向合并。来源仍为一次性 `TableRowSource`：同一合并连通组内按需读取，整个组准备完成后原子分页，不收集后续独立组或完整正文。默认关闭，原有单行预读路径及旧构造器行为保持。

```java
var spec = StreamingTable.builder("orders")
        .columns(80, 240, 120)
        .header("批次", "条目", "数量")
        .allowRowSpans(true)
        .build();

engine.export(spec, () -> openRows())
        .limits(limits -> limits.maxBufferedRows(32).maxBufferedCodePoints(100_000))
        .backend(new PdfBackend())
        .to(Path.of("orders.pdf"));
```

`openRows()` 由调用方提供。新锚点使用 `TableCell.rowSpan`／`columnSpan`；被前行纵向 owner 占据的列不再提供假格。例如两列中的首格覆盖三行时，第一行提供 owner 和右格，后两行仅提供右格。整行均被覆盖时返回 `TableRow.covered()`，仍占一个物理源行，不生成重复内容或虚假绘制片段。

## 布局与来源

- 锚点按照当前物理行的首个空列依次定位。横纵跨度可组合，但不能交叠或留下未覆盖槽；源提前结束于纵向跨度内时失败。
- 纵向跨度具有传递性：若组内后续行又开始新的跨度，连通组延长至最后一个活动 owner 结束。按跨度、原始行和物理列的固定顺序分配高度约束，各真实 owner 仅测量、计源和绘制一次。
- 整个组与完整重复表头一起放入同一页。剩余空间不足时全组移页；完整新页也放不下则返回 `CONTENT_TOO_TALL`，不裁切或偷偷拆组。完全被覆盖的物理行可以没有独立行高，几何由真实 owner 的矩形表达。
- `PAGE` 在组实际呈现页解析，`PAGES` 沿用准确声明的 `expectedPages`。文字／图片链接、图片和 UTF-16 诊断跟随最终 owner 位置，列号为原始物理列，不是紧凑锚点数组的下标。

## 缓冲和失败契约

`StreamingTableLimits.maxBufferedRows` 默认为 64，`maxBufferedCodePoints` 默认为 1,000,000，均为正数。前者限制一个传递连通组的物理行数，在发现超范围跨度时、继续读取来源之前拒绝；后者累计本组真实锚点的原文码点，在塑形前校验。已有逐格／逐行码点、运行、表格行列、页、输出行、链接、字段与生成值预算继续生效。

原有 `StreamingTable` 与 `StreamingTableLimits` 构造器重载保留，并分别采用关闭纵向合并、默认组预算的行为。新增 record 分量会改变反射及 record 解构形状，依赖完整分量列表的调用方须同步调整。

`maxCells` 按 `rowSpan × columnSpan` 计全部覆盖槽，`sourceCells` 只计真实锚点。源行数包含完全覆盖行；重复表头占实际呈现预算，不重复占源预算。最多保留有限表头、当前页和一个待放置连通组；不预读下一独立组。显式启用纵向合并的读取上限因此是“一组”，默认普通路径仍为“一行”。

取消、来源／消费者失败和预算失败立即停止，不交付半个合并组。高级布局 API 借用来源与消费者，不关闭它们；`DocumentEngine` 导出拥有创建的来源，成功时先关闭来源再完成写出。路径输出失败保留旧目标并清理临时文件，借用流失败可留下已写字节。

## 保留边界

本页描述普通 `export` 的原子连通组。该入口不接受重复表头纵跨，未启用语义模式时 `allowRowSplit(true)` 与 `allowRowSpans(true)` 的组合明确拒绝。需要块格／嵌套或跨页拆分 owner 时，显式设置 `semanticCells(true)` 并使用 [`exportSemantic`／`exportReplay`](streaming-replay-api.md)；自动宽探测、目录／未来引用和组脚注由重放计划提供。`StreamingTable` 仍不是普通语义 `Block`，不走 XML 模型序列化、DOCX 或恢复信息路径。

使用普通 `PdfBackend` 时仍保留累计 PDFBox 文档对象；需要逐页释放对象图时选用 [`PdfSpoolBackend`](streaming-replay-api.md)。有限容量样例不能证明长期无泄漏。原单向能力见[单向长表 API](streaming-table-api.md)。独立调用方运行 `streamingRowSpans`，随后执行 `python scripts/verify_streaming_row_spans.py --consumer-build-directory <本次调用方构建目录>`，分别核对源计数、原子失败、物理格几何、实际字段、链接／图片与双引擎渲染。

本单元的构建、独立输出、调用方及容量覆盖见[阶段九十八](verification/phase-98-layout-text-streaming-and-graphics.md)。
