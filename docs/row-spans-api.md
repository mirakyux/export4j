# 语义跨行合并 API

`TableCell.rowSpan` 与 `columnSpan` 表达真实语义矩形，PDF 和标准 DOCX 共用同一个源模型。长度仍为 point，坐标原点在左上角。组件网格 `GridTable` 保持自己的复杂组件／显式续排行为。

## 按行编写

~~~java
var format = CellFormat.builder().fontSize(10).lineHeight(16).build();
var table = Table.builder("report")
        .columns(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
        .cellFormat(format)
        .headerCells(TableCell.builder().text("报告\n指标汇总").format(format)
                .columnSpan(3).rowSpan(2).build())
        .coveredHeaderRow()
        .row(TableCell.builder().text("共同分类").format(format).rowSpan(2).build(),
                new TableCell("指标", format), new TableCell("结果", format))
        .row(new TableCell("指标二", format), new TableCell("结果二", format))
        .build();
~~~

每行只写新起点，按从左到右顺序占用下一个空槽。上例最后一行的两个源格分别从物理列 1、2 开始，列 0 已由上一行覆盖。整行完全被覆盖时使用 `coveredRow()`；表头使用 `coveredHeaderRow()`，只有完整 Table 校验为真正全覆盖才合法。原始 `new TableRow(List.of())` 同样需要 Table 上下文校验，独立空行不代表有效表格。

两个跨度都必须为正整数。完整网格不得越界、相交、留洞或跨越表头／正文边界。无显式列时从首行覆盖推断等权列，上限仍为 4,096。

## 来源、行高与分页

`TableRow.cells()` 是本行真实锚点列表。其 `columnCount()`／`cellAtColumn()` 只描述本行锚点的局部连续跨度，不能解析前行占用。一次操作内准备并复用二维网格：

~~~java
var grid = table.grid();
var owner = grid.ownerAt(3, 0);       // 原始锚点在第 2 行、第 0 列
var start = grid.anchorAt(3, 1);      // 本行真实锚点，Optional<Anchor>
var rows = grid.anchorRows();        // 每个物理行的有序锚点，全覆盖行列表为空
~~~

行列从 0 开始，owner 返回原始行、物理起始列和源格。越界查询失败，覆盖槽的 anchorAt 返回空。网格不可变，不保存字体或布局状态，每次 `table.grid()` 新建；ownerAt 最坏扫描源锚点／行，anchorAt 对指定行二分。布局和链接处理在当前同步操作内复用准备结果。

固定／比例列的合并格按完整宽度换行一次，内边距只扣一次。先用 rowSpan=1 的真实格决定各行基础高度；没有此类格的行基础为 0。再按跨度、行、列顺序，将合并格尚缺的高度等分到覆盖行，最后一行补舍入差。覆盖行不会重复添加 padding；有的全覆盖行可以保持零高度。固定行距保留，共享图文段落可能因图像 ascent 与邻格基线不同。

纵向跨度连接的行组成传递连通组，整体移页或移区。重复表头始终完整，必须与至少一个完整正文组放入新区域；过短首区可以跳过，超高组明确失败。显式 `allowRowSplit(true)` 可拆分 owner 内容；原始锚点行排完后再进入覆盖行，已完成的 owner 延伸边框但不重复正文，同页相邻空续格合并矩形。拆分模式不承诺原子行高分配。普通文本合并支持[有界 Auto 列](automatic-merged-columns-api.md)，[行内内表 Auto](nested-automatic-tables-api.md)已支持，含块格表保持独立限制。无纵向合并的既有完整行拆分保持。

IR 仅保存真实锚点；完全覆盖行不产生重复格或文字，但高度计入真实格矩形和表格高度。行列与 UTF-16 位置属于原始锚点。字段在最终页解析，图片和链接只输出一次，表头字段按实际呈现页解析。

## 模板与 DOCX

Java 静态 ValueBlock 绑定保留跨度、富文本、图像和字段。XML 静态格使用固定正整数 `row-span`／`column-span`，空 `<header/>` 或 `<row/>` 表示全覆盖行；整张表验证覆盖。动态单行 Rows 原型首批拒绝 rowSpan>1，避免循环实例跨数据行合并。

DOCX 使用原生 `w:vMerge` restart／continue 和 `w:gridSpan`。覆盖行输出标准空 continuation tc，导入归一到唯一真实锚点，不依赖私有元数据识别跨度。孤立续接、完整区间不匹配、重复或非法属性、错误父链、缺槽、越界、跨表头及非空续接内容在两种导入策略中均失败；不静默丢弃文字、图片、字段或链接。单独 restart 可归一为 rowSpan=1。

重复表头 PAGE 的 DOCX 导出仍拒绝；Word 原生字段的显示宽度和保存策略沿用[字段指南](docx-fields-api.md)。标准结构、Word 读取／字段更新、保存回读及 PDF 导出分别验收，不承诺一般 Word 文档无损或与 PDF 同分页。

## 兼容与边界

保留 TableCell 旧 2—6 参数构造器，旧调用 rowSpan 默认为 1，Builder／toBuilder 保留双跨度。record 组件从 6 变为 7，解构和反射形状变化，不能宣称完全二进制兼容。空 TableRow 的合法性须放到完整 Table 上下文判断。

单向 StreamingTable 继续拒绝任何合并。纵向拆分与递归格内容见[块格续排](block-cells-api.md)和[阶段五十七](verification/phase-57-recursive-tables.md)。任意 DOCX 无损互转仍未承诺。设计见 [ADR-0033](adr/0033-semantic-row-spans.md)，实际验证见[阶段二十六](verification/phase-26-row-spans.md)。
