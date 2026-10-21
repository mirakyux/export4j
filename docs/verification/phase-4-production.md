# 生产能力首批验收记录

日期：2026-10-14。范围：生产能力实现、隔离制品与独立验证。

## 交付范围

| 方向 | 已实现并验证的内容 |
| --- | --- |
| 稳定性 | 真实 Edge 阅读器填写、保存、重开；共享引擎／后端的并发隔离；实际大文档导出 |
| 文字 | 显式 OpenType 组件、kerning、连字、组合字符、阿拉伯语连接字形、双向混排、常见中文标点禁则 |
| 布局 | 顺序多栏、单矩形图文绕排、语义表格完整文字行拆页、脚注续页、多行交叉引用 |
| API 与语义 | Document Builder、DocumentEngine、字体局部作用域、上下文警告、多级十进制／符号列表、命名样式继承 |
| 输出与制品 | SVG／PNG ZIP、DOCX 语义子集、12 个库的 BOM、CycloneDX 库运行清单、独立 Maven 调用方 |

完整调用和兼容性矩阵见[生产能力 API](../production-api.md)。架构决策见 [ADR-0008](../adr/0008-production-capabilities.md)。

## 构建与测试

使用 Java 21.0.10、Gradle 9.2.1、Windows 11。为隔离本机全局 Kotlin DSL 缓存，命令使用工作区 `build/gradle-validation`，没有删除或修改全局缓存。

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:production :examples:advancedPdf --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation -p verification/consumer clean build run resolvedLibraries --refresh-dependencies --no-daemon --console=plain
~~~

完整构建通过，包含 Java 编译、Javadoc、源码 JAR、示例发行包和所有测试。151 项测试，0 失败、0 错误、0 跳过；生产与测试源码采用 `-Xlint:all -Werror`。首轮与前批次已有六个 PDF 示例均运行通过，最后一次又生成生产样例和综合 PDF。

主要新增回归证明：

- 4 个线程执行 24 次独立 PDF 导出，共享不可变引擎及后端，内容与元数据不串文档，借用输出流保持打开。
- 2,500 段落实际生成至少 80 页 PDF，逐段提取核对顺序与唯一性；每项压力测试有 120 秒上限。
- 字距开关会影响实际宽度，组合字符不会从字形簇中间拆开；缺字警告保留原码点及 UTF-16 范围。
- 多栏／浮动续页不丢失文字，短表格单元格续页不重复；长脚注保持完整行且无丢失重复。
- 较早脚注跨页时，新脚注首行仍出现在锚点页；当页全部新脚注的首行无法放入带高上限时明确失败。
- DOCX 支持范围内往返；未知结构、样式默认值、对齐与外部编号覆盖严格失败／返回损失诊断，宏内容类型和 VBA 部件在两种模式都拒绝。
- DOCX 的实体、路径穿越、展开预算、非法语义及导出写流前失败路径。
- 静态后端的资源、页面、绘制、字体与交互拒绝行为。

这些是正确性与有界压力验证，不是长期吞吐量、RSS、泄漏排查或生产容量承诺。原有 5,000 段落性能记录仍见[进阶验收](phase-3-advanced.md)，本批次未重测或替换旧数值。

## 独立工具与视觉检查

验证环境：Python 3.12.14、pypdf 6.10.0、pypdfium2 5.13.0／PDFium 153.0.7999.0、Pillow 12.3.0、python-docx 1.2.0、Poppler 26.07.0。它们仅用于验证，不进入 Java 运行依赖或发行包。

~~~powershell
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
~~~

真实阅读器副本为可选验证输入，不由示例或脚本伪造。重新在其他环境运行时可省略 `--reader-copy`，先实际完成阅读器保存再传入副本。

| 文件（`examples/build/production/`） | 页数／结果 |
| --- | --- |
| `typography.pdf` | 1 页；中英、阿拉伯语、希伯来语、组合字符、连字和字距样本 |
| `columns.pdf` | 4 页；COL-01～40 来源顺序和 PDFium 提取覆盖正确 |
| `float.pdf` | 2 页；文字在图片旁收窄，图下与续页恢复整宽 |
| `split-table.pdf` | 3 页；ROW-01～40 各一次，短单元格只出现一次，表头重复 |
| `notes.pdf` | 4 页；NOTE-01～30 各一次且有序，含仅脚注续页 |
| `semantic-roundtrip.pdf` | 2 页；DOCX 往返后的标题、富文本、列表、表格及横竖分节 |
| `typography.svg` | Edge 独立显示，无外部字体／图片依赖 |
| `typography-pages.zip` | 一页 144 DPI PNG，与同页 PDF 独立渲染比较通过 |
| `semantic.docx` | python-docx 读取：7 个顶层段落、2 节、1 张矩形表格；标题、富文本角色、编号／符号、页码重启及关系正确 |

全部 16 页新 PDF 用 Poppler 和 PDFium 渲染，并逐页检查拼图中的文本、边界、顺序、续页与留白。原有综合 PDF 的 7 页与持久 Poppler 基线比较，全部像素差异为 0，没有更新基线。

新 PDF 在 96 DPI、1 像素模糊后的双引擎平均通道误差最大 3.016905／255，超过 25 灰度差异的像素占比最大 0.037050，低于既有 4／0.06 阈值。PNG 与 PDF 在 144 DPI 的对应值为 0.124435／0.000394；差异来自渲染器抗锯齿与字体栅格化。几何与文字断言独立检查，不只靠宽松像素阈值。

PDF `/ActualText` 按指令顺序保存复杂文字，PDFium 可提取样本逻辑文字；混合基础文字与轮廓锚点的整页提取顺序仍可能不同于视觉顺序。例如样本标题可能在提取时被拆开。因此本次不宣称阅读器通用选择、搜索、复制顺序或 PDF/UA 兼容。

证据输出位于 `build/verification/production/`：`report.json`、逐页 Poppler／PDFium PNG、提取文本、拼图、`svg-edge.png`。历史回归报告位于 `examples/build/advanced-verification/report.json`。

## 真实阅读器填写、保存和重开

通过本地 HTTP 打开综合 PDF，在 Edge 阅读器中修改四个字段并点击保存。实际下载后原样复制至 `examples/build/production/reader-filled.pdf`，重新打开该副本，确认值仍在且字段可编辑。多行备注含一个真实 LF，不是字面量反斜杠与 n。

| 字段 | 保存后值 |
| --- | --- |
| `cn_name` | 阅读器填写验证 |
| `status` | 草稿 |
| `mode` | `/final` |
| `cn_notes` | 第一行“新填中文备注”，第二行“保存后重开验证” |

pypdf 独立检查规范字段树、5 个 widget、有效父字段值、正常外观流、唯一选中单选项和非只读标志。重开截图位于 `build/verification/production/reader-reopened.png`。此验证是 Windows 上一个 Edge 版本的实际证据，不代表所有阅读器及平台。

保存副本的全部 7 页另经 Poppler／PDFium 渲染比较与逐页拼图检查；与 16 页新增样例合计检查 23 页。原始下载与复制后的字节未改写，报告保存副本 SHA-256 以关联截图与字段检查。

## 本地制品与资源

`build/repository` 中的 12 个库和 BOM 已完成隔离发布及独立调用方执行。调用方只有 Maven 坐标和 BOM，没有 project 依赖；四种输出均生成并由独立工具检查。BOM 恰好约束这 12 个库，源码包、Javadoc、POM 和许可存在。

`build/reports/sbom.cdx.json` 为 CycloneDX 1.6 库运行清单，包含 12 个自研库及 PDFBox、FontBox、pdfbox-io、commons-logging 共 16 个制品。自研 SHA-256 与 Maven 元数据所指实际时间戳 JAR 一致，第三方 SHA-256 与既有审查清单一致；测试依赖不进入运行清单。任务以自身 JAR 和实际 runtimeClasspath 为输入，源码变更后重新生成对应哈希。

Noto Naskh Arabic 字体和 OFL 原文已登记来源、固定提交、大小、哈希与根 NOTICE。示例 JAR 字节与资源原文一致。核心库不打包示例字体。完整第三方 LICENSE／NOTICE 保留，SBOM 中 Apache-2.0 标识不能代替内嵌资源原始许可。

## 未覆盖与后续边界

DOCX 目前只支持明确的 OOXML 语义子集，尚无 Word／LibreOffice 视觉验证或任意文件无损转换保证。SVG 输入仍为白名单子集，SVG／PNG 输出不保存交互。OpenType 仅显式组件支持，基础段落仍执行原严格塑形契约；完整禁则、变量轴、彩色 emoji、竖排、PDF/UA、一般 PDF 编辑未实现。

尚未完成长期压力、跨平台真实阅读器验证、稳定公共 API 冻结、正式安全报告渠道和远程发布。此记录不把完成本地制品验证写成正式发布完成。
