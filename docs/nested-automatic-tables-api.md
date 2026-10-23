# 内表的有界自动列宽

只含普通／富文本、行内图片及字段的语义内表，支持 `ColumnWidth.Auto(min, max)`，也可与固定／比例列及横向／纵向合并组合。宽度以父单元格扣除内边距后的实际内容区为上限，沿用普通表格的有界首选宽度与收缩算法，不使用整页宽度。

```java
var cell = CellFormat.builder().fontSize(10).lineHeight(14).build();
var child = Table.builder("details")
        .columns(new ColumnWidth.Auto(30, 90), new ColumnWidth.Fixed(40))
        .cellFormat(cell)
        .format(format -> format.padding(2).allowRowSplit(true))
        .header("名称", "数量")
        .row("较长的名称", "12")
        .build();
var parent = Table.builder("orders")
        .format(format -> format.padding(4).allowRowSplit(true))
        .row(TableCell.builder().blocks(List.of(child)).build())
        .build();
```

字段仍按实际呈现页最终解析；父、子拆行开关独立，短区试取不消耗来源或子游标，内表头逐续片重复。Auto 首选探测不重复计源、输出行或缺字诊断；实际布局保留独立 padding、原始锚点与格内 UTF-16。嵌套探测中的字体／文字失败也携带从外到内的 `CellLocation`，不能只返回外格位置。

Auto 最小值之和超过父内容宽度时明确失败；某个最终文字框连一个字形也容不下时同样失败。源树、列、覆盖槽、文字、行与页继续共用既有预算，表深仍不超过 16 层。Auto 表本身若拥有块格，仍拒绝；其宽度需要块树的独立首选定义。单向长表没有未来全表，也不因此获得 Auto。

DOCX 允许行内内容内表的 Auto 声明。输出包含独立标准 `tblGrid`、跨度及原生字段，Export4j 元数据保留 min／max，严格回读可恢复声明。标准格宽采用既有语义适配规则，Word 可能重排，不承诺采用相同首选宽度或相同分页；无自有元数据的输入按标准格宽导入。

重复表头中的 PAGE 字段仍由 DOCX 明确拒绝。示例中的 PDF 采用重复字段表头；DOCX 是单独编写的不重复首行版本，保留原生 PAGE／NUMPAGES，不把不支持内容静默转成静态值。更广 Word 保存回读和视觉兼容仍独立。

运行 `:examples:nestedAutomaticTables` 生成宽／窄 PDF 与两份 DOCX，随后运行 `python scripts/verify_nested_automatic_tables.py` 并使用本次隔离调用方。基础算法见[自动列合并](automatic-merged-columns-api.md)，递归模型见[块格指南](block-cells-api.md)，设计及证据见 [ADR-0081](adr/0081-nested-inline-automatic-columns.md)与[阶段七十四](verification/phase-74-nested-automatic-tables.md)。
