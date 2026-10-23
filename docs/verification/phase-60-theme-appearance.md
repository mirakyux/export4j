# 阶段六十：全局主题字体角色与颜色

## 行为与范围

全局 TextAppearance 按属性进入正文、六级标题、便利表格和页眉／页脚。分域覆盖、显式空角色／黑色、单项恢复、完整样式及加入时快照均有测试。Java、组件与 XML 共用既有文字测量和输出路径；显式字体仍由调用方提供。

当前全仓 1,286 项测试通过，零失败、零跳过。build、SBOM、本地 Maven bundle 及全新隔离调用方成功。正式发布不在本阶段验证范围。

## 复现

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:themeAppearance :examples:themes :examples:xmlThemes :examples:xmlTextDefaults :examples:xmlPageRegions
python scripts/verify_theme_appearance.py
python scripts/verify_themes.py
python scripts/verify_xml_themes.py
python scripts/verify_xml_text_defaults.py
python scripts/verify_xml_page_regions.py
```

使用本次 bundle 按[本地制品验证](../releasing.md)重新构建隔离调用方，脚本读取成功目录指针。Python／Poppler 使用项目既定验证环境。

## 独立证据

`examples/build/theme-appearance` 含 semantic、components、xml 三份单页 PDF／SVG，另有 semantic、xml 的原生 DOCX。独立检查实际嵌入的常规／粗体字体、字号、六组颜色、列表标记、页码和总页数、局部恢复以及标准 OOXML 运行和编号样式。隔离调用方另生成一页，覆盖语义／组件默认与主题切换。

四页均使用 Poppler 与 PDFium 渲染并逐页查看；平均通道误差低于 3，变化像素比例低于 4%。原 Java／XML 主题、XML 分域外观和页区的四组独立回归通过，不刷新视觉基线。有限样例不代表任意阅读器、完整 CSS、PDF/UA 或 Word 保存往返验收。
