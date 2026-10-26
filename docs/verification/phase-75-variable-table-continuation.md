# 阶段七十五：行内格跨变宽区域的完整行续排

验收日期：2026-10-26。范围为语义表格显式 `allowRowSplit(true)`、不同栏宽、无纵向合并且无块格的组合。此前该组合直接返回 `INVALID_GEOMETRY`；现在普通／富文本、行内图片及字段以完整原文按实际区域行容量准备，再按完整行续排。契约见[API](../variable-table-continuation-api.md)和 [ADR-0082](../adr/0082-variable-width-inline-continuation.md)。

## 行为与失败

新增 9 项行为回归，另更新原先拒绝普通变宽拆行的既有断言。覆盖宽窄实际换行、原始 UTF-16／累计行号、横向合并、不同格行高、已完成邻格的空续接、短区跳过、作用域／平衡、最终字段及链接；源与输出预算、原始补充字符警告、取消后复用分别验收。

续排短区回归先复现同一行在中途遇到零高区域时提前失败，再验证共同区域计划：三列中首格横向合并、24 pt 行高且先完成，邻格以 12 pt 行高跨区续排；两格共同跳过零高及 12 pt 初始区域，六个实际字形唯一有序、空续接格不重复，最终占用区域为 0 和 3，准确的两片段预算仍可通过。

图片专例在窄区先消费两行，再在宽区呈现 80 pt 图片；图片不能因为未使用的窄宽正文候选而失败。实际落入窄行时仍明确拒绝。字段、图片和完整字形簇保持原子。预算回归验证错误分类保持 `BUDGET_EXCEEDED`，不会被富文本测量入口改写为文字错误。

完整构建、Javadoc、SBOM、本地 Maven bundle 通过；全仓 **1,371 项测试，失败／错误／跳过均为 0**。新隔离构建目录从本地 Maven 坐标编译并实际运行调用方，输出及依赖解析通过。未升级依赖、未刷新视觉基线。

## 实际文件与独立证据

| 样例 | 页数 | 核对内容 |
| --- | --- | --- |
| `examples/build/variable-split-tables/continued.pdf` | 4 | 72 个标签及配套原文唯一且有序；36 pt 邻格只输出一次；横向合并格在 76／192 pt 两种实际宽度续排；图片字节解码后的 12×8 RGB 像素及唯一图片链接；尾部字段为实际第 4 页 |
| `examples/build/variable-split-tables/balanced.pdf` | 3 | 三个原始正文行、48 个标签及配套原文；112／228 pt 栏宽、初始混合内容与有界平衡；前后相邻文字唯一 |
| 隔离调用方 `output/consumer-variable-split-tables.pdf` | 3 | 40 个标签顺序、89.333／178.667 pt 实际栏宽、逐栏表头的最终物理页码及正文链接 |

所有表格边界及链接均位于其实际页面／栏区域。逐栏 PAGE／PAGES 与独立提取的可见文字一致，空续接格不重复短格原文。PDF 由 pypdf 与 PDFium 独立检查结构／文字，再经 Poppler 和 PDFium 双渲染；全部 10 页、20 份渲染均已逐页查看。独立渲染的可见墨迹覆盖检查通过。

7 组相关历史独立回归通过：原子变宽表格、递归表格、行内内表 Auto、普通 Auto 合并及单向拆行、语义表格区域流、变宽最低行数、普通／富文本／复杂文字／混合变宽栏。它们使用本批重新生成的对应样例和新调用方结果。

## 可复现命令

从仓库根目录运行；以下为 Windows PowerShell，其他系统使用对应 Wrapper。依赖已缓存时可使用 `--offline`；首次构建需先取得版本锁定的分发包及依赖。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:variableSplitTables :examples:variableTables :examples:nestedAutomaticTables :examples:recursiveTables :examples:tablePdf :examples:flowPdf --offline --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation :examples:advancedTables :examples:semanticTableFlow :examples:minimumLines :examples:variableColumns --offline --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation -p verification/consumer "-PconsumerBuildDirectory=build/validation-<run-id>" clean build run resolvedLibraries --offline --no-daemon --console=plain
python scripts/verify_variable_split_tables.py
```

调用方成功后才将本次构建目录写入被忽略的 `build/verification/consumer-build-directory.txt`，供独立验证器读取；不以旧调用方产物替代本次执行。其他回归脚本分别为 `verify_variable_tables.py`、`verify_recursive_tables.py`、`verify_nested_automatic_tables.py`、`verify_advanced_tables.py`、`verify_semantic_table_flow.py`、`verify_minimum_lines.py` 和 `verify_variable_columns.py`。

## 范围

不同宽度的纵向合并拆分、Paragraph／Table 递归子树及组件网格仍返回明确的不支持结果；默认原子行／合并行组和等宽递归游标不因本项改变。单向长表、DOCX 同分页、一般复杂文字／跨阅读器兼容及长期容量不在本次验收范围。后端不重新换行或分页。
