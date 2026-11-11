# 示例索引

从仓库根目录运行 Gradle 任务。Windows 使用 `.\gradlew.bat`，Linux/macOS 使用 `bash ./gradlew`；需要 JDK 21，首次构建需要下载固定依赖。示例显式加载仓库字体，来源和许可见[字体记录](../assets/fonts/README.md)。

## 常用任务

```powershell
.\gradlew.bat :examples:authoring :examples:xmlTemplates :examples:production
```

选择与目标场景相关的任务，无须一次执行全部示例。表中输出目录相对于仓库根目录。

| 示例任务 | 输出目录 | 展示内容 |
| --- | --- | --- |
| `:examples:authoring` | `examples/build/authoring/` | Java 编写、文字排版与语义 DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML 报告模板与数据绑定 |
| `:examples:manuWeave` | `examples/build/manuweave/` | ManuWeave 全部 286 个学术模板配置，支持按 Profile ID 选择，输出 PDF 及实际格式／差异报告 |
| `:examples:manuWeaveMath` | `examples/build/manuweave-math/` | 8 类 PDF／SVG 矢量公式及 Word 原生可编辑公式 |
| `:examples:xmlTemplateBundle` | `examples/build/xml-template-bundle/` | 编辑、保存和重开普通 ZIP 中的 XML 源码／资源，再绑定不同数据 |
| `:examples:recoveryClosure` | `examples/build/recovery-closure/` | 导航／脚注／尾注／分节／图层／表格／图片组合恢复、实际编辑及有损多轮 |
| `:examples:xmlModelSaving` | `examples/build/xml-model-saving/` | 语义模型保存为 XML，再重开编辑导出；三轮无编辑控制及明确损失报告 |
| `:examples:xmlRegionSaving` | `examples/build/xml-region-saving/` | 共享页区、变体／图层及保存／重开／编辑控制 |
| `:examples:xmlCrossReferenceSaving` | `examples/build/xml-cross-reference-saving/` | 独立引用三轮保存、跨节页码／编号、目标改写和 Web 损失 |
| `:examples:xmlSemanticSaving` | `examples/build/xml-semantic-saving/` | 脚注／尾注／题注／文字外链完整绑定、三轮控制、实际题注编辑及 Web 损失 |
| `:examples:pageOverlays` | `examples/build/page-overlays/` | XML 背景／前景、可见遮挡关系及明确转换损失 |
| `:examples:xmlPageRegions` | `examples/build/xml-page-regions/` | XML 页眉页脚主题，以及含目录的最终页码 |
| `:examples:xmlSections` | `examples/build/xml-sections/` | 图片／表格页区、分节纸张／页码和原生 DOCX 严格往返 |
| `:examples:webExports` | `examples/build/web-exports/` | HTML／Markdown、嵌入图片及按功能报错／警告／忽略 |
| `:examples:documentConversion` | `examples/build/document-conversion/` | 导入、编辑、实际损失预检和四格式输出 |
| `:examples:pdfObjectEditing` | `examples/build/pdf-object-editing/` | 被动字段／对象及关系重映射选页／合并 |
| `:examples:pdfContentEditing` | `examples/build/pdf-content-editing/` | 真实文字替换及新建纯图片 PDF 的区域脱敏 |
| `:examples:pdfSemanticImport` | `examples/build/pdf-semantic-import/` | 恢复 PDF 标题、显式表格和图片，再编辑与转换 |
| `:examples:production` | `examples/build/production/` | 分页、分栏、绕排、表格与多格式输出 |
| `:examples:staticForms` | `examples/build/static-forms/` | 密集静态表单与矢量控件 |
| `:examples:adrReport` | `examples/build/adr-report/java.pdf` | Java 语义模型编写的三页个例报告 |
| `:examples:adrReportXml` | `examples/build/adr-report/xml.pdf` | 相同数据、版式的 XML 模板个例报告 |
| `:examples:commonDocuments` | `examples/build/common-documents/` | 合同、简历、说明书、报价单、会议纪要和项目周报；包含 PDF、XML、JSON 及引用图片与许可 |
| `:examples:streamingTable` | `examples/build/streaming-table/` | 单向长表输出 |
| `:examples:variableTables` | `examples/build/variable-tables/` | 按实际栏宽布局的语义行、合并 owner 和原子嵌套格 |
| `:examples:variableSplitTables` | `examples/build/variable-split-tables/` | 行内格在变宽栏间按完整行续排，保留最终字段、图片和链接 |
| `:examples:verifyPdfSignatures` | `examples/build/pdf-signature-verification/` | 显式离线 CMS 校验、未签追加及签名字节篡改区分 |

## ManuWeave 学术模板

```powershell
# 生成全部 286 个配置的样例：
.\gradlew.bat :examples:manuWeave
# 列出模板，或只生成指定模板：
.\gradlew.bat :examples:manuWeave --args="--list"
.\gradlew.bat :examples:manuWeave --args="acm-sigconf ieee-conference zh-master"
```

[运行入口](../examples/src/main/java/com/mirakyux/export4j/examples/ManuWeaveExample.java)读取仓库内固定的[模板目录](../examples/src/main/resources/templates/manuweave/README.md)，以共享的虚构学术内容展示各配置的纸张、边距、标题、摘要、正文、分栏、图表及参考文献。全部 Profile ID 与所引用版本的 [ManuWeave README](https://github.com/jiassia/manuweave/blob/e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b/README.md)对应，运行时不访问上游仓库。

每个配置输出 `<id>.pdf`、`<id>-report.xml` 和 `<id>-source.txt`。报告记录源配置、实际采用的字体与排版参数，以及缺省、替代或无法表达的项目。示例使用仓库内已授权的固定字体，未捆绑上游指定的全部字体；这些 PDF 用于演示 Export4j 排版，不承诺期刊投稿合规、LaTeX 类文件执行或 Word 模板无损复现。详细边界、资源许可与独立验证命令见[模板说明](../examples/src/main/resources/templates/manuweave/README.md)。

模板使用真实衬线／无衬线粗斜体、标题相邻保留、图表与题注整组移页、2／2 孤行控制和末栏平衡；有明确源要求的配置生成审稿行号、奇偶页区和镜像边距。示例参考文献按源事实选择有限格式，未执行外部 CSL 或出版社类文件。

可以通过 `--font-map <Properties XML>` 明确提供已授权的源字体，路径及真实变体要求见[模板说明](../examples/src/main/resources/templates/manuweave/README.md)。全部模板的独立结构与双渲染结果见[验收记录](verification/manuweave-templates.md)。

## 数学公式

```powershell
.\gradlew.bat :examples:manuWeaveMath
```

[数学示例](../examples/src/main/java/com/mirakyux/export4j/examples/ManuWeaveMathExample.java)生成分式／根式、上下标、积分、求和、矩阵、分段、希腊字母／三角函数及极限／连乘。`math.pdf` 和 `math.svg` 使用显式 Computer Modern 数学字体排成矢量路径，保留 TeX 原文 ActualText；公式按自然尺寸原子布局，不通过缩小或栅格图片适应栏宽。

`math.docx` 包含 8 条标准 OMML 原生公式，`math-edited.docx` 演示把第一条公式的分母从 `2a` 修改为 `3a`，其他公式保持。`math-standard-round-1.docx` 至 `math-standard-round-3.docx` 验证不依赖项目私有元数据的三轮读写。Word 原生公式 API 不依赖数学库或 Office；用法及受支持的类型见[数学 API](docx-math-api.md)。

矢量示例新增的 JLaTeXMath 1.0.7 仅进入 `examples`，无需安装 TeX；其许可、链接例外、字体条款和源包分发见[依赖说明](dependencies.md)。只允许源码中明确列出的有限 TeX 命令及矩阵环境，拒绝宏、外联、未知命令和超预算输入。两种输出目前分别编写等价内容，没有自动 TeX↔OMML 转换器。

```powershell
python scripts/verify_manuweave_math.py
# 本机有 Word 时，在自有后台实例中实际修改、保存并重开：
powershell -NoProfile -File verification/verify_docx_math_word.ps1 -InputPath examples/build/manuweave-math/math.docx -OutputDirectory build/verification/manuweave-math-word
.\gradlew.bat :examples:manuWeaveMath --args="--verify-word-saved build/verification/manuweave-math-word/word-saved.docx"
python scripts/verify_manuweave_math.py --word-directory build/verification/manuweave-math-word
```

验证脚本使用项目隔离 Python 环境及固定依赖。Word 保存后的普通样式／主题等损失逐项报告，数学类型与设置另行严格核对；应用自动化和人工界面验收不混称，详见[验收记录](verification/manuweave-templates.md)。

## 个例报告：Java 与 XML 两种编写方式

```powershell
.\gradlew.bat :examples:adrReport :examples:adrReportXml
```

两种写法生成相同版式：A4 页面、患者与病史信息、18 列用药表、两层合并表头、不良反应经过、实验室检查、报告人信息、备注和动态页码。示例按内容长度、列宽、行距和内边距自然分页，没有显式分页符；默认虚构数据生成三页，第三页保留备注区域。更换文字或增加数据后，页数会随内容变化。

- [Java 代码](../examples/src/main/java/com/mirakyux/export4j/examples/AdrReportExample.java)直接通过语义模型 Builder 编写表格、段落和页脚，不读取 XML。
- [模板入口](../examples/src/main/java/com/mirakyux/export4j/examples/AdrReportXmlExample.java)编译 [XML 模板](../examples/src/main/resources/templates/adverse-reaction.xml)，绑定数据并保留页脚。
- [共享数据与字体配置](../examples/src/main/java/com/mirakyux/export4j/examples/AdrReportData.java)提供虚构病例及显式字体。修改 `data()` 中的患者、药品、反应经过或检查数据，可以比较两种编写方式的结果。

示例仅用于展示密集表单的编写与排版，病例、药品、机构和日期均为虚构，不用于真实个例报告申报。运行不需要原始 PDF、设计器服务或 Office。字体与真实表单不同，因此展示的是相近结构和内容高度，不承诺与原件逐像素一致。

已配置独立结构及双引擎渲染检查；准备[验证环境](development.md)后运行：

```powershell
python scripts/verify_adr_report.py
```

旧任务 `:examples:adrForm` 保留为合并格、静态控件和长单元格分页的能力探针，输出在 `examples/build/adr-form/`，与此处两份自然分页业务示例分开使用。

## 常用文档模板

```powershell
.\gradlew.bat :examples:commonDocuments
# 也可以只生成一份，例如个人简历：
.\gradlew.bat :examples:commonDocuments --args="resume"
```

[运行入口](../examples/src/main/java/com/mirakyux/export4j/examples/CommonDocumentsExample.java)按 XML 模板绑定 Java 数据并输出 PDF，同时导出配套 XML、JSON，以及引用的 `images/` 和 `licenses/`。每份示例使用相同的文件基名，输出位于 `examples/build/common-documents/`。以下页数对应默认数据；修改内容后自然重排。

| 文档 | 模板及测试数据 | 默认页数 | 展示内容 |
| --- | --- | --- | --- |
| 服务合同 | [XML](../examples/src/main/resources/templates/common/service-contract.xml) · [JSON](../examples/src/main/resources/templates/common/service-contract.json) | 7 | 每页 Logo／公司／版本编号页眉、12 章条款、交付验收矩阵、里程碑、费用与分期付款、SLA、数据处理、变更表、附件与双栏签署 |
| 个人简历 | [XML](../examples/src/main/resources/templates/common/resume.xml) · [JSON](../examples/src/main/resources/templates/common/resume.json) | 2 | 首页照片与姓名并排、成果概览、主次双栏、技能矩阵、工作经历、三个问题／方案／结果项目卡、教育与认证 |
| 用户手册／说明书 | [XML](../examples/src/main/resources/templates/common/user-manual.xml) · [JSON](../examples/src/main/resources/templates/common/user-manual.json) | 8 | 9 章及附录、安装与操作流程、角色权限、参数矩阵、18 条排障记录、维护恢复与条件样式验收清单 |
| 报价单 | [XML](../examples/src/main/resources/templates/common/quotation.xml) · [JSON](../examples/src/main/resources/templates/common/quotation.json) | 4 | 22 项分组服务、两层合并表头、小计与税额、可选优惠测算、阶段交付、SLA、商务边界及确认卡 |
| 会议纪要 | [XML](../examples/src/main/resources/templates/common/meeting-minutes.xml) · [JSON](../examples/src/main/resources/templates/common/meeting-minutes.json) | 3 | 参会角色、五个详细议题、三套方案比较、四项决策、14 项责任行动、风险、待决项与附件 |
| 项目周报 | [XML](../examples/src/main/resources/templates/common/weekly-report.xml) · [JSON](../examples/src/main/resources/templates/common/weekly-report.json) | 4 | 健康度与趋势、里程碑、四个嵌套工作流卡、预算资源、风险依赖、问题变更及管理决策 |

Java 数据位于 [PersonalDocumentData](../examples/src/main/java/com/mirakyux/export4j/examples/PersonalDocumentData.java)和 [OfficeDocumentData](../examples/src/main/java/com/mirakyux/export4j/examples/OfficeDocumentData.java)，配套 JSON 用于设计器的测试数据。合同费用与分期金额是预填并经独立核对的示例值；报价明细、分组小计、税额和优惠测算先由 Java `BigDecimal` 计算，再绑定到模板。XML 不计算业务金额，设计器中修改数量、单价或税率时，也需同步相应计算结果。修改 Java 数据后应同步配套 JSON，以便两种入口使用相同内容。

这些模板展示多层表头及横纵合并、跨页重复表头、富文本角色与颜色、双栏块格和嵌套表格、编号步骤、条件内容及条件样式、固定页区和最终页码。默认共 28 页，正文按实际内容自然分页；简历的教育组和项目卡、说明书的排障记录等局部块保持完整。可修改长条款、增加明细或切换测试数据中的布尔状态，观察续页及条件样式变化。

六套模板也可通过设计器的“新建 → 从样例”选择，资源直接复用此处文件。两种入口均显式使用 Liberation Sans 和霞鹜文楷 TC。文字及版式均为自编，履历、机构、产品、日期及金额为虚构。简历照片来自 Joon Tae Kim 的 Pexels 作品，仅作配图；合同 Logo 为项目自绘的青木示例标记。图片及[来源与许可](../examples/src/main/resources/templates/common/assets/README.md)随项目打包，生成和预览不访问网络。合同仅演示排版与绑定。

简历使用 `resume_portrait` 图片引用，显示尺寸为 64 × 80 pt；合同使用 `contract_logo`，每页页眉显示为 25 × 25 pt。Logo 旁展示 `${contract.version}` 与 `${contract.number}`，修改测试数据后会同步到所有页。设计器样例已注册图片，完整模板 ZIP 会携带图片与许可；仅打开 XML 时需在资源面板补充同名图片。通过 Java 单独编译模板时也需显式注册，例如：

```java
var template = XmlTemplate.builder()
        .image("resume_portrait", ImageResource.load(Files.readAllBytes(
                Path.of("examples/build/common-documents/images/resume_portrait.png"))))
        .build()
        .compile(Files.readString(Path.of("examples/build/common-documents/resume.xml")));
```

选取文档类型和通用信息结构时参考了 [Microsoft Word 模板分类](https://word.cloud.microsoft/create/en/templates/)、[Square 合同示例入口](https://squareup.com/us/en/contracts)、[Smartsheet 报价单](https://www.smartsheet.com/price-quote-templates)、[会议纪要](https://www.smartsheet.com/content/project-management-meeting-minutes-templates)、[项目状态报告](https://www.smartsheet.com/content/project-status-templates)及 [Folge 用户手册结构](https://folge.me/user-manual-template)。仓库未复制这些参考站点的条文、模板文件或品牌设计。简历配图的独立来源见上文资源说明。

生成后运行 `python scripts/verify_common_documents.py`，检查正文完整性与唯一性、记录顺序、多层表头的跨页重复、页数、页码、字体、嵌套格边界、金额，以及图片像素／位置／重复次数、资源许可和所有页面的双引擎渲染。只检查这两份图文模板可加 `--samples service-contract resume`。

## 可视化设计器与完整业务样例

运行 `:export4j-designer:run` 启动可选 HTML 界面；运行 `:export4j-designer:scenarios` 无界面生成五套带源码编辑、保存和重开检查的 XML／ZIP／JSON 样例。常用文档模板另用上文的 `:examples:commonDocuments` 生成。默认目录 `export4j-designer/build/scenarios` 应未存在，重复执行可指定新的 `-PdesignerOutputDirectory=build/<new-directory>`。

| 样例 | 主要行为 | 本批页数 |
| --- | --- | --- |
| 巡检报告 | 变量、条件、循环、图片和源数据编辑 | 1 |
| 密集静态检查表 | 14 行表格、静态正文勾选和审核留白 | 1 |
| 分页库存台账 | 120 行、跨页重复表头及正文唯一有序 | 6 |
| 目录与交叉引用 | 自动目录、标题导航、题注编号和最终页码引用 | 3 |
| 药品不良反应报告表 | 18 列合并表头、数据绑定及按内容自然分页 | 3 |

每套输出六种格式；`report.json` 记录真实编码大小、耗时、观察堆用量和损失数，并完成 11 项失败控制。独立检查运行 `python scripts/verify_designer.py`；非默认源目录使用 `--source`。使用方法、显式字体与预算见[设计器指南](designer.md)，成本及全部页面证据见[阶段一百零八](verification/phase-108-designer-and-business-scenarios.md)。

## 原生 DOCX 与 HTML/CSS 布局

这两组组合样例位于独立 Maven 调用方，消费本地发布坐标。先执行 `build sbom publishLocalBundle`，再为每次验证选择新的 `<validation-directory>`：

```powershell
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<validation-directory>" build docxSemanticComplete htmlCssLayout resolvedLibraries --refresh-dependencies --no-daemon --console=plain
```

输出位于 `verification/consumer/build/<validation-directory>/output/` 的 `docx-semantic-complete/` 和 `html-css-layout/`。运行生成任务不等于完成 Word 或浏览器兼容性验收；应用及独立渲染步骤分别见 [DOCX 专项](verification/phase-103-native-docx-semantic-editing.md)和 [CSS 专项](verification/phase-104-html-css-layout.md)。

## 选择 API 与验证

- 需要完整 Java 调用时，从[示例源码](../examples/src/main/java/com/mirakyux/export4j/examples)及对应指南开始；上表任务以 [Gradle 配置](../examples/build.gradle.kts)为准。
- 需要判断输入范围、拒绝条件或格式损失时，查[文档导航](README.md)及相应 API，不将一个样例视为完整格式支持。
- 需要复现阶段结果时，查对应[验收记录](verification)中的工具版本、命令与边界；生成物和原始日志保留在构建目录。
