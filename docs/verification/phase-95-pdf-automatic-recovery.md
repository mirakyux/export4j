# 阶段九十五：PDF 自动阅读顺序与表格恢复

本阶段对应 GAP-20.1／GAP-20.2。`PdfAdapter.importDocument(input).analysis(PdfSemanticImporter.Analysis.AUTO)` 在既有显式网格导入之上增加有界几何分析，恢复可编辑正文和明确子集的表格结构，并通过统一转换入口实际生成 DOCX、HTML、Markdown 和重新布局的 PDF。默认 `LINES` 保持逐行恢复及调用方网格行为。

## 能力与边界

| 项 | 实际行为 | 明确边界 |
| --- | --- | --- |
| 阅读顺序 | 用孤立标题分隔纵向区域，再按明确留白沟形成多栏；栏内由上到下，栏间由左到右 | 几何推断，不把内容流顺序当成正文顺序；任意浮动排版仍无法唯一恢复 |
| 正文结构 | 合并相容字号／行距的连续行，恢复标题、项目符号及十进制列表；重复顶部／底部短文字移入页眉页脚 | 行连接使用空格；不推断完整段落样式、字体族、动态页码或作者意图 |
| 有线表格 | 连接网格线；短断口不制造新行；内部缺边可恢复矩形纵／横合并；独立内部网格形成嵌套 Table | 斜边、不规则格、切字或非矩形连通格不强行转为表格，保留物理文字行并报告不确定性 |
| 无线表格 | 至少三行、二至八列的稳定对齐，另有数字或紧凑矩阵内容证据；实际跨列文字可成为合并表头 | 普通对齐正文不自动转表；空格不能证明纵向合并 |
| 跨页续表 | 相邻页底部／顶部表格的位置、总宽与分列比例相容时连接；相同简单表头保留一次并设为重复表头 | 不把相同列数视为充分证据；无法安全表达的表头保留数据行 |
| 显式覆盖 | `.table(page, xEdges, yEdges, headerRows)` 覆盖同区域自动判断 | 网格需合法且不得切字，不回避源结构或字体检查 |
| 来源 | 每条物理文字行保留源页和左上角 pt 框，表格有区域框；`cellPath` 逐对定位零基物理行／列 | 来源框不等于字符偏移或字形轮廓；一个语义块可能对应多行或多页，不能直接用作脱敏选区 |

所有推断通过 `PDF_LAYOUT / DEGRADE` 记录具体效果及 `UNCERTAIN` 边界。默认 ERROR；WARN／IGNORE 接受相同模型、来源和完整实际损失。原路径仍按 `PDF_VECTOR_GRAPHICS / DISCARD` 报告，Markdown 无法表达的合并／嵌套结构另按目标能力报告，不把降级输出声称为源布局无损往返。

本阶段不扩大既有语义入口的页面、字体、绘制和权限白名单，不执行 OCR、自然语言分析或不可信 ActualText 的替代提取。仍须使用有效嵌入的真实字体程序；复杂裁切或绘制只能使用明确受支持的整页图片降级。完整 API 与阈值见[语义恢复指南](../pdf-semantic-import-api.md)。

## 独立验证

源由 [pypdf 生成器](../../scripts/create_pdf_automatic_fixtures.py)独立构造，不使用 Export4j 或 PDFBox 生成。13 份 PDF、15 个源页覆盖：两栏／三栏与跨栏标题、段落与两类列表、重复页眉页脚、完整及短断线网格、横／纵合并、嵌套表格、无线及无线合并表头、跨页续表、对齐正文、显式覆盖与歧义网格。

[公开 API 调用方](../../verification/consumer/src/main/java/consumer/PdfAutomaticRecoveryConsumer.java)完成 104 次转换：13 份输入 × WARN／IGNORE × 四种目标格式。另验证 16 次拒绝：13 个严格策略、对象预算、操作预算与同步取消；预算失败保留旧目标，源摘要不变，转换请求快照 AUTO 配置，默认 LINES 保持原行为。

[独立验证器](../../scripts/verify_pdf_automatic_recovery.py)通过 pypdf、PDFium、DOCX XML、HTML 和 Markdown 解析检查原生结构与实际输出：

- 核对正文顺序、内容唯一性、标题／列表、页眉页脚、网格维度、跨度、嵌套、跨页表头及显式覆盖；检查 DOCX／HTML 的真实合并和嵌套结构。
- 核对 1,132 个样例源字符与叶来源框的覆盖，包含内联表头及嵌套 `cellPath`；该数值只证明本组样例，不代表任意 PDF 的完整恢复保证。
- 对模型故意删内容及交换顺序执行 24 个负控制，独立判定均能发现错误；WARN／IGNORE 的模型、来源、实际损失及 PDF 像素一致。
- 源和重新布局的 PDF 使用 Poppler／PDFium 在 144 DPI 独立渲染，共 86 张页面图、15 张联络图。逐张检查文字、表格、分页及内容完整性；重排后的栏布局、段落换行和页数变化属于已声明行为，没有裁切或重叠。最终 Maven 输出的这 101 张 PNG 摘要与已目视产物全部一致。

## 复现

`PdfAutomaticRecoveryTest` 的 7 项测试全部通过，覆盖分栏与段落、列表、有线合并、内表、无线与显式覆盖、重复页眉页脚与跨页来源，以及策略／预算／取消。本轮全量构建、SBOM 和本地 Maven 制品发布完成后，另建全新隔离调用方，使用实际发布的 Maven 坐标完成上述 104 次转换及 16 次拒绝；独立验证器对该批最终输出再次全部通过。独立调用方、外部结构／渲染和单元测试分别检查，不用当前编译目录或同一后端读回代替制品证据。这是本地制品验收，不代表向远程制品库正式发布。

从仓库根执行，Python 使用 `scripts/requirements-verification.txt` 固定依赖，并安装 Poppler。其他平台将 Wrapper 改为 `./gradlew`。`<consumer-build-directory>` 是相对 `verification/consumer` 的新构建目录。

```powershell
python scripts/create_pdf_automatic_fixtures.py
.\gradlew.bat :export4j-adapter-pdf:test --tests '*PdfAutomaticRecoveryTest'
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build resolvedLibraries pdfAutomaticRecovery --refresh-dependencies
python scripts/verify_pdf_automatic_recovery.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```

生成物、原始报告、页面图、联络图、测试 XML 与本地构建指针保留在忽略目录。API、支持矩阵和缺口状态只按本节声明的自动子集解释，不外推为任意文档或任意阅读器的无损兼容保证。
