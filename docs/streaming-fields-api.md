# 单向长表页码字段

单向长表支持 `InlineField.Kind.PAGE`，在实际呈现页解析；重复表头与跨页正文片段各自使用自己的物理页码，从 1 开始。`PAGES` 要求调用方通过 `expectedPages(n)` 声明准确总页数；默认 0 表示未知，允许 PAGE，遇到 PAGES 明确失败。

```java
var style = InlineStyle.of(10);
var format = CellFormat.builder().fontSize(10).lineHeight(20).build();
var header = TableCell.mixed(List.of(
        new InlineText("第 ", style),
        new InlineField(InlineField.Kind.PAGE, "", 24, style),
        new InlineText(" 页，共 ", style),
        new InlineField(InlineField.Kind.PAGES, "", 24, style),
        new InlineText(" 页", style)), format);
var spec = StreamingTable.builder("orders")
        .columns(180).padding(0).expectedPages(3)
        .pageSettings(PageSettings.builder().size(200, 100).margins(10).build())
        .header(new TableRow(List.of(header))).build();
// engine 的显式字体组须覆盖所有源文字及生成数字；应用提供一次性行源。
var result = engine.export(spec, () -> openOrderRows(format))
        .limits(limits -> limits.maxFields(100_000).maxGeneratedCodePoints(1_000_000))
        .backend(new PdfBackend()).to(Path.of("orders.pdf"));
```

`expectedPages` 是校验条件，不是排版估计值。超过声明页数时立即失败；读源结束后若实际页数仍小于声明，也在交付最后页、完成写出器之前失败。即使没有 PAGES 字段，正数声明仍须匹配。它不保证业务记录数与页数有简单对应关系；未知准确总数时保持 0，只用 PAGE。引擎不重读源、不缓存整份文档、不自动修改字段宽度或重新分页。

字段占一个 U+FFFC 原文 UTF-16 位置，按明确 pt 宽度参与一次换行。分页确定后复用完整文档的字段绘制逻辑，在原预留框中生成实际字体轮廓；不会改变行宽、行号、来源或图片。声明的字体角色与缺字策略同样用于生成数字，无法放进一行预留宽度时失败。正文启用完整行拆分后，字段使用包含该字段的片段所在页，而非源行首次出现页。

源表头、运行和 U+FFFC 只计一次源预算。`maxFields` 默认 100,000，`maxGeneratedCodePoints` 默认 1,000,000，分别限制实际字段呈现次数和生成值码点数；重复表头每页重新计费。两位页码占两个生成码点，不扩大源码点数。输出行、诊断、链接和后端预算继续独立生效。

源文字诊断继续使用 `STREAMING_TABLE` 及格内原文 UTF-16。生成值使用 `INLINE_FIELD:<原字段UTF16位置>`，诊断范围属于生成值自身；表 ID、实际页、源行及物理列同时保留。字段溢出或预算异常仍定位原始字段位置。诊断回调异常原样传播；取消与超时不会被忽略。

路径输出失败保留旧目标，关闭一次行源并清理临时文件。目标流为借用流，失败可能已接收部分字节。高级逐页接收器也可能已接收前面的页，必须以整次调用成功为提交条件；声明总数本身不能实现接收器事务。

本页的普通单次源不推断未来信息。需要未知总数、未来页／编号引用、目录、组脚注、块格或自动宽探测时，使用[显式语义／重放入口](streaming-replay-api.md)。正文原子纵向合并及其最终页字段另见[有界连通组](streaming-row-spans-api.md)。基础资源、所有权和后端对象边界见[单向长表 API](streaming-table-api.md)，完整行续排见[拆行指南](streaming-row-split-api.md)。设计与证据见 [ADR-0080](adr/0080-single-pass-page-fields.md)及[阶段七十三](verification/phase-73-streaming-fields.md)。
