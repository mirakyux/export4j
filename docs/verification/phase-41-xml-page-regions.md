# 阶段四十一：XML 页眉页脚与独立主题验收

日期：2026-10-21。范围为固定文字页区及最终页码，设计见 [ADR-0048](../adr/0048-xml-page-regions.md)，调用见[页区指南](../xml-page-regions-api.md)。

## 实现范围

- XML 主源的可选 page 后、正文前各声明一次 header/footer；显式高度与间距，支持绝对长度单位。区域仅含 p、文字／变量、span 和 PAGE/PAGES 固定宽度字段。
- 模型 PageRegion／PageRegions／PageDocument 与组件转换器分离，模板模块仍只依赖模型。DocumentTheme 新增独立 headerStyle/footerStyle，保留完整几何、角色、RGB、显式局部恢复及命名样式规则。
- 新绑定入口共享根数据快照和正文预算，旧 render 拒绝丢失页区。PageTemplate 提前预留正文空间，最终组合复用已有文字和字段解析；支持目录偏移与 Java 分节重启页码。
- 超高、未知角色、字段宽度不足、非法结构及预算／取消失败明确返回。失败布局不调用后端，原子目标和临时目录保持完整。

## 行为与制品验证

新增 **12 项 JUnit**，覆盖自然分页／正文唯一性、主题独立和局部覆盖、旧入口兼容／拒绝、共享绑定预算、静态语法与全部分支、零外联与脱敏位置、空间／字段溢出、配置冲突、目录／分节最终页码、缺字 UTF-16 来源、取消恢复、快照及旧目标保护。

最终全仓 **1,041 项测试通过，失败／错误／跳过均为 0**。完整编译、Javadoc、SBOM、本地 Maven bundle 与全新隔离 Maven 调用方通过。没有新增依赖或改变模块依赖方向。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:xmlPageRegions :examples:xmlTemplates :examples:xmlPageSettings :examples:xmlTextDefaults :examples:xmlThemes :examples:xmlIncludes :examples:pageRegionFonts :examples:componentText :examples:componentPdf :examples:advancedPdf :examples:production :examples:capabilities
.\gradlew.bat :examples:themes :examples:inlineFields :examples:xmlReferences :examples:contentsStyles
.\scripts\verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_page_regions.py
```

验证使用 Java 21、Gradle 9.2.1、PDFBox／FontBox 3.0.8；隔离 Python 安装 `scripts/requirements-verification.txt`，实际版本为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0 和 cryptography 50.0.2。Gradle 验证使用独立用户缓存目录；Linux/macOS 使用 `./gradlew` 及等价隔离调用方命令。

## 独立输出

| 样例 | 最终页数 | 观察结果 |
| --- | --- | --- |
| `examples/build/xml-page-regions/report.pdf` | 3 | 目录位于第 1 页，17 行正文按原序分布到第 2、3 页；每页页眉及 1/3、2/3、3/3 字段唯一；目录链接指向第 2 页 |
| 隔离调用方 `consumer-xml-page-regions.pdf` | 2 | 8 行正文按 6+2 分页；独立主题字体／颜色与 1/2、2/2 字段正确；无交互对象 |
| `examples/build/xml-page-regions/report.svg` | 3 | 保留物理页尺寸、重复页眉和最终字段逻辑值；静态预览显式只选择绘制页面 |

独立解析核对实际嵌入的常规／粗体字体、字号、RGB、局部空角色／黑色恢复、正文唯一性、页区边界、字段 ActualText 和实际着色轮廓。PDFium 另核对普通页眉文字；这不等于轮廓字段具有通用搜索／选择兼容。

全部 **5 页**经 Poppler 与 PDFium 以 192 DPI 渲染，并逐张打开两种引擎的完整页复查。最大平均通道误差约 0.919，最大变化像素占比约 0.00284；没有页区重叠、文字裁切或正文丢失。结果位于忽略目录 `build/verification/xml-page-regions/`。未修改视觉基线。

14 组历史独立检查通过：`verify_xml_templates.py`、`verify_xml_page_settings.py`、`verify_xml_text_defaults.py`、`verify_xml_themes.py`、`verify_xml_includes.py`、`verify_xml_references.py`、`verify_page_region_fonts.py`、`verify_component_text.py`、`verify_themes.py`、`verify_inline_fields.py`、`verify_contents_styles.py`、`verify_advanced_pdf.py`、`verify_production.py`、`verify_capabilities.py`。

## 保留边界

XML 分节／首页奇偶变体、背景／前景、页区图片／表格／链接／引用和完整外观级联未在本批实现。Java 显式模板组合保持原能力。DOCX 页眉页脚、Word 读取／保存和其他阅读器交互未在本批验收；本子集不改变既有 DOCX 支持范围。

后续扩展：页区图片／语义表格、XML 分节和 DOCX 默认页眉页脚见 [ADR-0049](../adr/0049-semantic-page-regions-and-sections.md)及[阶段四十二](../verification/phase-42-sections-and-docx-regions.md)。上述原始子集边界为该阶段快照。
