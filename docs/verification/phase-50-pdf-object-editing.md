# 阶段五十：已有 PDF 的被动交互对象

按 [ADR-0058](../adr/0058-passive-pdf-object-edits.md)实现[对象读取与编辑](../pdf-object-editing-api.md)，与旧静态入口分开。接受受限 AcroForm、Text／Link／Widget、页目标书签、内嵌附件及元数据；未知活动结构仍拒绝。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfObjectEditing :examples:pdfPageEditing :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_pdf_object_editing.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
```

全仓 1,211 项 JUnit 通过，无失败、错误或跳过。11 项新增行为涵盖四类字段及当前外观、原内容流、附件字节与快照、字段扁平化损失、只读权限、未知格式跳过、XMP 冲突、脚本／签名／循环／悬空 widget、畸形外观指令、厚边框容量、预算、取消和原子输出。

本次隔离调用方通过。首次刷新依赖因 Maven Central 超时而失败；随后使用同次发布的本地制品、固定锁文件和已缓存依赖以 `--offline` 完成新的隔离构建，未替换依赖或放宽验证。

独立检查样例与调用方共 6 页：字段树／值／normal 外观、链接目标、层级书签、Info、附件完整字节及显式扁平化损失。全部页面经 Poppler 和 PDFium 5.13 双渲染并逐页查看，最大双像素模糊均差小于 1.25、变化像素占比小于 0.01256；同引擎扁平化前后差异通过。验证器显式初始化 PDFium 表单环境，避免把未绘制 widget 的空白结果与扁平化页面比较；未扩大阈值。

填写保留 DA 前景色，显式完整字体／固定字号并检查框容量；复杂格式只能明确跳过。扁平化删除编辑能力，附件始终是不自动保存或执行的不透明字节。本阶段不包含跨输入合并及选页重映射，不能据此关闭整个交互处理缺口；签名、XFA 和未知活动结构不受支持。
