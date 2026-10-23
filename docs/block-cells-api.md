# 多段单元格与原子内表 API

单元格可按源顺序容纳多个独立段落与内表，段落保留各自 ID、格式、图片和固定宽度字段。使用 `TableCell.builder().paragraph(...).table(...).paragraph(...)` 表达结构；设计见 [ADR-0034](adr/0034-atomic-block-cells.md)，实际证据见[阶段二十七](verification/phase-27-block-cells.md)。

~~~java
var cell = TableCell.builder()
        .paragraph("summary", "摘要")
        .table("details", table -> table.columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(2))
                .header("项目", "结果")
                .row("编号", "A001"))
        .paragraph("conclusion", "结论")
        .columnSpan(2)
        .build();
var table = Table.builder("report")
        .columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
        .row(cell)
        .build();
~~~

示例中的 ID 由调用方显式指定，用于书签、引用和来源诊断，必须在整个文档内唯一。格 Builder 没有全篇自动编号上下文。已有段落用 `paragraph(Paragraph)`，已有块列表用 `blocks(List<? extends Block>)`；配置回调同步执行，列表立即快照。`paragraph(id, text)` 使用当前格的字号、固定行高、对齐及断行策略，段前段后间距为 0；完整段落格式通过 `Paragraph` 指定。

## 模型与测量

`TableCell` 的行内与块内容互斥。Builder 的 `text`／`content`／`blocks` 切换分支会替换旧分支，连续 `paragraph`／`table` 则追加块；`TableCell.rich`／`mixed` 是另建行内格的静态工厂。块列表必须非空，仅接受 `Paragraph`／`Table`。根表计第一层，最多 16 层，超过共享深度预算明确拒绝。真实空段落仍占一个完整行框。

先调用 `hasBlocks()` 选择分支。块格的 `text()`／`inlines()`／`images()`／`fields()` 明确抛出 `IllegalStateException`，避免旧代码把多段当作空正文。`blocks()` 返回不可变列表；行内格返回空列表。`columnSpan`／`rowSpan` 在两个分支都有效，覆盖槽仍通过 `TableGrid` 定位真实锚点。

父格按覆盖列宽之和扣一次内边距，内表以父内容宽度解析固定／比例列及行内内容的有界 Auto 列，不使用整页宽度。格内第一个块的前间距和最后一个块的后间距不计入格高；相邻块的前后间距相加。父子 padding 独立。各叶最终布局一次，Auto 首选探测独立且不重复计费；完整子树高度进入父行高与纵向跨度约束。

默认整个块格及其父连通行组原子移页／移区，适用于正文、`SemanticTable`、`FlowColumn`、`ContinuousColumns` 和网格中的语义插槽。短首区可跳过，完整新区域容不下则失败，不裁切、缩小或无限重试。普通行内父表头可逐区重复；块格出现在重复父表头时拒绝。内表在原子子树内完整呈现，其表头只出现一次。

显式 `TableFormat.allowRowSplit(true)` 将当前表的格内容按完整段落行和内表片段续排。内表自己的开关独立；开启后逐片重复内表头，关闭后整组移动。纵向 owner 在原始锚点行消费内容，可跨多页，之后延伸到覆盖行；不会复制文字或在同页合并格内加横线。段落 `keepTogether` 和孤行限制保留。短区域和预算失败不提交任何子游标。

[行内内表 Auto](nested-automatic-tables-api.md)已支持；仍拒绝含块格父表的 `Auto` 列宽、格段 `pageBreakBefore`／`keepWithNext`、格内注释标记、组件块、单向长表源块格及 Markdown 新嵌套语法。XML 已通过[多段格语法](xml-block-cells-api.md)接入同一模型；普通行内表既有拆行路径保持。递归续排及纵向组拆分的设计见 [ADR-0065](adr/0065-recursive-semantic-continuation.md)，证据见[阶段五十七](verification/phase-57-recursive-tables.md)。

## 来源、字段和预算

`SemanticTree.walk` 先序遍历每个真实节点，不按对象实例去重，也不把覆盖槽重复作为内容。`CellLocation(tableId, rowIndex, columnIndex, blockIndex)` 路径从外到内；行列为原始物理网格索引，格内块序号从 0 开始，旧行内叶用 -1。布局异常和上下文诊断增加不可变 `sourcePath()`，根块索引及叶局部 UTF-16 偏移保持原义。

全树节点、覆盖槽、叶文字、图片和实际输出行共享一次调用的预算。普通重复表头另外计输出行，内表不另开完整引擎、不重置页号或预算。取消检查及同步上下文恢复保持。

书签、固定宽度 PAGE／PAGES／页码引用／编号引用和图文外链在最终目录偏移与分节显示页码确定后解析。字段不会改变已排好的行宽、原文范围或分页，预留宽度不足仍失败。格内段落和内表均可作为目标；来源 ID 不等于 PDF 对象编号。

IR `CellFragment` 的内容分为 `Inline`／`Blocks`。`Blocks.sourceBlockIndices()` 保存每个子片段的原始格块序号，无内容续格可返回空列表。子片段坐标相对父格 `textOrigin`，绘制和交互矩形统一累积平移；后端只绘制，不重新排版。旧单段 `layout()`／`firstLineIndex()` 对块格失败，完整行数用返回 `long` 的 `lineCount()`。

## DOCX 与迁移边界

DOCX 使用标准 `w:tc` 下有序的 `w:p`／`w:tbl`，内表拥有自己的 `tblGrid`、`gridSpan`／`vMerge` 和父格内容宽度。媒体、字段、关系、书签及输入预算在整个包内共享。原生多段格导入后保留独立 `Paragraph`，不再用换行拼接成一个 `TableCell.text()`；每叶字段位置从各自 UTF-16 原文计算。

末块为表时 DOCX 写入标准必需尾段；只归一掉经验证的 Export4j 自有结构标记。无私有元数据的真实空段保留。多个语义目标落在同一物理首段时，自有书签 owner 元数据需验证可达归属；标准原生输入按规范所有者导入，不能假定保留自有 ID。

`DocxLimits` 新增全树 `maxTableSlots`（默认 1,000,000）与 `maxSourceCodePoints`（默认 4,000,000）；`maxBlocks` 包含全部语义后代，不含自有结构尾段和空合并续格。旧五参数构造器保留。原生字段缺少宽度元数据时仍需显式配置导入宽度。

`TableCell`／`CellFragment`／`DocxLimits` record 分量及 canonical descriptor 改变；保留旧源码构造不代表所有已编译调用方二进制兼容。遍历旧单段视图的代码必须按内容分支迁移。此子集不保证一般 Word 文档无损互转，也不保证与 PDF 同分页。
