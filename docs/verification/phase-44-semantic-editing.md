# 阶段四十四：语义编辑、跨运行查找替换与损失报告

## 实现范围

依据 [ADR-0051](../adr/0051-semantic-editing-and-loss-reports.md)，增加模型级 `DocumentEditor` 和共享 `model.processing` 值。支持三种文档类型、稳定块 ID、物理格锚点、插入／删除／移动、段落和范围样式、图片替换、字面查找替换，以及绑定候选的预检／实际报告。详见[编辑 API](../semantic-editing-api.md)。

跨同样式运行保留链接；不同样式默认拒绝，明确降级采用首运行的完整样式／链接。图片、字段和部分字素簇不能被降级消费。整块及整次跳过会撤销范围内暂存修改和损失；IGNORE 的实际损失仍可查询。完整性和执行失败不能忽略。

## 验证

从仓库根目录执行：

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:semanticEditing :examples:flowPdf :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe -m pip install -r scripts/requirements-verification.txt
build/verification/python-phase13/Scripts/python.exe scripts/verify_semantic_editing.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_capabilities.py
```

全仓 1,103 项 JUnit 通过，无失败、错误或跳过，其中新增 25 项编辑行为。覆盖共享页区、跨运行样式和链接、对象位置迁移、嵌套表格真实锚点、覆盖槽拒绝、整块／请求回滚、预检快照、引用／注释／列表完整性、Unicode／字素边界、取消及膨胀预算。

独立 Maven 调用方从本次本地仓库解析制品并生成输出。Python 固定 pypdf 6.10.0、PDFium 5.13.0、Pillow 12.3.0、markdown-it-py 4.0.0。专项检查：

- 3 个 PDF 页：原始、编辑后和隔离调用方；独立文字及链接检查，Poppler／PDFium 双渲染，逐页复查无裁切、重叠或遗漏。
- 2 个 DOCX 包：直接检查标准 OOXML 的文字、嵌套表格、页区、原生 PAGE 字段和替换后的颜色。
- 4 个 HTML／Markdown 输出：独立 HTML／CommonMark 解析，检查内容唯一性、嵌套顺序及未改链接。
- 1 项实际样式／链接降级，与预检条目、动作、处理、块 ID 和 UTF-16 范围一致。

独立检查初版曾把所有原生字段都假定为 `instrText`；实际标准输出为 `fldSimple w:instr="PAGE"`。修正验证器以检查实际标准结构后重新通过，没有改写生产输出或放宽内容断言。原始日志保留于忽略的构建目录。

## 边界

本批只编辑已经存在的语义模型，不证明一般 DOCX 未建模结构保留或 PDF 内容流编辑。旧 Web／DOCX 策略不由共享值自动替换，统一转换和导入损失报告继续按各自单元验收。Word 人工保存回读、长期容量及跨平台阅读器不在本次结论内。
