# 阶段六十七：单向长表完整行分页

`StreamingTable.allowRowSplit(true)` 支持固定列长行按完整文字行续页，兼容富文本、横向合并、图片及被动链接。默认仍整行移动，表头始终完整重复。源行只换行一次，完成全部续片后才读取下一源行。

与阶段六十六共同完成全仓 1,324 项测试、构建、SBOM、本地 bundle 和全新隔离调用方。样例 3 页、调用方 2 页经 Poppler／PDFium 双渲染并逐页检查；连同阶段六十六共 8 页，可见笔画未匹配比例为零。未刷新基线。

独立检查正文 LINE-01～11 按 5／5／1 行分页、后续三格唯一有序、图片及图片链接只出现一次、逐页 URI 数量 6／5／1、12 个边框及页区边界。交付页时源读取次数为 1／1／3，源关闭一次，正文源行数 2、源锚点数 6、实际输出行数 20。

行为检查保留原始 UTF-16 和 `firstLineIndex`，缺字警告只在实际片段出现；表头加下一完整行无法放入新页时明确失败。短格完成后的空框占实际输出行预算，不重复原文、图片或警告。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:advancedTables :examples:streamingMedia :examples:streamingTable :examples:streamingRichText :examples:streamingColumnSpans --offline --no-daemon
python scripts/verify_advanced_tables.py
python scripts/verify_streaming_media.py
python scripts/verify_streaming_table.py
python scripts/verify_streaming_rich_text.py
python scripts/verify_streaming_column_spans.py
```

先生成本次全新隔离调用方；固定验证环境及页图留在忽略目录。纵向合并、块格、自动宽度、未来字段及总页数继续独立，PDFBox 页树仍累积。本项有限样例不证明长期容量。见[拆行 API](../streaming-row-split-api.md)和 [ADR-0075](../adr/0075-single-pass-row-continuation.md)。
