# 阶段六十一：首页与奇偶页区

## 行为与范围

模型 PageRegions、XML、分节引擎、语义编辑／转换和 DOCX 现在保留默认／首页／偶数页区。首页优先、显示页码奇偶、显式空对、根到节继承及全部变体最大预留均有回归。旧 SectionTemplate.first 继续覆盖两种首页。

全仓 1,297 项测试通过，零失败、零跳过；build、SBOM、本地 Maven bundle 与全新隔离调用方通过。

## 复现

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:pageVariants :examples:themeAppearance :examples:xmlSections :examples:xmlPageRegions
python scripts/verify_page_variants.py
python scripts/verify_xml_sections.py
python scripts/verify_xml_page_regions.py
python scripts/verify_theme_appearance.py
```

隔离调用方按[制品指南](../releasing.md)从本次 bundle 重新构建，验证器使用成功目录指针。Python 依赖及 Poppler 沿用项目验证环境。

## 独立证据

`examples/build/page-variants` 的原始 PDF 与严格 DOCX 读回重导出 PDF 各 7 页，包含显示页码 2、3、4、8、9、10、11，偶数首页、首页优先及空首页。独立检查正文唯一性、全部 PAGE／NUMPAGES、实际常规／粗体字体与颜色、偶数页图像和统一正文边界；SVG 检查最终值。两份 PDF 的绘制文字、样式和坐标逐项对比，坐标误差小于 0.01 pt。

ZIP/XML 独立检查标准页区 part、媒体关系、first／even 引用、titlePg、evenAndOddHeaders 及明确清空。测试另覆盖完全去除私有属性的原生 DOCX、显式几何和严格损失边界，避免仅靠自有元数据读回。新隔离调用方生成 2 页并验证偶数首页回退。

全部 16 页经 Poppler／PDFium 双渲染并逐页查看，平均通道误差低于 3、变化像素比例低于 4%，不刷新基线。XML 分节、既有 XML 页区和主题外观的三组独立回归通过。

此处 DOCX 回读是标准 OOXML 结构和本项目严格导入后的结果，不是 Word 保存回读。一般 Word 保存、跨平台阅读器及完整浮动结构没有新增验收结论。
