# 阶段四十五：通用 Markdown 导入与编辑后多格式输出

## 范围

依据 [ADR-0052](../adr/0052-offline-commonmark-import.md)和[导入指南](../markdown-import-api.md)，新增离线 CommonMark／GFM 子集导入，复用模型和共享处理报告，不改变严格 Markdown 模板。成熟解析依赖、锁、校验摘要、原始 BSD 许可、NOTICE 和 SBOM 同步登记。

## 验证

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:markdownImport :examples:semanticEditing :examples:markdownTemplates
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_markdown_import.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_semantic_editing.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_markdown_templates.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_web_exports.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
```

全仓 1,122 项 JUnit 通过，无失败／错误／跳过；新增 19 项覆盖强调、表格、嵌套列表、显式图片、字面模板语法、源位置、损失策略、零外联、危险输入拒绝、预算、取消和导入后编辑。隔离 Maven 调用方使用本次成功制品。运行清单含 15 个自研库与 7 个第三方组件，共 22 项，许可证分别核对。

独立 markdown-it-py 4.0.0 检查源语法；pypdf 6.10.0 检查 2 个 PDF 页的文字、物理强调字体、图片和链接；直接检查 2 个标准 DOCX 包及 4 个 HTML／Markdown 输出。样例的引用／代码两项 WARN 降级与原始范围一致，调用方的 IGNORE 引用降级仍保留实际记录。全部新页用 Poppler／PDFium 5.13.0 双渲染并复查，无遗漏、重叠或裁切。旧严格模板、Web 导出、语义编辑和生产制品独立回归通过。

独立验证器发现 markdown-it-py 4.0.0 的图片 HTML 渲染遗漏 `text_special` 转义子节点，导致替代文字中的连字符未显示。验证改为检查独立解析 AST 中的完整替代文字，并继续检查实际 HTML 属性；生产输出未改动。原始证据位于忽略的构建目录。

## 边界

不宣称完整 GFM 或任意文件无损往返。引用和代码容器、语言、任务状态及其他未建模项需显式损失处理；图片只从显式注册读取，代码角色不隐式加载等宽字体。第三方解析没有内部硬中断保证。HTML/CSS 输入和统一跨适配器报告按后续单元独立验收。
