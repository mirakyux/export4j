# 架构设计

Export4j 以语义文档为中心，共享文本测量、布局和绘制能力。PDF／SVG／PNG 消费排版结果；DOCX／HTML／Markdown 映射语义结构，由目标阅读器重排。统一入口、模板和设计器负责装配这些能力。

本文描述当前开发版的实现结构。发布版差异见[当前状态](current-status.md)，支持范围见[能力矩阵](capabilities-api.md)，具体取舍见[架构决策](adr/README.md)，已执行的验证见[验收导航](verification/README.md)。

## 分层与数据流

~~~mermaid
flowchart TD
    S[Java 编写 / 源内容 / XML 与数据] --> A[编写与输入编译]
    A --> M[Document / SectionedDocument 语义模型]
    M --> P[语义准备与布局编排]
    P --> L[Layout Engine]
    L --> IR[Layout IR]
    IR --> DL[Display List + 已解析语义]
    DL --> PDF[PDF Backend]
    DL --> SVG[SVG Backend]
    DL --> R[Raster Backend]
    M --> E[语义格式适配器]
    E --> W[DOCX / HTML / Markdown]
    G[精确绘制 API] --> DL
    F[已有文件] --> I[格式读取与专用编辑]
    I -->|显式语义导入及损失报告| M
    I --> O[原格式处理结果]
~~~

图中箭头表示数据流，模块的编译依赖另见下表。各层的职责保持单向：

| 层 | 主要类型或入口 | 负责的工作 | 边界 |
| --- | --- | --- | --- |
| 语义模型 | `Document`、`SectionedDocument`、`Block`、`InlineContent` | 内容、样式、来源、资源标识和模型校验 | 不持有后端句柄、PDFBox 对象或已排版坐标 |
| 字体与文本 | `FontSource`、`FontFace`、`FontGroup`、`ParagraphLayouter` | 显式字体、字形／轮廓、度量和局部行布局 | 不决定文档页码，不向公共 API 暴露解析库类型 |
| 语义准备与编排 | `DocumentEngine`、内部 `PreparedSemanticDocument` | 语义展开、来源验证、导航、页区与最终字段协调 | 不替代核心分页器，不成为全局文档缓存 |
| 布局 | `DocumentLayouter`、`DocumentLayout`、页面片段 | 约束、行带、换行、分页及来源映射 | 不写目标文件；结果保留已接受的几何 |
| 绘制 | `DocumentPainter`、`DisplayDocument` | 将最终位置转为中立指令，绑定交互及无障碍语义 | 不重新换行／分页 |
| 后端 | `DocumentBackend`、PDF／SVG／Raster 实现 | 坐标转换、资源嵌入与编码 | 不重新塑形或修改布局；无法保留的语义须拒绝或明确报损 |
| 格式适配器 | DOCX、Web、模板、PDF 适配器 | 映射语义、处理原格式或显式恢复模型 | 各有支持子集，不承诺任意格式无损互转 |

生成、导入、编辑和转换均不依赖打印机驱动、打印服务、Office 或浏览器进程。普通路径支持无桌面 JVM。Word、Chromium 等用于独立兼容性验收，不进入库的生产调用链。

## 模块与依赖

仓库有 21 个 Gradle 子项目：18 个库、BOM、示例与可选设计器。下表省略 `export4j-` 前缀；依赖列只列**直接生产项目依赖**，`A` 表示 Gradle `api`，`I` 表示 `implementation`，不展开传递依赖及第三方库。实际声明以 [settings](../settings.gradle.kts)、各模块 `build.gradle.kts` 和[依赖记录](dependencies.md)为准。

| 分组 | 模块 | 职责 | 直接项目依赖 |
| --- | --- | --- | --- |
| 基础 | `model` | 语义文档、模板绑定、样式、执行与处理契约 | 无 |
| 基础 | `font-core` | 显式字体来源、不可变字体与有界缓存 | 无 |
| 基础 | `text-core` | 塑形、测量及局部段落行布局 | A: `font-core`、`model` |
| 绘制与布局 | `graphics-api` | 几何、绘制类型、Display List 与输出 SPI | A: `model`、`text-core` |
| 绘制与布局 | `graphics-core` | 图形原语、记录与指令执行 | A: `graphics-api` |
| 绘制与布局 | `layout-model` | 页面、片段、来源映射与 Layout IR | A: `graphics-api` |
| 绘制与布局 | `layout-core` | 分页、区域流、核心组件 SPI 与绘制转换 | A: `layout-model`；I: `graphics-core` |
| 绘制与布局 | `components` | 标准组件、语义编排与 `DocumentEngine` | A: `layout-core`；I: `graphics-core` |
| 后端 | `backend-pdf` | PDF 编码、文档内字体及解析租约 | A: `graphics-api`；I: `graphics-core` |
| 后端 | `backend-svg` | 静态 SVG 输出 | A: `graphics-api`；I: `graphics-core` |
| 后端 | `backend-raster` | 图像及预览输出 | A: `graphics-api`；I: `graphics-core` |
| 适配器 | `adapter-docx` | 通用正文、原生数学及类型化 OOXML 语义读写 | A: `model` |
| 适配器 | `adapter-template` | 离线 XML／严格 Markdown 模板编译 | A: `model` |
| 适配器 | `adapter-web` | HTML／Markdown 语义读写、离线 CSS 布局及源内容会话 | A: `model`；I: `components`、`graphics-core` |
| 适配器 | `adapter-pdf` | 有界原对象操作、渲染、脱敏及显式语义恢复 | A: `model`、`adapter-template` |
| 扩展与装配 | `math` | 有界 TeX 编译、逐次公式身份及目标表示 | A: `model`、`adapter-docx`、`adapter-web`；I: `font-core`、`graphics-api` |
| 扩展与装配 | `opentype` | 原生塑形、可变／彩色字体与竖排流 | A: `layout-core`；I: `graphics-core` |
| 扩展与装配 | `starter` | 配置一次的源内容／XML 与 PDF／DOCX 导出 | A: `components`、`math`、`adapter-template`、`backend-pdf`、`font-core`、`adapter-docx` |

`export4j-bom` 只约束这 18 个库的版本，不自动引入全部模块；第三方版本由 Gradle version catalog 与锁文件管理。`examples` 组合公共 API 演示用法。`export4j-designer` 是独立本地应用，保留 XML 源码、数据和资源，通过 Java 引擎预览／导出；它与示例均不参与 Maven 库发布。

`starter` 包含数学与 Web 内容装配，其传递项目依赖为 14 个库；不引入 PDF 编辑适配器、SVG／Raster 后端或原生 OpenType。需要这些能力时显式选择模块，原生扩展还须选择平台 classifier。核心不反向依赖 starter、适配器、后端、设计器或示例。

PDFBox 文档类型封装在 PDF 实现模块；FontBox 与 pdfbox-io 可封装在字体层内部。JLaTeXMath、CommonMark、jsoup、LWJGL／HarfBuzz 的实现类型也不进入公共 API、异常或 SPI。专用测试使用第三方类型不改变生产边界。

## 主要运行路径

### 配置一次的源内容导出

[Export4j](../export4j-starter/src/main/java/com/mirakyux/export4j/Export4j.java) 保存字体来源、全局配置与有界缓存。每次创建可变的 `Request`，在选择输出时进入独立同步执行范围：

~~~mermaid
flowchart TD
    Q[Request.pdf / Request.docx] --> X[ExecutionContext]
    X --> F[OperationFonts 按操作绑定字体]
    F --> C[MathContent + ContentImporter]
    C --> S[源文本 或 XML compile + render]
    S --> V[compileVisual]
    S --> W[compileDocx]
    V --> D[DocumentEngine]
    D --> P[PdfBackend]
    W --> A[DocxAdapter.writeMath]
~~~

[OperationFonts](../export4j-starter/src/main/java/com/mirakyux/export4j/OperationFonts.java) 将同一声明投影为输入解析角色、PDF 字体组和 Word 字体槽，同一来源对象在一次操作中只载入一次。业务角色的 regular／bold／italic／boldItalic 对应显式物理变体。

PDF 和 DOCX 各执行选定的目标投影。XML 使用 `renderComplete` 绑定分节、页区和注释，再投影到选定目标。同一 `Request` 先后导出两个目标时会分别读取来源并绑定数据。需要一次解析同时得到两种正文模型时，可使用 [MathContent](rich-content-api.md) 的双目标入口。

### XML 语法准备与数据绑定

[XmlTemplate.Syntax](../export4j-adapter-template/src/main/java/com/mirakyux/export4j/adapter/template/XmlTemplate.java) 是调用方拥有的不可变静态语法计划。`prepare` 安全解析结构并展开当时配置的片段，不加载字体、不打开源内容 parser、不绑定业务数据。后续 `compile(syntax)` 按当前主题、资源和解析配置生成 `DocumentTemplate`，重新校验输入／展开预算及语义声明，再由 `render(data)` 快照并绑定 Map、record 或 JavaBean；静态准备成功不代表所有语义或资源已经有效。

`DocumentTemplate` 位于仅依赖 JDK 的模型层。属性访问受深度、集合、块、行和文字预算限制；表达式不允许任意方法调用。公开 getter 仍是可信应用代码，合作取消不能抢占一个阻塞的 getter。

源内容在编写／绑定边界展开成现有语义块。一次 render 对同一 parser 身份共用一个 `SourceContentParser.Session`，跨循环、分节和动态单元格累计预算，完成或失败后关闭。XML／严格 Markdown 模板与通用 HTML／Markdown 导入是不同入口，最终共用语义模型及布局。详见[模板 API](xml-template-api.md)与[源内容块](content-blocks-api.md)。

### 语义准备、完整布局与逐页输出

[DocumentEngine](../export4j-components/src/main/java/com/mirakyux/export4j/components/DocumentEngine.java) 先将语义文档、注释和导航归一到共同准备流程。内部 [PreparedSemanticDocument](../export4j-components/src/main/java/com/mirakyux/export4j/components/PreparedSemanticDocument.java) 只保存语义准备结果和来源块快照，不是公共缓存 API，也不保存完整 Layout IR。

完整布局保留各页 IR，再绘制为 Display List。普通文档的逐页输出共享语义准备及分页规则，使用轻量规划／验证后重放，逐页绘制和释放；它不先收集全部页面。按需块源的单次路径只打开一次；需要多遍重放时，由调用方提供可重开且内容稳定的同一快照。

`PdfSpoolBackend` 处理单页 PDF 对象与磁盘索引；普通 `PdfBackend` 的完整文档持有方式不因此改变。后端临时流缓冲、IR 保留、源块窗口和输出对象是不同的内存边界。详见[逐页输出](paged-document-api.md)与[流式重放](streaming-replay-api.md)。

### 语义格式与已有文件

- DOCX／HTML／Markdown 导出直接映射语义模型，目标阅读器完成布局，不消费 Display List。DOCX 专有结构位于不可变 `DocxSemanticDocument`／`DocxMathDocument`，通过块 ID 和 UTF-16 锚点连接正文；投影到通用模型时逐项报损。
- HTML 默认映射语义块；显式离线 CSS 布局模式在导入时解析级联，布局时求解尺寸及位置，文字通过语义插槽保留字段与链接。后端不解析 CSS。
- 已有 PDF 的原对象编辑、页面组合、渲染提取、选择性重建和语义恢复各有独立入口。原对象处理不调用布局引擎；恢复模型之后才由调用方选择重新排版。覆盖矩形或裁剪不等于删除源内容。
- Graphics API 可直接生成 Display List，用于精确绘制。它不要求语义文档，也不能据此推断已经具备语义导出或无障碍关系。

## 配置与内容继承

[DocumentTheme](themes-api.md) 提供语义样式，[ContentOptions](content-blocks-api.md) 保留逐属性声明意图。源内容按“主题／编译器默认 → 全局／请求默认 → XML document → 外到内 scope → 块显式声明”解析；不同入口的可用层级以对应 API 为准。黑色、空字体角色和 `false` 都是有效显式覆盖，不能按其数值猜测是否继承。

字体、颜色和段落默认值可用于原生语义块；格式及公式／HTML／表格开关用于源内容解析，不重新解释已经构建的块或旧 `p`／`span` 的普通绑定文本。Markdown 列表标记使用块的最终默认外观，首个正文运行的强调只作用于该运行。

公式出现身份由编译过程逐次绑定，不按公式字符串去重。视觉矢量、原生 OMML、IMAGE 和 TEXT 是不同目标表示；默认候选为空，只有显式配置才允许转换。TEXT 保留原 TeX、定界符、该次出现的完整 `InlineStyle` 及最终链接；预算、取消和 deadline 不能转为降级成功。详见[混合内容与跨格式公式](rich-content-api.md)。

相对权重的有限正数快照与分配由模型层 `RelativeWeights` 共用；表格、分栏及目标格式继续负责自己的固定尺寸、padding、单位取整和预算。共享纯规则不使适配器依赖布局内部运行对象。

## 状态与资源生命周期

| 状态／资源 | 所有者与存活范围 | 释放与共享边界 |
| --- | --- | --- |
| `Export4j` 配置 | 应用创建并关闭，可供独立请求复用 | 关闭内部拥有的 `FontFaceCache`；借用缓存及自定义 PDF 后端不由 exporter 关闭 |
| `Request` | 一次编写任务内的可变配置 | 不作为并发编辑对象；每次导出新建执行状态，XML 数据在执行时快照 |
| `XmlTemplate.Syntax` | 调用方显式保留的静态计划 | 不持有打开的 parser、字体或输出文档；不会成为隐式全局模板缓存 |
| 源内容 `Session` | 一次同步 render／编译 | 累计来源及解析预算，关闭后清状态；内建 `ContentImporter` 的前置失败不占 namespace，开始解析后的失败保留名称及已用预算，名称数量有上限 |
| `OperationFonts`、引擎及公式文字字体快照 | 当前导出操作 | 不跨操作缓存引擎；公式文字字体按需产生，操作结束释放本地引用 |
| `FontSource.bytes` | 持有该来源的调用方 | 创建时隔离编码字节并复用内容标识；来源本身一直持有私有快照，缓存过期不释放它 |
| `FontFaceCache` | exporter 或显式提供的应用缓存 | 按内容寻址，容量／源字节上限及空闲到期；共享不可变 face，同键冷载入有界合并，解析在锁外 |
| `PdfFontCache` | 默认池或调用方池 | 横向 TrueType 解析程序独占借用；clear／close 先移除空闲项，在用项归还后销毁，不共享跨文档 PDF 字体对象，不外推全部字体格式 |
| Layout IR、Display List 与返回结果 | 由保留结果的调用方持有 | 完整／逐页路径的保留量不同；已逃逸的字体、布局及结果不会被 TTL 强制失效 |
| 输入／输出流与路径 | 借用流由调用方关闭；内部打开的流由当前操作关闭 | 流失败后可能已有前缀；路径输出按原子提交契约保护旧目标 |

DOCX 声明接收端使用的字体族。PDF 使用同一物理字体测量和嵌入，缺字默认失败。无参 `Export4j.builder()` 在 starter 装配层从已知本地路径选择字体，再传给引擎。显式入口和核心引擎使用调用方提供的字体。

字体到期只释放缓存引用；调用方持有的 face、来源快照或活动解析租约仍可延长存活。代码不调用生产 `System.gc()`，也不承诺 TTL 到点时 RSS 下降。公式库自有字体及内部缓存另有生命周期。详见[字体缓存 API](font-cache-api.md)及其验收。

## 跨层契约

| 契约 | 共同规则 |
| --- | --- |
| 几何 | pt，左上角原点，X 向右／Y 向下；有限尺寸，行框与基线分开，目标坐标由后端转换 |
| 来源 | 使用原文 UTF-16；保留节点／owner、片段顺序、原始行列及锚点，不把字形数当字符数 |
| 测量与输出 | 共用字体、字形及 advance；已接受行保留实际宽度，静态后端不再次塑形 |
| 分页推进 | 每轮消耗内容或推进状态；不可容纳的整块明确失败，不无限生成空页 |
| 页区与字段 | 先预留页眉页脚再分页；物理页与显示页码分开，目录偏移确定后解析导航；动态字段占固定宽度，最终值不能改变分页 |
| 组件 | 核心定义 `Component`／`PageableComponent`，标准模块实现；普通组件整块移动，分页组件返回有序有界片段，试探测量须确定且可重复 |
| 语义附件 | `DocumentExtras` 保存交互，`AccessibilityTree` 保存逻辑顺序与最终命令绑定；命令变化须重建绑定，不能静默丢弃 |
| 预算 | 源预算与实际呈现预算分别累计，重复表头、片段、字段及链接按对应契约计费；有限预算不是恶意输入的进程沙箱 |
| 执行 | `ExecutionContext` 同步传播取消、deadline、进度和输出上限，作用域退出恢复或移除线程本地执行上下文；字体层通过中立 `Runnable` 接入，不反向依赖模型 |
| 失败与输出 | 参数、资源、权限、预算及 I/O 失败明确传播；Path 完成临时文件及最终检查后才原子替换，借用流不保证回滚 |
| 损失 | PREVIEW／ACTUAL 分开；ERROR／WARN／IGNORE 共享明确处理，IGNORE 仍报告实际损失，不能压制非法输入或执行失败 |
| 确定性 | 固定输入、字体、资源与配置验证布局；视觉一致、结构一致及文件字节一致分别判断 |

starter 和 `DocumentEngineProvider` 将 `ExecutionContext::checkpoint` 传入内建字体来源；同键等待每 25 ms 合作检查一次，受 JVM 调度影响。provider 还在业务回调开始前检查调用方当前的执行范围。取消等待者不取消共享解析或其他调用。旧加载接口没有自动执行上下文，自定义来源的默认重载只在调用前后检查；内部等待需要主动传递检查点。字体解析、调用方 I/O 和 getter 均不能被这一机制抢占。

## 扩展与维护入口

| 需要修改的行为 | 首先定位 | 进一步阅读 |
| --- | --- | --- |
| 编写结构、来源遍历、纯值规则 | `model`、`SemanticTree`、`RelativeWeights` | [开发规约](development.md)、[模型／布局 API](layout-api.md) |
| 字体来源、缓存、度量或塑形 | `font-core`、`text-core`，原生能力另看 `opentype` | [文字 API](text-api.md)、[字体缓存](font-cache-api.md)、[原生 OpenType](native-opentype-api.md) |
| 断行、分页、区域流和来源 | `layout-core`、`layout-model` | [布局](layout-api.md)、[表格](table-api.md)、[组件](components-api.md) |
| 共同语义准备、页区与导航编排 | `components` 的 `DocumentEngine`／`PreparedSemanticDocument` | [逐页输出](paged-document-api.md)、[字段](inline-fields-api.md) |
| 源解析、模板与外观继承 | `adapter-web`、`adapter-template`；公式表示看 `math` | [源内容块](content-blocks-api.md)、[XML](xml-template-api.md)、[混合内容](rich-content-api.md) |
| 统一入口配置与目标装配 | `starter` 的 `Export4j`／`OperationFonts` | [统一导出 API](easy-export-api.md) |
| 特定格式编码或已有文件操作 | 对应后端／适配器 | [能力矩阵](capabilities-api.md)、[转换](document-conversion-api.md) |
| 模板编辑与预览 | `export4j-designer`，保留源码及显式资源 | [设计器](designer.md) |
| API、运行制品和发布边界 | 根构建、BOM、验证调用方 | [API 兼容](api-compatibility.md)、[发布](releasing.md) |

核心架构改变时记录 ADR；公共调用契约写入对应 API，支持状态写入能力矩阵，实际执行结果写入验收，工程取舍写入项目笔记，避免在总览重复维护全部实现细则。

## 关键决策与证据

| 主题 | 决策及契约 |
| --- | --- |
| 分层、绘制、文字与分页 | [ADR-0001](adr/0001-layered-architecture.md)、[0003](adr/0003-font-text-layout.md)、[0004](adr/0004-paragraph-pagination.md)、[0006](adr/0006-components-and-pdf-interactions.md) |
| 最终字段与页区 | [ADR-0014](adr/0014-inline-semantic-fields.md)、[0015](adr/0015-semantic-table-fields.md)、[0049](adr/0049-semantic-page-regions-and-sections.md) |
| 语义适配、DOCX 与 CSS 布局 | [ADR-0050](adr/0050-semantic-web-exports.md)、[0103](adr/0103-typed-native-docx-semantics.md)、[0104](adr/0104-bounded-html-css-layout.md)、[0108](adr/0108-native-docx-math-and-example-typesetting.md) |
| 原对象 PDF 与显式恢复 | [ADR-0096](adr/0096-pdf-compatibility-paths.md)、[0097](adr/0097-explicit-pdf-editing-recovery-and-standards.md) |
| 递归布局、图形与原生字体 | [ADR-0099](adr/0099-variable-recursive-grid-and-contour-flow.md)、[0100](adr/0100-native-paints-and-offline-svg-resources.md)、[0101](adr/0101-explicit-native-opentype-and-cff-embedding.md) |
| 逐页、按需来源与重放 | [ADR-0102](adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md)、[0111](adr/0111-page-at-a-time-document-export.md)、[0112](adr/0112-incremental-block-source.md) |
| 数学、源继承与统一入口 | [ADR-0113](adr/0113-optional-bounded-math.md)、[0115](adr/0115-source-content-options.md)、[0117](adr/0117-single-source-math-representations.md)、[0118](adr/0118-configured-export-entry.md) |
| 字体缓存 | [ADR-0114](adr/0114-idle-expiring-font-caches.md)及[缓存 API](font-cache-api.md) |
| 共同规则、操作绑定及语法计划 | [工程决策](../.agents/notes/implemented/architecture/2026-11-19-reference-architecture-convergence.md)及[架构优化验收](verification/architecture-convergence.md) |

早期阶段背景保留在[立项文档](project-proposal.md)、ADR 和[阶段验收](verification/README.md)，不作为当前结构或未完成清单。
