# 单向长表拆行分页

```java
var spec = StreamingTable.builder("details")
        .columns(100, 300)
        .header("编号", "说明")
        .allowRowSplit(true)
        .padding(3)
        .build();
```

默认仍整行分页。显式 `.allowRowSplit(true)` 后，正文行可以按格内完整文字行续页，支持普通／富文本、PNG／JPEG、被动链接和固定列横向合并。表头始终完整重复。

每个源格只换行一次。续片保留原始 UTF-16、物理列和起始文字行号；图片随所在行移动一次，不在后续片段重复。单元格固定行距仍须容纳字体与图片。未完成的长行全部交付后才读取下一个源行。

短格内容结束后，在后续片段保留空行框及边框，不重复文字、图片或警告。这些空框和重复表头占实际输出行额度；`bodyRows`、`sourceCells` 和 `sourceCodePoints` 只计源一次。逐页输出诊断定位到真正呈现缺字的物理页及原始 UTF-16。

若新页放不下完整表头、重复内边距及任一格的下一行框，抛出 `CONTENT_TOO_TALL`。不生成无限空页或自动裁切。后端失败／取消立即停止源读取，原子路径保留旧目标并清理临时文件；借用流的失败仍可能留下部分字节。

PAGE 及准确声明总数的 PAGES 已通过[单向字段](streaming-fields-api.md)接入；本项不接入纵向合并、块格、自动宽度或未来引用。设计见 [ADR-0075](adr/0075-single-pass-row-continuation.md)，样例任务为 `:examples:advancedTables`，独立检查为 `scripts/verify_advanced_tables.py`。
