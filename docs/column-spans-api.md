# 语义表格列合并 API

语义 Table 支持连续物理列合并，并贯通 PDF 布局、最终字段、图文外链、Java／XML 模板及 DOCX 标准 gridSpan。设计见 [ADR-0032](adr/0032-semantic-column-spans.md)，结果见[阶段二十五验收](verification/phase-25-column-spans.md)。这里的跨度属于语义模型；组件 GridTable 的跨行网格仍是独立入口。

## 链式构建

~~~java
var cell = CellFormat.builder().fontSize(10).lineHeight(18).build();
var table = Table.builder("results")
        .columns(new ColumnWidth.Fixed(80), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
        .cellFormat(cell)
        .headerCells(TableCell.builder().text("检查结果").format(cell).columnSpan(3).build())
        .row(TableCell.builder().text("说明占两列").format(cell).columnSpan(2).build(),
                new TableCell("通过", cell))
        .row(new TableCell("编号", cell),
                TableCell.builder().text("另一种合并结构").format(cell).columnSpan(2).build())
        .build();
~~~

headerCells 接受真实表头格；原 header(String...) 保持。普通文字、rich 和 mixed 格都可用 toBuilder().columnSpan(n).build() 保留内容并设置跨度。已有构造器与工厂默认 1；跨度为 0／负数或每行覆盖不足／越界在构造时失败。

未声明 columns 时，按首行跨度总和生成等比例列，最多 Table.MAX_INFERRED_COLUMNS（4,096）；更多列须显式配置并满足布局预算，不会由一个巨大跨度分配巨大隐式网格。

## 来源与布局

TableRow.cells() 是压缩的真实内容列表。例如 [A(span=2), B(span=1)] 中，B 的列表下标为 1，物理起始列为 2。columnCount() 返回覆盖列数；cellAtColumn(1) 返回 A，cellAtColumn(2) 返回 B，越界抛 IndexOutOfBoundsException。IR 的 CellFragment.columnIndex、异常及诊断使用物理起始列；原文位置仍是格内 UTF-16。

合并宽度等于覆盖列宽之和，左右内边距仅扣一次，内部不生成假格边框。固定／比例／有界 Auto 列可合并，首选宽度探测与显式最小／最大约束见[自动列合并 API](automatic-merged-columns-api.md)。源代码点／图片和测量按真实锚点一次处理；DocumentLayoutLimits.maxCells 继续计所有覆盖网格槽，不能用合并绕过结构上限。

默认整行移动；显式 allowRowSplit 仍按完整文字行拆分，保留 firstLineIndex、continuedRow、图片及字段的原始位置。相邻行允许不同跨度结构。SemanticTable、短区域、连续栏和网格中的语义表格复用同一核心算法，字段按实际物理页解析，外链用最终矩形。

## 模板与 DOCX

Java DocumentTemplate 普通表格和 Rows 动态原型保存跨度。XML 的 cell 增加固定 column-span="2"；该属性为正十进制整数，不接受变量表达式、符号、空白或溢出。每个静态行、表头和动态原型须完整覆盖 columns 网格。

DOCX 写标准 w:gridSpan，保留 tblGrid 物理列和一个锚点一个 w:tc。导入校验正整数、唯一属性／元素、父容器和完整行覆盖；无网格时按跨度推断。不通过项目私有元数据表达合并。原生字段宽度、图片和链接仍遵循既有 [DOCX 字段契约](docx-fields-api.md)；重复表头 PAGE 依旧拒绝。

## 边界与迁移

阶段二十五的列合并切面不包含语义 rowSpan；后续标准 vMerge 与二维来源见[跨行合并 API](row-spans-api.md)。递归块格见[块格 API](block-cells-api.md)。横向分页仍不支持；StreamingTable 已支持[固定列横向合并](streaming-table-api.md#固定列横向合并)，纵向合并继续拒绝。复杂 Word 表格的旧 hMerge、省略槽及未知结构仍按严格损失边界处理，不能推断几何或扁平化内容后称为无损。

阶段二十五将 TableCell 由五项 record 变为六项，旧构造调用与 accessor 保留；后续 rowSpan 将其扩为七项。record 解构参数数目和反射 shape 改变，调用方需重新编译并调整解构模式。没有新增依赖、字体或隐式资源访问。
