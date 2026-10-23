# 阶段七十四：普通行内内表的自动列宽

嵌套语义表格不再仅因位于父格而拒绝 Auto。实际父内容宽度进入既有列宽算法；含块格表自身的 Auto 限制保持。首选探测异常增加真实内表锚点路径。DOCX 的独立子网格同步保留 Auto 声明、跨度与原生字段。

新增 5 项行为回归：父格实际宽度／独立内边距、自动列横纵合并锚点、精确源码点／行预算及唯一缺字警告、最小值溢出与探测缺字的完整祖先路径，以及严格 DOCX 声明／跨度／字段往返；既有短区及失败纯性案例进一步采用 Auto 内表。全仓 1,362 项测试，零失败／错误／跳过；构建、SBOM、本地 bundle 和本次新隔离调用方通过。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:nestedAutomaticTables --offline --no-daemon
python scripts/verify_nested_automatic_tables.py
```

先按[制品指南](../releasing.md)生成本次隔离调用方。宽／窄样例验证实际 Auto 列收缩、六条正文唯一、完成短格不重复、重复表头最终字段及按真实行拆分的外链。DOCX 使用单独编写的不重复首行版本，因为逐次 PAGE 表头仍明确拒绝；共 4 页经 Poppler／PDFium 双渲染及逐页目视，三份 DOCX 独立结构检查通过；8 组历史独立回归（递归表格、变宽表格、块格、自动列合并、双跨度、语义区域流、单向字段）通过。宽／窄 Auto 列分别为 79.52／60 pt。独立 XML 检查不代替 Word 阅读器保存验收。

本项不关闭含块格表 Auto、单向 Auto、变宽递归子树、Word 通用视觉兼容或 PDF 恒内存。契约及设计见[API](../nested-automatic-tables-api.md)与 [ADR-0081](../adr/0081-nested-inline-automatic-columns.md)。
