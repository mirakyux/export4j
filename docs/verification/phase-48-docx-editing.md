# 阶段四十八：DOCX 原包编辑与样式导入

## 实现

依据 [ADR-0055](../adr/0055-preserving-docx-package-edits.md)提供[原包文字编辑](../docx-editing-api.md)，并按 [ADR-0056](../adr/0056-materialized-docx-styles.md)接入[有限样式级联](../docx-style-import-api.md)。前者保留未编辑包结构，后者展开受支持格式并明确报告样式图损失，二者不能互称无损语义往返。

## 验证

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:docxStyleImport :examples:docxPackageEdit :examples:documentConversion :examples:production :examples:capabilities :examples:blockCells
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_docx_style_import.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_docx_package_edit.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_document_conversion.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_docx_fields.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_sections.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_block_cells.py
```

全仓 1,189 项 JUnit 通过，无失败、错误或跳过。新增 24 项覆盖保留、快照、普通与混合运行、字段跨段、请求回滚、关系安全、Unicode、预算、取消与流失败，以及默认值／基样式／字符样式／直接格式、切换规则、真实角色、非法未用样式、累计复制预算和分节／空页区组合。

新隔离调用方通过。独立 ZIP／XML 验证 2 个原包编辑样本、3 个修改条目；样例中 13 个未改条目解压字节一致，目录、评论、修订、内容控件、表单及三类页眉结构保持。样式输入图和输出 2 页 PDF、2 个 DOCX、2 个 HTML 的具体字号、颜色、粗斜字体和页眉分别核对。全部新 PDF 页使用 Poppler 与 PDFium 5.13 双渲染并逐页复查，双像素模糊均差最大 0.079，变化像素占比小于 0.00017；无重叠、裁切或内容遗漏。统一转换、字段与分节页区回归通过。

## Word 证据边界

Word 16.0 成功读取编辑后正文、首页／默认／偶数页眉，并读取到 1 个评论、2 个修订、1 个内容控件、1 个表单及 2 个字段。保存副本和直接导出 PDF 均超时；Word 自建纯文本空白文档的保存控制组也超时。探针有界结束并清理自身实例；这组证据不能确定超时根因，也不能登记保存回读或 Word 视觉通过。原始输出仅保留在构建目录。

本阶段没有关闭首页／奇偶页区的语义编辑、浮动图片／文本框、更深内表，以及目录／评论／修订／表单的完整模型映射。
