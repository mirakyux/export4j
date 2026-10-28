# 架构设计

本文将[立项文档](project-proposal.md)整理为实现约束。当前共 18 个模块，包含 16 个库、BOM 和示例。可选 `export4j-opentype` 封装原生塑形，不让核心强制依赖 JNI；生产入口仍位于组件模块。实际能力见[能力指南](capabilities-api.md)，声明式模板边界见 [ADR-0025](adr/0025-safe-declarative-templates.md)，行内图片及 DOCX 媒体见 [ADR-0010](adr/0010-inline-image-layout-and-docx-media.md)。

## 分层与数据流

~~~mermaid
flowchart TD
    M[Document Model 文档模型] --> L[Layout Engine 布局引擎]
    L --> IR[Layout IR 布局结果]
    IR --> DL[Display List 绘制指令]
    DL --> PDF[PDF Backend]
    DL --> SVG[SVG Backend]
    DL --> R[Raster Backend]
    DOCX[DOCX / OOXML] --> I[DOCX Importer]
    I --> M
    M <--> Web[HTML / Markdown Adapter]
    M --> E[DOCX Exporter]
    E --> DOCX
    XML[Offline XML / Markdown Template] --> T[Template Compiler]
    T --> B[DocumentTemplate + Explicit Data]
    B --> M
    Existing[Existing static PDF] --> PA[PDF Adapter]
    PA --> Combined[Checked extraction / selected PDF pages]
    PA --> Recovery[Explicit-loss semantic recovery]
    Recovery --> M
~~~

文档模型表达内容的含义；布局结果表达内容的位置；绘制指令表达具体绘制操作；后端负责把指令映射到目标格式。DOCX 使用独立的模型映射路径。

变宽递归表格与网格在布局层从完整来源重测剩余内容，已接受的行保留实际宽度，不切断原始 UTF-16 或重编号 owner；轮廓浮动也在排版阶段确定行带和锚点。原生 OpenType 模块在绘制前完成方向、轴及彩色字形处理，输出自有字形／轮廓／图像指令。后端无需持有或调用塑形引擎。

高级颜色、画刷、隔离组和裁剪由后端中立绘制类型表达；各输出器保留原生资源或通过共享处理策略报告确切损失。单次语义流与显式快照重放独立于完整文档快照，重放由应用提供可重开来源并验证摘要；`PdfSpoolBackend` 使用单页对象和磁盘索引，不改变普通 `PdfBackend` 的文档持有方式。设计分别见 [ADR-0099](adr/0099-variable-recursive-grid-and-contour-flow.md)、[0100](adr/0100-native-paints-and-offline-svg-resources.md)、[0101](adr/0101-explicit-native-opentype-and-cff-embedding.md)和 [0102](adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md)。

无障碍输出另由后端中立 `AccessibilityTree` 保存逻辑阅读顺序及最终命令绑定。`DocumentPainter.paintAccessible` 使用源模型 ID 和已有 Layout IR 建立受支持的标题、段落、列表、图像及表格关系，不重新测量或分页；复杂来源由调用方显式绑定。PDF 后端据此生成 PDF/UA-1 标签，其他后端拒绝不能保留的非空语义树。原对象编辑、选择性重建、自动几何推断及离线标准验证分别保持自己的资源与损失契约，见[ADR-0097](adr/0097-explicit-pdf-editing-recovery-and-standards.md)。

## 核心层职责

| 层 | 输入与输出 | 负责的工作 | 边界 |
| --- | --- | --- | --- |
| 文档模型 | 语义节点、样式和资源引用 | 文档结构、构建、遍历与校验 | 不携带后端对象或文件格式句柄 |
| 字体与文本 | 字体资源、文本、塑形配置 → 字形与度量 | 字体解析抽象、测量和塑形接口 | 不将字体解析库类型暴露到公共契约 |
| 布局引擎 | 文档模型、约束和资源环境 → Layout IR | 换行、尺寸计算、分页和布局诊断 | 不写目标文件 |
| 布局结果 | 页面、片段、边界与来源映射 | 保存最终位置、基线和可追踪关系 | 不持有打开的后端资源 |
| 精确绘制 | Layout IR 或调用方绘制请求 → Display List | 图形状态、变换、裁剪和绘制指令 | 不决定段落换行和分页 |
| 输出后端 | Display List、资源 → 目标输出 | 坐标映射、资源嵌入和文件写入 | 不反向修改布局 |
| 格式适配器 | 格式结构 ↔ 文档模型或专用处理模型 | 导入、导出、能力映射和损失诊断 | 不承诺所有格式无损互转 |

Graphics API 支持调用方直接生成 Display List。此路径用于精确绘制，不强制构建语义文档。

## 模块规划

模块按实际需要创建。下表规定职责和主要生产依赖方向；实际依赖清单由构建文件给出。核心模块可以依赖 JDK 与已审查的内部基础抽象，但不得反向依赖后端或测试模块。

| 模块 | 职责 | 主要项目内依赖 |
| --- | --- | --- |
| `export4j-model` | 语义节点、样式和资源标识 | 保持基础层独立 |
| `export4j-font-core` | 字体资源、度量抽象和生命周期 | 保持基础层独立 |
| `export4j-text-core` | 自适应简单／OpenType 塑形、文本测量与段落行布局 | 字体层、模型层执行检查点 |
| `export4j-opentype` | 可选的原生方向／特性塑形、可变轮廓、彩色字形及竖排区域流 | 布局核心和绘制核心；LWJGL／HarfBuzz 封装在内部 |
| `export4j-graphics-api` | 绘制接口、几何与输出 SPI | 模型、文本层的字形数据 |
| `export4j-graphics-core` | 图形原语、指令记录与执行协调 | 绘制接口 |
| `export4j-layout-model` | Layout IR、布局片段和来源映射 | 绘制 API 的几何类型及传递的模型、字形与行数据 |
| `export4j-layout-core` | 约束、换行、分页与绘制转换 | 布局结果、文本层与绘制层 |
| `export4j-components` | 标准绘制组件、目录与导航编排 | 布局核心定义的组件接口与绘制核心 |
| `export4j-api`（未创建） | 当前由组件模块 `DocumentEngine` 提供入口 | 有明确拆分需求时再建模块 |
| `export4j-backend-pdf` | PDF 输出 | 绘制层与字体层 |
| `export4j-backend-svg` | SVG 输出 | 绘制层 |
| `export4j-backend-raster` | 图像输出和预览 | 绘制层 |
| `export4j-adapter-docx` | OOXML 导入与导出 | 文档模型与资源抽象 |
| `export4j-adapter-template` | 离线 XML／Markdown 子集编译为 DocumentTemplate | 仅文档模型与 JDK |
| `export4j-adapter-web` | 语义 HTML5／CommonMark-GFM 导入／导出与逐功能损失策略 | 文档模型；CommonMark／jsoup 解析封装在内部 |
| `export4j-adapter-pdf` | 有界 PDF 读取、页／被动对象编辑和组合、渲染提取、优化、脱敏及语义恢复 | 模型及执行控制；PDFBox 隔离在内部 |
| `export4j-testing` | 测试样本、断言和比较工具 | 按测试需要依赖各层 |
| `export4j-conformance-tests` | 格式与行为一致性测试 | 被测模块与测试工具 |
| `export4j-bom` | 对外发布模块的版本约束 | 发布模块的制品坐标 |
| `examples` | 可执行用例和集成方式 | 公共 API 与所用后端 |

PDFBox 文档及绘制 API 限定在 PDF 实现模块。FontBox 和 pdfbox-io 可封装在字体层内部，公共签名不暴露其类型。DOCX 专用实现依赖限定在 DOCX 实现模块。专用测试可以使用它们，但不能以此替代独立工具验证。

PDF 原对象处理不调用布局引擎；[语义恢复](adr/0064-bounded-pdf-semantic-recovery.md)使用独立显式损失通道生成模型，随后由调用方选择 DOCX／Web 或布局后端。原严格复制入口的输入快照、预检、权限与原子输出边界继续保持，见 [ADR-0027](adr/0027-pdf-operations-adapter.md)。PDFBox 的对象解析不等于进程资源沙箱。XML／Markdown 模板共用模型绑定；通用 Markdown／HTML 导入与模板语法独立，不建立平行排版器。

布局核心定义组件扩展接口，由组件模块实现，避免相互依赖。公共入口通过接口接受后端；示例或调用方选择具体实现。

`export4j-bom` 对齐对外模块版本。第三方依赖版本优先由 Gradle version catalog 集中管理，不混淆两者职责。

## 第一阶段最小结构

第一阶段从文档模型、绘制接口、绘制核心、PDF 后端和可执行示例开始。测试先放在对应模块中；存在复用需求时再提取测试工具模块。

阶段一 `export4j-model` 提供页面尺寸与文档元数据，绘制 API 引用这两个基础类型，打通 Graphics API 直接生成 Display List 的路径。阶段三在基础模型中加入语义文档、段落、格式及页边距，保持模型无项目外部依赖。

阶段二已补齐字体抽象、普通换行与行布局。阶段三已实现段落流式分页与基础表格，完整语义布局仍在后续范围内。

## 第二阶段文本路径

~~~text
显式 TrueType 数据 → FontFace / FontData → SimpleTextShaper
                    → ParagraphLayouter → ParagraphLayout
                    → TextLayoutPainter → GlyphText → PDF
~~~

字体层提前解析 Unicode 映射和水平度量，返回不可变快照并关闭解析资源。文本层执行固定回退和有界换行，保留原文 UTF-16 范围、基线、字形编号与 advance。行布局只描述一个段落的局部坐标，页面分配由布局核心完成。

绘制核心只平移已有行布局。PDF 后端嵌入同一份字体字节，校验字形编号，以 `TJ` 定位修正保留既定 advance。复杂文本直接消费中立轮廓，分页用 `atBaseline()` 保留所有绘制结果。字体缓存限定在一次输出内部；相同 SHA-256 的字体按文档复用。详见 [ADR-0003](adr/0003-font-text-layout.md)。

## 第三阶段混合内容分页路径

~~~text
Document.blocks / Paragraph / Table / PageSettings + 显式字体栈
    → DocumentLayouter → DocumentLayout / LayoutPage.elements / LayoutFragment
    → DocumentPainter → DisplayDocument → PDF Backend
~~~

模型层的 `ParagraphFormat` 保存语义格式，不依赖文本或绘制模块。布局核心负责映射为文本层的 `ParagraphStyle`，每段只换行一次，再把完整行放入页面内容区域。跨页片段保留段落 ID、索引、原始行号与 UTF-16 范围。

分页按行推进，保留整块的段落按段推进；不可容纳内容明确失败。结果是不可变快照，输出转换不重新测量、换行或分页。字体回退诊断存于对应页面片段内，可由页面和节点定位。

段落与表格共用有序块及页面片段。表格默认只在完整行之间分页；显式 `allowRowSplit` 按单元格完整文字行拆分，保存起始行和续行标记。续页复用已测量表头并计入输出预算。表头组与最小正文片段不能放入新页时失败，避免只含表头的无限分页。

`Document` 使用统一页面设置，`SectionedDocument` 支持按节配置纸张、页边距与起始显示页码；字体组及行内角色在布局入口显式传入。页眉页脚通过核心 `PageTemplate` 预留区域，最终页码组件在分页完成后执行。基础分页规则见[文档布局 API](layout-api.md)和 [ADR-0004](adr/0004-paragraph-pagination.md)。

## 组件与交互路径

~~~text
Document + PageTemplate + Navigation + 显式字体栈
    → 预留页区 → 正文分页（含 ComponentBlock）
    → 测量前置目录 → 解析最终页偏移和交互目标
    → PageComposer（背景、正文、页眉页脚、前景）
    → DisplayDocument + DocumentExtras → PDF Backend
~~~

核心定义 `Component`、`ComponentContext`、定位和页区合成，标准组件模块提供实现。组件只测量并返回局部不可变指令与诊断，正文适配器对普通组件按整块分页，对 `PageableComponent` 消费有序片段；原语义段落和表格继续支持拆分。核心不导入组件模块。

`MultiFloatText` 在组件层验证多个边缘矩形、计算各固定行带的连续可用区间，并通过已有 OpenType 逐行宽度／偏移接口完成一次换行。浮动矩形只出现在首片段，续页恢复全宽；封闭行带跳过或输出有限的浮动独占片段。布局核心和后端不重新计算避让，见 [ADR-0011](adr/0011-multiple-rectangular-floats.md)。

核心 `RegionFlow`／`FlowRegions` 表达相同宽度下的有限短区前缀与完整续区，返回带区域索引的局部片段。`FlowText`／`OpenTypeText` 一次换行后分配完整行；组件 `ContinuousColumns` 把首组剩余页高和完整续栏组合为物理片段，末片段保留最高栏实际高度。原 `MultiColumn` 的整页／有界平衡契约保持，见 [ADR-0012](adr/0012-continuous-text-regions.md)。

`FlowColumn` 为区域子组件传递扣除内边距、已用高度和区域内间距后的剩余前缀，支持嵌套；普通组件和旧分页片段整块移动。`GridTable` 一次测量单元格，按合并连通行组分配，每个占用栏重复表头且至少包含一个正文组。共享校验检查数量、顺序、尺寸和连续推进；后端不重新布局，见 [ADR-0013](adr/0013-mixed-content-regions.md)。

`DocumentExtras` 保存已解析的书签、页内链接、外部 URI、评论和字段，不持有 PDFBox 对象。目录采用固定页码列，分页不依赖最终数字；解析后使用真实页目标。PDF 后端仅转换目标坐标并建立 outline、annotation 和 AcroForm 字段，不重新布局。

评论和表单是独立于绘制层的交互对象，PDF 阅读器通常在页面内容之上显示它们。当前表单含文本、复选框、下拉框与单选组，中文字段完整嵌入一个显式字体；保留当前值、默认值和外观流，不执行脚本、不默认扁平化。详见[组件 API](components-api.md)和 [ADR-0006](adr/0006-components-and-pdf-interactions.md)。

## 几何与坐标约定

以下约定适用于新建接口，变更时须记录 ADR：

- 逻辑长度使用 point，`1 pt = 1/72 inch`；核心计算使用 `double`。
- 页面逻辑坐标原点位于左上角，X 向右、Y 向下。
- 页面与内容尺寸必须有限且符合各自有效范围；负坐标可以表示页外位置，不等同于负尺寸。
- 路径、矩阵组合顺序和裁剪行为必须在接口文档及测试中明确。
- 文本定位区分行框与基线，图片定位区分布局尺寸与原始像素尺寸。
- PDF 等目标格式的坐标差异由后端统一转换，覆盖旋转、裁剪区域与变换场景。

语义模型保存页面设置和样式，不保存排版后的绝对位置。几何类型采用项目自有类型，避免布局结果引用 PDFBox 或 AWT 实现对象。

## 文本与资源

文本处理区分 UTF-16 代码单元、Unicode 码点、字形和字形簇。测量与输出共用一致的字体及塑形结果。

字体资源通过显式配置解析，回退顺序固定且可诊断。`FontGroup` 默认缺字报错，显式占位模式保留原文来源并返回警告。基础 `SimpleTextShaper` 仍拒绝复杂文字；默认段落布局器按需使用显式静态 TrueType／CFF 的 JDK OpenType 排版，`TextOutline` 保存中立轮廓及逻辑文字。显式 `OpenTypeText` 继续提供 kerning 等开关，不使用系统字体回退。详细边界见[生产能力 API](production-api.md)。

`OutlineText` 在布局时确定轮廓，PDF 用 ActualText 保存逻辑文字；静态后端消费相同路径。`GlyphText` 转静态输出时按既定字形编号提取轮廓，禁止重新塑形。SVG／Raster 拒绝交互 extras；DOCX 直接映射语义，不经过坐标指令。详见 [ADR-0008](adr/0008-production-capabilities.md)。

资源引用使用稳定标识，不持有任意后端对象。文档级资源由独立上下文管理，关闭时释放其拥有的资源。全局缓存应有容量、并发和失效策略。

## 模板与受控导出

`DocumentTemplate` 在 JDK-only 模型层快照并绑定点路径数据，条件／循环／集合表格受访问、深度、块、行和文字预算约束。它不执行反射、脚本或网络访问。

`ExecutionContext` 在同步调用范围中传播取消、截止时间、进度与输出字节上限；finally 恢复或移除 ThreadLocal。原子写入先完成同目录临时文件，再原子替换目标。PDF 临时存储仅转移后端流缓冲，不转移 IR、Display List 和资源。PDF/A、加密与外部 CMS 签名由 PDF 模块封装，见 [ADR-0009](adr/0009-shared-text-templates-and-controlled-export.md)。

## 诊断与失败

诊断至少能够定位处理阶段、严重程度、稳定错误代码和原因。适用时附带节点标识、页码或资源标识；页码的起始约定由契约明确。

输入无效、缺少必需字体、不可恢复的布局失败和输出失败，应使对应操作明确失败。格式特性降级必须记录具体损失；只有调用方明确接受的降级才能继续。

分页算法需要证明每轮消耗内容或改变分页状态。不可分割内容大于可用页面时，必须执行明确的溢出策略或失败，不能无限生成新页面。

## DOCX 与 PDF 的边界

DOCX 导出保留段落、表格和样式语义，由目标阅读器完成其布局。PDF 输出使用确定的页面绘制结果。

DOCX 行内字段映射为原生 PAGE／NUMPAGES／PAGEREF／编号 REF，由 Word 更新；可忽略扩展只保存引擎预留宽度，不限制 Word 布局。导入解析白名单简单／扁平复杂字段，宽度缺失须显式指定，无法保留的语义按策略失败或返回缓存文字及诊断；重复表头 PAGE 明确拒绝导出。边界见 [ADR-0016](adr/0016-native-docx-fields.md)。

PDF 导入不能天然恢复原始段落、表格或 Word 结构。未来的 PDF 分析与编辑适配器应单独定义能力，不承诺从任意 PDF 无损重建 Document Model。

每个适配器应声明支持、降级和不支持的特性，并为信息损失提供诊断。

## 可复现性

常用编写入口仍生成原有语义模型；自动 ID 不代替显式引用目标。两端对齐由布局阶段修改字形 advance 或平移物理轮廓，后端不重排。严格中文禁则按原始 UTF-16 边界判断，富文本标题／列表在模板和语义降级中保留运行，见 [ADR-0017](adr/0017-authoring-and-justification.md)。

相同输入、字体、资源和布局配置应产生相同的布局结果。测试需要固定字体版本、资源标识顺序、区域设置和相关配置。

布局一致性、视觉一致性和文件字节一致性分别验证。输出文件可能包含时间戳、对象顺序等元数据；只有专门声明的确定性输出模式才要求字节稳定。

## 关键决策

公共链式构建、组件层 `Documents` 便利入口、声明式格定位与确定性行高见 [ADR-0018](adr/0018-fluent-authoring.md)。便利入口生成原有语义文档，不改变模块依赖方向或后端布局边界。

分层、DOCX 数据流和第三方边界见 [ADR-0001](adr/0001-layered-architecture.md)。字体实现、模块发布坐标和具体依赖版本在实际采用时记录验证结果。

第一阶段实现范围与标准字体策略见 [ADR-0002](adr/0002-initial-pdf-output.md)。

第二阶段字体实现、行框和公共接口变化见 [ADR-0003](adr/0003-font-text-layout.md)。

第三阶段语义段落、片段来源与分页推进见 [ADR-0004](adr/0004-paragraph-pagination.md)。

混合块兼容、列宽和重复表头见 [ADR-0005](adr/0005-basic-tables.md)，调用方式见[表格 API](table-api.md)。

组件扩展、页区、定位与 PDF 交互见 [ADR-0006](adr/0006-components-and-pdf-interactions.md)。进阶能力、字体组缺字策略和有界脚注预留见 [ADR-0007](adr/0007-advanced-layout-and-font-policy.md)及[进阶 API](advanced-api.md)。

行内动态字段采用固定宽度原子盒，最终分页后替换轮廓及逻辑文字，保持原始来源与几何。语义单元格复用字段盒，重复表头按实际页面解析，拆行保留原始行列及 UTF-16 来源，见 [ADR-0015](adr/0015-semantic-table-fields.md)。显式脚注标记以实际所在行决定归属，使用原有单调预留和续页算法。模型、文本、布局和后端职责见 [ADR-0014](adr/0014-inline-semantic-fields.md)与[调用指南](inline-fields-api.md)。

语义 PageRegion 保存有序段落／表格，Section 携带默认重复页区；XML 分节共享绑定预算，组件桥在最终页上测量固定区及解析字段。DOCX 直接映射标准页区 part 与局部媒体关系，模型不引用组件或后端。固定几何元数据与普通 Word 显式回填策略见 [ADR-0049](adr/0049-semantic-page-regions-and-sections.md)。

HTML／Markdown 通过独立语义适配器导出，由目标阅读器重排；不反向消费 Display List。ERROR／WARN／IGNORE 按功能控制损失，非法输入与执行失败不受策略压制。共享源／输出预算与原子输出契约见 [ADR-0050](adr/0050-semantic-web-exports.md)。
