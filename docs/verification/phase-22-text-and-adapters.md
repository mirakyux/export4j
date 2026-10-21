# 阶段二十二：多脚本文字、文本模板与静态 PDF 处理

- 日期：2026-10-16。
- 范围：GAP-08 的文字一致性与阅读器语料、GAP-07 的 Markdown／XML 字段与外链、GAP-10 的静态页处理，以及 DOCX 静默损失修正。
- 契约：[文字语料](../text-corpus-api.md)、[Markdown](../markdown-template-api.md)、[XML](../xml-template-api.md)、[PDF 适配器](../pdf-adapter-api.md)；[ADR-0026](../adr/0026-source-clusters-and-shaping-context.md)、[ADR-0027](../adr/0027-pdf-operations-adapter.md)、[ADR-0028](../adr/0028-markdown-templates.md)、[ADR-0029](../adr/0029-xml-fields-and-links.md)。

## 实现与修正

复杂文字的换行同时约束原文簇和物理字体的实际字形簇。缺字替换不再把原始组合簇重新分段；天城文 virama／ZWJ 连接具有保守边界补充。软续行继承段落的双向方向，绘制使用测量时的完整段落上下文；同字体跨颜色内容共同塑形，JUSTIFY 平移既有轮廓而不拆开阿拉伯连字。样式边界穿过不可分实际簇时明确失败。它们不是完整 UAX #29 或全部 OpenType 实现。

独立回查补充修正两条准备路径中的二次方查找及取消空窗：替换位置使用集合查询，诊断仍按原文顺序发出，码点、字体覆盖和警告循环有工作检查点。组件文字逐段／逐行检查取消，空行也受原有 100,000 输出行上限约束。超大缺字簇取消、软行间取消和作用域恢复有回归。

Markdown 严格离线子集编译到既有 DocumentTemplate，支持标题、段落、一级无序条目、真实强调角色、纯文本变量及注册图片。不引入 CommonMark 解析器或另一套布局。Reader 借用、输入及展开预算、诊断位置和未知语法拒绝均有行为验证。补充平面长行的取消检查改按扫描工作量计数，避免绝对 UTF-16 偏移取模跳过所有检查。

XML 新增四类固定宽字段、静态图文外链及一级编号。所有分支都编译检查；目标 ID／list-id 不插值，不猜测循环后缀。链接使用既有 URI 校验，内容与图片 alt 可绑定，href 静态且从不获取。字段替换不改变行宽或分页。原有 XML 报告继续输出无交互的 PDF／DOCX／SVG；新增交互样例单独验收。

新增 `export4j-adapter-pdf`，项目共 16 个模块。静态 PDF 只检查、提取普通字形文字并复制原始页面资源，支持零基有序选择、重复、拆分和合并。交互、签名、标签、图层、Type3、行内图像、未知／多滤镜等结构拒绝。输入、对象、深度、解码、文字、操作符、图像声明像素及输出预算独立控制；内容词法先于 PDFBox token 化校验。路径输出在同目录临时文件完成后原子替换，失败保留旧目标。

测试曾暴露 PDFBox 延迟对象解析捕获 IOException 后返回 null，导致超限流被忽略；内部解析边界现保留为非检查异常并在适配器入口归一化，预算拒绝不再变成成功。AES 加密字典合法 Perms 与 Catalog 认证权限分别判断。全权限 reader 不能仅凭权限位被当作 owner；标准安全处理器认证后的只读状态参与身份判断，R2／R3／R4／R6 及 Unicode owner 规范化均有验证。没有另行实现密码学或 SASLprep。

DOCX 导入原先接受已知 tbl 元素，却只读取 cell 的直接 p，导致内表静默丢失。现在按实际读取容器校验：STRICT 拒绝，ALLOW_LOSS 明确报损；其他已知语义元素放错位置也拒绝或报损。仍没有新增合并／嵌套 Table 模型或完整 OOXML Schema 验证。

## 构建、依赖与调用方

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:textCorpus :examples:markdownTemplates :examples:pdfOperations :examples:xmlReferences `
    :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf `
    :examples:advancedPdf :examples:capabilities :examples:production :examples:authoring `
    :examples:inlineImages :examples:continuousColumns :examples:mixedColumns `
    :examples:inlineFields :examples:docxFields :examples:xmlTemplates `
    :examples:semanticTables :examples:semanticTableFlow :examples:gridSemanticFlow `
    :examples:styleDefaults :examples:gridContinuation :examples:multiFloats `
    :examples:staticForms :examples:adrForm --write-locks --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

完整构建通过，60 个测试类、472 项测试，失败／错误／跳过为 0；相较阶段二十一增加 94 项。PDF 适配器 42 项、Markdown 22 项、XML 字段／外链 12 项、复杂文字及取消边界 16 项、DOCX 损失 2 项。Java 生产、测试和独立调用方以 `-Xlint:all -Werror` 编译；两条进阶缺字警告为预期运行诊断。Javadoc、source JAR、BOM／SBOM 和本地坐标均生成。

SBOM 和隔离运行集合为 14 个自研库与 4 个既有外部运行制品，共 18 个组件，制品哈希与 BOM 约束由生产验证器检查。PDF 适配器仅公开依赖模型，内部复用 PDFBox 3.0.8。XML 新增测试图依赖组件模块，生产依赖仍只为模型／JDK。没有新增第三方 Maven 坐标。

隔离调用方成功目录为 `verification/consumer/build/validation-adcc9561be97`，由 `build/verification/consumer-build-directory.txt` 登记。新增 Markdown、PDF 选页合并及 XML 字段／链接均由公开坐标解析运行；以后验证必须读取新的成功指针，不沿用此目录作为新批次证据。

新增 Noto Sans Devanagari／Thai 静态字体，来自固定官方提交 `ffebf8c1ee449e544955a7e813c54f9b73848eac`，详见[字体记录](../../assets/fonts/README.md)。两份字体未经修改，SHA-256、字节数及共用 OFL 原文已登记。根 NOTICE 与示例 JAR 保留许可；核心 JAR 不携带示例字体。全部文案、模板、合成数据及 PNG 为项目自编，不复制竞争实现。

## 独立输出验收

使用固定 Python 环境：pypdf 6.10.0、pypdfium2 5.13.0、PDFium 153.0.7999.0、Pillow 12.3.0、python-docx 1.2.0。Poppler 始终参与渲染，本机不含 pdftotext。不改交叉渲染阈值，不更新任何视觉基线。

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_text_corpus.py
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_markdown_templates.py
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_pdf_operations.py
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_xml_references.py
~~~

| 输出 | 独立证据 |
| --- | --- |
| 文字语料 12 页 | 拉丁组合、希伯来、阿拉伯、天城文、泰文、中文各两条路径；原文、来源、查询及选中文字；全部页双渲染与逐页复查 |
| Markdown 2 页 | 报告／调用方各 1 页；真实 Bold／Italic／BoldItalic、纯文本绑定、显式图像；独立 DOCX 标题／运行／媒体检查 |
| PDF 处理 11 页 | 两来源 3 页、重排 2 页、拆分 1 页、合并 3 页、调用方 2 页；顺序、几何、资源及诊断；所有复制页与对应来源的 Poppler 像素完全相同 |
| XML 字段／链接 10 页 | 主报告 6 页、Word 伴随 PDF 2 页、调用方 2 页；目录偏移、28 条来源、四次表头、最终引用目标、固定链接矩形及标准 OOXML |

共 35 张新增 PDF 页均以 Poppler 与 PDFium 渲染并逐张查看原始页，未发现可见裁切、重叠或遗漏。文字的陌生语言语义不由视觉检查推断。XML 主报告目录在第 1 页，正文第 2 页，表头实际显示第 2～5 页，终点位于第 6 页；编号引用为 3。文字和图像的被动外链目标及矩形均独立核对。两份 DOCX 的 PAGE／NUMPAGES／PAGEREF／REF、目标书签、24 pt 宽度、标准媒体和外链关系通过独立包检查。

XML 独立脚本初次把编号重启误查为 abstractNum 的 start；已改为按段落 numId 查找标准 num／lvlOverride／startOverride，并严格核对为 3，没有改库或放松编号断言。DOCX 对重复表头 PAGE 在写出任何字节前明确拒绝；允许的伴随文档单独导出。

既有 advanced、production、capabilities、authoring、inline_images、multi_floats、continuous_columns、mixed_columns、inline_fields、docx_fields、static_forms、adr_form、grid_continuation、semantic_tables、semantic_table_flow、grid_semantic_flow、style_defaults、xml_templates 共 18 组独立回归通过。最终 archive.pdf 再经 veraPDF 1.30.3 验证：PDF/A-2b、144 规则／10,629 检查、零失败。本轮没有重新生成签名或扩大签名合规声明。

## 实际限制与下一入口

12 项 PDFium 查询及选中文字都精确匹配，11 项 pypdf 无法提取完整原文，轮廓锚点的最小选区高度约 0.519 pt。报告明确保存这些兼容差异；没有反转查询、隐藏失败样本或把逻辑文字等同于精确选区。未进行真人阅读器搜索／拖选、辅助阅读器或 PDF/UA 验收。

PDF 静态适配器不保存交互、签名或归档合规，不恢复文档模型，也不构成恶意输入沙箱。PDFBox 对象流内部解析／字体内部解析不能由入口预算变成硬 RSS 上限。DCT 声明尺寸检查不等于独立 JPEG 完整解码验证。

本轮 DOCX 仅独立检查标准包结构，未在真实 Word 更新／保存新样例；阶段十二的 Word 证据不能自动扩展到这些新模板。其他操作系统／JDK、完整 OpenType、彩色 emoji、竖排、HTML/CSS、主题逐属性级联、一般 DOCX 合并／嵌套、有界长表源、完整 PDF/UA／PAdES 仍按[执行计划](../remaining-capabilities-plan.md)推进。

证据位于 `build/verification/`：`phase22-final-build.log`、`phase22-tests.json`、`phase22-pdf-tests-final.log`、`phase22-consumer.log`、`phase22-text-corpus-final.log`、`phase22-verify_markdown_templates.log`、`phase22-verify_pdf_operations.log`、`phase22-verify_xml_references-final.log`、`phase22-regression-*.log`、`phase22-advanced.log`、`phase22-verapdf.xml`；四个同名验证目录包含 JSON 与全部渲染页。早期失败日志保留为修复证据，不作为成功证明。

没有提交、推送、创建 remote、启用远程 CI 或正式发布；本地 bundle 发布仅供隔离验证。
