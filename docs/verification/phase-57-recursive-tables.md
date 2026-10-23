# 阶段五十七：递归表格与富文本续排

## 行为与输出

`allowRowSplit(true)` 支持多段格、内表及纵向合并 owner 的逐行续排。各层表头按实际片段重复，字段在最终页解析；默认原子行为保持。语义表最多 16 层，来源保留原始行列、格块序号及 UTF-16。短区／取消／预算失败不消费游标。

`RichTextComponent.flow()` 支持等宽连续栏和区域组合，保留各运行字体与颜色。`ContinuousColumns.balanced(true)` 支持有界试排，在不增加页数的条件下缩短公共栏高；变栏宽另有契约需求。

## 可复现命令

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:recursiveTables :examples:flowPdf :examples:tablePdf
python scripts/verify_recursive_tables.py
```

Python 使用 `scripts/requirements-verification.txt` 固定版本。新隔离 Maven 调用方覆盖递归正文、SemanticTable、原生 DOCX 及自动平衡富文本，运行方式见[发布指南](../releasing.md)。

## 已检查证据

`examples/build/recursive-tables` 包含三页递归正文、两页连续表格栏和两页富文本栏。独立验证器核对 24 条正文及两侧格各一次、三层重复表头、最终 PAGE／PAGES、图文链接和唯一图片；标准 OOXML 保留三层 `w:tbl`、`vMerge` 及可拆行设置。7 页由 Poppler／PDFium 分别渲染并逐页查看，最终自动平衡版本已重新生成并检查。

当前证据不包括 Word 保存回读、变栏宽、单向源嵌套或任意深度递归。
