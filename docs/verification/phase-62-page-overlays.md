# 阶段六十二：XML 背景与前景

## 行为与范围

固定 PageOverlay 贯通模型、XML、主题、绑定、编辑、组件与 PDF／SVG／PNG。图层按节继承、替换和清空，不改变正文预留；显示页码／总页数、共享源与预算保持。DOCX 的明确丢弃和 Web 的流降级分别报告实际损失。

全仓 1,309 项测试通过，零失败、零跳过。build、SBOM、本地 bundle 和全新隔离调用方通过。

## 复现

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:pageOverlays :examples:pageVariants :examples:themeAppearance :examples:xmlSections :examples:xmlPageRegions
python scripts/verify_page_overlays.py
python scripts/verify_page_variants.py
python scripts/verify_theme_appearance.py
python scripts/verify_xml_sections.py
python scripts/verify_xml_page_regions.py
```

从本次 bundle 按[制品指南](../releasing.md)构建新隔离调用方。Python 使用项目固定依赖，Poppler 在 PATH 中。

## 独立证据

`examples/build/page-overlays` 含 4 页 PDF／SVG／PNG ZIP、显式移除图层后的 DOCX、普通流 HTML／Markdown 和损失计数。源页码为 8～11，三页前景最终字段和三页背景按继承／清空结果出现；正文唯一性与坐标、真实常规／粗体字体、图层颜色、表格内边距和 10 次图像绘制均独立核对。

三组重叠色块分别来自背景、正文和前景。PDF 内容指令的图像顺序、独立渲染后的可见像素与项目 PNG 三方一致，证明定位与遮挡关系。新调用方另输出一页，覆盖 XML 图层及最终字段。全部 5 页 Poppler／PDFium 双渲染并逐页查看；4 页 PNG 对照独立 PDF 渲染。平均通道误差低于 3、变化像素比例低于 4%，不刷新基线。

DOCX 独立检查正文与 4 个图片保留，图层内容明确移除；转换记录 10 项 DOCX 损失，HTML／Markdown 分别记录 35／81 项实际损失。测试检查默认严格拒绝、IGNORE 保留损失、预检／实际对账及不可用 PRESERVE 拒绝。原页区变体、主题、XML 分节和 XML 页区独立回归通过。本项没有新增 Word 保存或跨平台阅读器验收结论。
