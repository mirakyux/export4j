# 阶段五十一：PDF 被动对象的选页与合并

按 [ADR-0059](../adr/0059-passive-pdf-composition.md)和[合并指南](../pdf-object-composition-api.md)实现明确来源命名空间、页面重排、字段／widget 归属、链接和书签目标、附件及文档设置。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfObjectEditing
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_pdf_object_composition.py
```

全仓 1,220 项 JUnit 通过，无失败、错误或跳过。9 项新增行为涵盖顺序重排及嵌套目标、双来源重名隔离、第一来源元数据、缺失目标的不同实际处理、被排除 widget、部分单选组值重置、重复／无效选择、预算／取消／原子性、旧静态拒绝、NeedAppearances 与原始外观不修复、owner 解密和显式损失。

本次新的隔离调用方使用同次本地制品及已缓存固定依赖通过。独立检查 7 页、14 个具名字段、widget 所属页、内链／嵌套书签、附件字节、元数据、选页损失和原绘制流。全部新页 Poppler／PDFium 5.13 双渲染并逐页查看；最大双像素模糊均差小于 1.254，变化像素占比小于 0.01258。相同 PDFium 渲染下，合并页与对应原页差异通过，原绘制流保持。

发现 PDFBox 合并默认获取表单会补默认字体及按需重建外观，已通过借用源 COS 文档的只读生命周期视图和禁止修复的 catalog 入口绕过；新建字体句柄登记到原拥有者，输出文档正常关闭。回归核对默认资源键和外观字节，避免系统字体扫描及隐式修复。源视图不拥有或关闭源资源。

每个源快照只能出现一次且页不重复；来源字段／附件名称按显式前缀改变。未知对象、签名和活动结构仍拒绝，选页不作为安全脱敏。更广 PDF 格式继续按实际子集评估。
