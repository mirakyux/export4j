# 阶段四十九：已有 PDF 页面与叠加编辑

按 [ADR-0057](../adr/0057-static-pdf-page-edits.md)实现[页级编辑](../pdf-page-editing-api.md)：旋转、裁剪、内容及页面框等比缩放、显式 TrueType 文字和 PNG／JPEG 前后叠加。复用严格 PDF 安全与预算，不改变旧入口行为。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfPageEditing
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_pdf_page_editing.py
```

全仓 1,200 项 JUnit 通过，无失败、错误或跳过；11 项新增行为覆盖源框、UserUnit、非零原点、真实缩放和图层像素、原流保持、元数据、裁剪策略、复杂文字跳过、权限／解密、快照、预算、流所有权及取消时原子目标保护。

本次新隔离调用方通过。独立检查 3 页的文字唯一性、显式字体、图片、CropBox、旋转、缩放、源操作流及实际损失。全部新页以 Poppler 和 PDFium 5.13 双渲染并逐页目视，最大双像素模糊均差 0.486，变化像素占比小于 0.00468；无内容遗漏或裁切。首轮比较发现 Poppler 默认 MediaBox、PDFium 默认 CropBox，验证器改为明确同一 CropBox，未扩大阈值或更新基线。

裁剪报告隐藏未删除；验证保留源操作流，不能用该操作脱敏。既有交互对象、签名、图层和归档声明继续拒绝，不把静态页编辑扩展为任意 PDF 编辑。
