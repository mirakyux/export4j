# 阶段四十二：图文页区、XML 分节与 DOCX 页眉页脚

日期：2026-10-21。范围为固定页区的图片／语义表格、XML 静态分节与标准 DOCX 默认页眉页脚双向映射。设计见 [ADR-0049](../adr/0049-semantic-page-regions-and-sections.md)，调用见[页区](../xml-page-regions-api.md)、[XML 分节](../xml-sections-api.md)及 [DOCX 页区](../docx-page-regions-api.md)。

## 实现与行为

- PageRegion 保存有序 Paragraph／Table 块，复用显式 PNG／JPEG、两层原子块格和固定宽度 PAGE/PAGES。区域预留高度及间距，使用同一语义布局与最终字段解析；放不下就失败，不裁切或缩小。
- XML 根页区作为各节默认值。静态 section 可覆盖纸张／边距、重启显示页码、替换或清空页区；正文及页区内的离线平面 include 保留限定 ID。所有分支编译检查，所有节共用数据快照、绑定计数及同步取消。
- DocxAdapter 直接读写 PageDocument／SectionedDocument，生成标准 header/footer part、各节 default 引用、局部媒体关系、DrawingML、表格及 PAGE／NUMPAGES。不同宽节的表格生成各自物理网格，媒体去重；显式空 part 终止 Word 页区继承。
- 导入按标准关系解析并继承缺省引用。自有几何扩展保持固定预留；普通 Word 文件缺少此信息时须提供 RegionGeometry，字段宽度缺失时也须显式配置。错误关系、外部目标、ID 碰撞、无效几何及预算超限均失败，不用允许损失绕过。
- 页区来源、表格槽、文字、图片及 ZIP 限额跨 part 共用。正文不能把重复页区 ID 当作单一引用目标。布局失败不调用输出后端，原子路径保留旧目标。

新增 **17 项行为测试**，覆盖继承／覆盖／清空、分节纸张与页码、图片和嵌套表格字段、不同宽节的标准表格网格、离线 include、静态声明位置与全部分支、共享快照及预算、严格 DOCX 往返、缺失／外部／类型冲突关系、显式几何、损失诊断和失败时零输出。

最终全仓 **1,058 项测试通过，失败／错误／跳过均为 0**。完整构建、Javadoc、SBOM、本地 Maven bundle 与全新隔离 Maven 调用方通过。没有新增生产依赖或改变模块依赖方向；样例标志由项目 Java2D 代码生成。

## 独立输出

| 样例 | 页数 | 检查结果 |
| --- | --- | --- |
| `examples/build/xml-sections/report.pdf` | 4 | 报告正文 9 行按 7+2 分页；横向附录显示页码 8；最后一节清空页区。页眉图片、页脚表格及总页数 4 正确 |
| `examples/build/xml-sections/imported.pdf` | 4 | 标准 DOCX 严格导入后重绘，与原 PDF 的 Poppler 页面像素完全一致 |
| 本次隔离调用方 `consumer-xml-sections.pdf` | 3 | 正文 8 行按 6+2 分页；第二节改变纸张、显示页码 7、清空页眉并继承页脚；原文唯一且顺序保持 |
| 本次隔离调用方 `consumer-xml-sections-imported.pdf` | 3 | 独立 Maven 制品 DOCX 严格往返，重绘与调用方原 PDF 页面像素完全一致 |

独立 PDF 检查覆盖全部 **14 页**的来源顺序、正文／页区边界、最终 PAGE/PAGES、图片位置、字体嵌入、逻辑文字及真实轮廓。全部页面经 Poppler 与 PDFium 以 192 DPI 双渲染，**28 张完整页逐张查看**，无重叠、裁切或正文丢失。最大平均通道误差约 1.708，最大变化像素占比约 0.01133；没有更新视觉基线。

ZIP/XML 和 python-docx 独立核对标准页区 part、各自关系及媒体、原生字段、显式空区、分节页边距和按实际宽度生成的表格网格。新检查同时验证 Word 报告对应当前 DOCX 摘要，避免沿用其他文件的阅读器结果。生成物与报告保留在忽略目录 `build/verification/xml-sections/`。

本次新隔离调用方上，`verify_xml_page_regions.py` 及 **15 组历史独立检查**全部通过：`verify_xml_templates.py`、`verify_xml_page_settings.py`、`verify_xml_text_defaults.py`、`verify_xml_themes.py`、`verify_xml_includes.py`、`verify_xml_references.py`、`verify_page_region_fonts.py`、`verify_component_text.py`、`verify_themes.py`、`verify_inline_fields.py`、`verify_contents_styles.py`、`verify_advanced_pdf.py`、`verify_production.py`、`verify_capabilities.py`、`verify_docx_fields.py`。进阶视觉基线保持不变；本次未重做独立 PDF/A 合规、签名信任或容量采样。

## Word 阅读器证据

Word 16.0 私有实例已打开生成的 DOCX、重新分页并更新正文和默认页区字段两次，得到 **3 节／4 页**。各节页眉行内图片数为 **1、1、0**，页脚表格数为 **1、1、0**，页脚原生字段数为 **2、2、0**；起始页码为 **1、8、0（继续）**。生成物打开后正常关闭，未操作用户文档。

自动保存及原生 PDF 导出尝试发生阻塞，未取得保存副本或 Word PDF；对应结果不计为通过。本次证明标准结构、Word 打开／更新及 Export4j 严格语义往返，不证明一般 Word 保存回读、完整样式继承或 DOCX 与 PDF 同分页。

## 复现

从仓库根目录运行，先在隔离 Python 环境安装 `scripts/requirements-verification.txt`，并将 Poppler 加入 PATH：

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:xmlPageRegions :examples:xmlSections :examples:xmlTemplates :examples:xmlPageSettings :examples:xmlTextDefaults :examples:xmlThemes :examples:xmlIncludes :examples:xmlReferences :examples:pageRegionFonts :examples:componentText :examples:themes :examples:inlineFields :examples:contentsStyles :examples:advancedPdf :examples:production :examples:capabilities :examples:docxFields
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_sections.py
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_page_regions.py
```

安装 Word 的 Windows 环境可另运行以下读取／更新验证；默认不执行保存或 PDF 导出：

```powershell
powershell -NoProfile -File scripts/verify_docx_page_regions_word.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_sections.py --word
```

验证工具版本：Java 21、Gradle 9.2.1、PDFBox／FontBox 3.0.8、pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0、cryptography 50.0.2。Linux/macOS 使用 `./gradlew`、对应隔离 Python 与等价的 Maven 调用方任务，Word COM 步骤仅适用于安装 Word 的 Windows。

## 兼容与边界

保留旧 PageRegion List 构造器、文字 Builder／访问器及 Section、DocumentTemplate 的旧参数构造器。PageRegion 改为不可变值类并新增 blocks；旧 paragraphs 遇到表格明确失败。Section／DocumentTemplate 增加 record 组件，需要调用方重新编译，不宣称类型形状或二进制兼容。

本批关闭所列三项受支持子集。XML 连续分节、首页／奇偶页区、XML 背景／前景、页区链接／目标引用和更广外观级联不在该契约内；既有高级 Java 模板仍可显式组合。
