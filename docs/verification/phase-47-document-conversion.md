# 阶段四十七：统一转换与损失对账

## 实现

依据 [ADR-0054](../adr/0054-prepared-document-conversion.md)，在模型模块增加同步 `DocumentConversion`，接入 Markdown／HTML／DOCX 真实输入、语义编辑、Web／DOCX 及显式布局后端目标。`prepare` 保存确切候选与编码，发布不重复解析、编辑或编码。全链路使用共享动作／处理策略，目标扫描未编辑内容；旧入口默认契约保持。

## 验证

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:documentConversion :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_document_conversion.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_capabilities.py
```

全仓 1,165 项 JUnit 通过，无失败、错误或跳过；新增 21 项覆盖单次执行、准备后快照、策略优先级、三阶段实际损失、未编辑样式损失、WARN／IGNORE 一致性、不可用处理、注释、缺字、真实 DOCX 输入、物理格坐标、编码失败、预算、取消／上下文恢复及原子目标保持。注释断言首轮错误使用对象字符串，已改为独立收集绘制命令逻辑文字后通过。

新隔离调用方通过，SBOM 仍为 23 项。独立检查 4 个 PDF 页的内容、唯一性和外链，2 个 DOCX 的表格、媒体字节及未编辑颜色，8 个 Web 输出的内容和图片。19 项样例实际损失逐项对账，包含 IMPORT／EDIT／EXPORT 三阶段；IGNORE 与 WARN 的 Markdown 字节一致且保留全部实际损失。DOCX→PDF、DOCX→HTML 与 Markdown→HTML 均使用真实适配器入口。

全部 4 页采用 Poppler 与 PDFium 5.13.0 双渲染并逐页复查；原始均差小于 0.757，双像素模糊均差小于 0.430，变化像素占比小于 0.00421，满足既有表格口径。未刷新视觉基线。生产、字体／安全／归档及既有能力独立回归通过。

## 边界

预算限制准备结果、报告与各模块执行量，不保证全流程恒内存。自定义源和目标为可信调用方代码。DOCX 未建模结构由语义输入显式报损，原包保留另行实现；PDF 语义恢复、任意格式无损互转和一般 Word 保存兼容不在本阶段。
