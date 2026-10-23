# 阶段六十六：合并自动列

普通语义表格已支持横向／纵向合并覆盖有界 Auto 列。固定列不变，自动列在最小／最大范围内分配，窄页面可收缩到最小值；一次内边距、真实 owner 与源预算保持。

与阶段六十七共同完成全仓 1,324 项测试，零失败／错误／跳过；构建、SBOM、本地 bundle 和全新隔离 Maven 调用方通过。新增自动列样例 2 页、调用方 1 页均经 Poppler／PDFium 双渲染并逐页检查。标准 DOCX 的 `gridSpan=2` 及两项 `vMerge` 独立解析通过。

独立检查宽／窄表各 10 个边框、固定 40 pt 首列、自动列 20～80 pt 边界、宽表对称分配、窄表两列各 20 pt、真实纵向 owner、正文顺序和尾格唯一性。回归覆盖普通列／行合并、块格、递归续排及组件语义流。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:advancedTables :examples:columnSpans :examples:rowSpans :examples:blockCells :examples:recursiveTables :examples:semanticTableFlow :examples:gridSemanticFlow --offline --no-daemon
python scripts/verify_advanced_tables.py
python scripts/verify_column_spans.py
python scripts/verify_row_spans.py
python scripts/verify_block_cells.py
python scripts/verify_recursive_tables.py
python scripts/verify_semantic_table_flow.py
python scripts/verify_grid_semantic_flow.py
```

先按[制品指南](../releasing.md)生成本次全新调用方，Python 使用固定验证依赖。比例列仍不提供内容感知的最小字形宽度；窄样例尾列显式固定为 30 pt。块格／内表 Auto、横向分页和单向源自动列不由本项扩展。契约见[自动列合并 API](../automatic-merged-columns-api.md)。
