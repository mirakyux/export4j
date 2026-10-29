# 依赖与资源管理

可选 `export4j-opentype` 内部依赖 LWJGL 3.4.3 的 `lwjgl`／`lwjgl-harfbuzz` 两个 Java 制品（BSD-3-Clause）。应用显式选择平台 native classifier，生产 POM 不自动传递平台二进制。Windows 实际内含 HarfBuzz 14.3.0，与绑定头版本分别核对；HarfBuzz、libffi、liburing 等内含许可原文随清单保存，见[原生制品记录](../third-party/native-artifacts.csv)。核心原有 JDK 路径不强制加载 JNI。

Linux／macOS x64 与 arm64 的 native JAR 校验值已按实际下载及 Central 发布摘要审查，进入严格依赖验证；此状态只证明固定制品被识别，不代表跨平台加载／渲染或阅读器测试通过。库测试与隔离调用方共享显式 classifier 选择逻辑，未知平台／架构拒绝。平台实际运行结果另见[阶段一百零五](verification/phase-105-publication-and-compatibility.md)。

SVG 独立渲染增加验证依赖 resvg-py 0.5.0（MIT）及其 resvg（MPL-2.0）实现；只在 Python 验证环境使用，不加入 Java 运行清单。字体检查使用 fontTools／uharfbuzz 的固定版本，字体来源、SHA-256 与原始许可独立登记于[字体记录](../assets/fonts/README.md)。

PDF 信任链、OCSP／CRL 与 PAdES 使用 Bouncy Castle 1.86：PDF 后端内部直接依赖 `bcpkix-jdk18on`，传递依赖同版本 `bcprov-jdk18on` 和 `bcutil-jdk18on`。三者采用 MIT，实际 JAR／POM 的 SHA-256、原始许可和来源均登记在依赖清单；公共 API 只使用项目类型与 JDK 密钥／证书字节，不暴露第三方类型。独立验签使用 pyHanko 0.33.0，固定在验证 requirements，仅用于验证环境，不进入 Java 制品。

外部 PDF 的显式源字体映射、不透明图形状态恢复和独立合成语料由项目自主实现，没有新增运行依赖、字体文件或第三方样本。固定 AFM 度量来自既有 PDFBox 3.0.8，验收复用已登记的 Liberation Sans 及原始许可。映射字体由调用方显式提供并遵守其嵌入权限，见[字体兼容](pdf-font-compatibility-api.md)和[阶段九十一](verification/phase-91-pdf-source-fonts-and-graphics-state.md)。

普通 Form XObject 的有界语义恢复同样复用既有 PDFBox 3.0.8；独立夹具由 pypdf 自主生成，使用已登记字体的确切字节及自定义源字宽，不增加依赖、字体或外部样本。来源与双渲染证据见[阶段九十二](verification/phase-92-pdf-form-recovery.md)。

PDF 普通输出的不可见逻辑锚点复用既有 PDFBox 3.0.8 JAR 内 `org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf`，与[字体记录](../assets/fonts/README.md)中已审查的 Regular 字节一致。只嵌入锚点使用的子集，不用作可见正文的字体替换；没有新增 Maven 坐标或第三方字体文件。沿用已有 PDFBox 完整 LICENSE／NOTICE 和 Liberation Sans OFL 声明。原始字体字节计入输出字体预算，归档仍使用显式配置的字体。

PDF 页级编辑复用既有 PDFBox／FontBox 3.0.8 和已登记 Liberation Sans。PDF 适配器显式声明 FontBox，用于读取嵌入权限及禁止子集标志；没有新增运行坐标、字体或第三方样本。样例图像由 Java 自行生成，源码和检查脚本采用 Apache-2.0。见[阶段四十九](verification/phase-49-pdf-page-editing.md)。

离线 HTML 导入在 Web 适配器内部采用 jsoup 1.21.2。仅调用 String 解析与有限选择器，不调用连接／加载接口；公共 API 不暴露其类型。无传递运行依赖，实际 class major 52，与 Java 21 兼容。MIT 原文来自实际 JAR，JAR／POM 与上游 SHA-1 及 Gradle SHA-256 对照；原始许可、归属和运行 SBOM 同步保留。

通用 Markdown 导入使用 commonmark-java 0.24.0 及同版本 GFM tables／strikethrough 扩展，作为 Web 适配器内部实现依赖，公共签名不暴露解析器类型。三者采用 BSD-2-Clause，原始许可从实际 JAR 提取并随分发保留；源码目标 Java 11、实际 class major 55，与 Java 21 基线兼容。Maven Central 的 7 个 POM／JAR 与上游 SHA-1 对照，并将实际 SHA-256 写入依赖验证元数据和制品清单。没有新增字体或第三方样本。

2026-10-21 新增自研 `export4j-adapter-web`，当时工程共 17 个模块、15 个库及 BOM。阶段四十三的生产代码仅依赖模型与 JDK，无新增第三方 Java 坐标；HTML／Markdown、合成样例、Java 生成蓝色 PNG 及验证脚本采用 Apache-2.0，不含外部素材。见[阶段四十三](verification/phase-43-web-exports.md)。当前模块另封装已登记的 CommonMark／jsoup，并为显式 CSS 布局复用自研组件和图形核心；此布局扩展没有增加第三方版本或资源。

2026-10-21 XML 固定页眉页脚、页区主题、合成报告及验证脚本由项目自主实现，采用 Apache-2.0。复用既有模型、JDK XML／文字路径和已登记的 Liberation Sans；没有新增模块、外部依赖、字体或第三方素材。见[阶段四十一](verification/phase-41-xml-page-regions.md)。

2026-10-16 组件字体作用域／固定富文本、独立列表标记和 XML 角色／颜色默认值均自主实现。没有新增模块、直接／传递依赖、字体或第三方资源；复用既有 JDK、共享文字路径和已登记 Liberation Sans。源码、合成样例、测试及检查脚本采用 Apache-2.0。见[阶段三十八](verification/phase-38-component-text.md)、[三十九](verification/phase-39-list-markers.md)及[四十](verification/phase-40-xml-text-defaults.md)。

2026-10-16 固定页区字体角色修复自主实现，无新增模块、直接／传递依赖、字体或第三方资源。样例、测试、隔离调用方及独立检查脚本采用 Apache-2.0，复用已登记的 Liberation Sans。见[阶段三十七](verification/phase-37-page-region-fonts.md)。

2026-10-16 XML 静态页面配置由项目自主实现，继续使用既有模型、JDK XML 和输出后端，没有新增直接／传递依赖、字体或第三方资源。`page-settings.xml`、合成报告数据、样例、测试与独立检查脚本采用 Apache-2.0，沿用已登记的 Liberation Sans。见[阶段三十六](verification/phase-36-xml-page-settings.md)。

本文件管理候选技术、实际依赖和非代码资源。当前已采用 Gradle 9.2.1、PDFBox / FontBox 3.0.8 和 JUnit 6.1.3；实际解析坐标由各模块 `gradle.lockfile` 固定。

## 候选技术

| 用途 | 候选项 | 计划使用位置 | 当前结论 |
| --- | --- | --- | --- |
| PDF 输出与处理 | [Apache PDFBox](https://pdfbox.apache.org/) | PDF 后端及独立 PDF 适配器内部 | 已采用 3.0.8；静态页处理的支持边界见 [PDF API](pdf-adapter-api.md) |
| DOCX 导入导出 | JDK ZIP／安全 XML | DOCX 适配器 | 当前使用 JDK 实现语义子集，未新增 Maven 依赖 |
| 自动化测试 | [JUnit](https://junit.org/) | 测试代码 | 已采用 6.1.3，Java 21 测试通过 |
| 字体解析 | [Apache FontBox](https://pdfbox.apache.org/) | 字体层内部 | 已采用 3.0.8，与 pdfbox-io 一起封装，验证静态 TrueType／CFF 及集合成员 |
| 复杂塑形 | JDK TextMeasurer／GlyphVector；可选 LWJGL／HarfBuzz | 核心静态字体与可选原生 OpenType 模块 | JDK 路径保持；原生方向、可变／彩色字体及特性见[原生指南](native-opentype-api.md) |
| 行内图片 | JDK ImageIO／GraphicAttribute | 模型内有界资源验证、文本测量、绘制解码 | 仅显式静态 PNG／JPEG；AWT 不进入公共签名，无新增 Maven 依赖 |
| 构建工具 | [Gradle](https://gradle.org/) | 构建与内置插件 | 已采用 9.2.1，Java 21 构建通过 |

项目名称或宽松许可证名称不能代替具体制品及传递依赖审查。

2026-10-15 合并格续页批次由项目自主实现，未新增依赖或第三方资源。

同日默认样式贯通批次复用现有模型、布局和 JDK，未新增依赖、字体或外部素材。证据见[阶段十九](verification/phase-19-style-defaults.md)。

2026-10-16 声明式模板批次新增自研 `export4j-adapter-template`，项目共 15 个模块。生产仅依赖模型模块及 JDK `java.xml` StAX，没有新增外部 Maven 坐标。模板与虚构巡检数据由项目自编，蓝色 PNG 由样例代码生成，均采用 Apache-2.0；继续使用已登记字体。测试用 JDK loopback HTTP 服务计数，拒绝 XXE／DTD／XInclude 后须为零请求。BOM、SBOM、锁文件和隔离调用方纳入[阶段二十一](verification/phase-21-xml-templates.md)。

## 采用流程

2026-10-16 多脚本文字与适配器批次新增自研 `export4j-adapter-pdf`，项目共 16 个模块。它 `api` 依赖模型，`implementation` 依赖已登记的 PDFBox 3.0.8；没有新增第三方 Maven 坐标。Markdown 及 XML 字段／外链继续在模板适配器内用 JDK 实现。新增未修改的 Noto Sans Devanagari／Thai 静态字体来自与 Noto Naskh Arabic 相同的固定官方提交，来源、SHA-256 与共享 OFL 原文见[字体记录](../assets/fonts/README.md)。示例 JAR 携带全部字体及原始许可，核心库不携带示例字体。语料、XML、Markdown、合成数据和图片均由项目自编，未复制竞争实现。

1. 说明用途、使用模块、必要性和公共 API 隔离方式。
2. 选择固定版本，验证 Java 兼容性、功能覆盖与已知安全问题。
3. 解析直接和传递依赖，记录完整坐标、实际版本、来源和依赖路径。
4. 审查每项许可证、版权、NOTICE、专利条款与分发要求。
5. 记录结论及证据，再更新构建配置与版本目录。
6. 在升级和发布时复核依赖图与打包产物，不能沿用旧版本结论。

运行依赖、构建插件、测试工具、原生库和被打包资源都属于审查范围。必要时使用依赖锁定与校验元数据保证解析结果可追踪。

## 正式依赖记录

完整清单见[依赖坐标与证据](../third-party/dependencies.csv)，共 24 个 Maven 坐标，包含 BOM 与测试依赖；平台原生分类制品另见 `third-party/native-artifacts.csv`。原始许可证及归属文件保存在[许可证目录](../third-party/licenses)。当前未引入外部 Gradle 插件。

| 依赖 | 版本 | 范围 | 许可依据与处理 |
| --- | --- | --- | --- |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | 运行 | Apache-2.0 及内嵌资源许可；保留完整 LICENSE / NOTICE |
| `org.apache.pdfbox:fontbox` | 3.0.8 | 字体模块直接运行、PDF 后端／适配器传递运行 | Apache-2.0 及内嵌资源许可；保留完整 LICENSE / NOTICE |
| `org.apache.pdfbox:pdfbox-io` | 3.0.8 | 字体模块直接运行、PDF 后端／适配器传递运行 | Apache-2.0；保留 LICENSE / NOTICE |
| `commons-logging:commons-logging` | 1.4.0 | 传递运行 | Apache-2.0；保留 LICENSE / NOTICE |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | PDF 后端内部运行 | MIT；保留实际 JAR 原始许可 |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | PDF 后端传递运行 | MIT；保留实际 JAR 原始许可 |
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | PDF 后端传递运行 | MIT；保留实际 JAR 原始许可 |
| `org.jsoup:jsoup` | 1.21.2 | Web 适配器内部运行 | MIT；保留实际 JAR 原始许可 |
| `org.commonmark:commonmark` | 0.24.0 | Web 适配器内部运行 | BSD-2-Clause；保留实际 JAR 原始 LICENSE.txt |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.24.0 | Web 适配器内部运行 | BSD-2-Clause；保留实际 JAR 原始 LICENSE.txt |
| `org.commonmark:commonmark-ext-gfm-strikethrough` | 0.24.0 | Web 适配器内部运行 | BSD-2-Clause；保留实际 JAR 原始 LICENSE.txt |
| `org.junit:junit-bom` | 6.1.3 | 测试版本约束 | EPL-2.0；POM 与同版本 JUnit 许可文件 |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | 测试 | EPL-2.0；保留 LICENSE.md |
| `org.junit.jupiter:junit-jupiter-api` | 6.1.3 | 传递测试 | EPL-2.0；保留 LICENSE.md |
| `org.junit.jupiter:junit-jupiter-engine` | 6.1.3 | 传递测试 | EPL-2.0；保留 LICENSE.md |
| `org.junit.jupiter:junit-jupiter-params` | 6.1.3 | 传递测试 | EPL-2.0；保留 LICENSE.md |
| `org.junit.platform:junit-platform-commons` | 6.1.3 | 传递测试 | EPL-2.0；保留 LICENSE.md |
| `org.junit.platform:junit-platform-engine` | 6.1.3 | 传递测试 | EPL-2.0；保留 LICENSE.md |
| `org.junit.platform:junit-platform-launcher` | 6.1.3 | 测试运行 | EPL-2.0；保留 LICENSE.md |
| `org.opentest4j:opentest4j` | 1.3.0 | 传递测试 | Apache-2.0；保留 LICENSE |
| `org.apiguardian:apiguardian-api` | 1.1.2 | 传递测试编译 | Apache-2.0；保留 LICENSE |
| `org.jspecify:jspecify` | 1.0.0 | 传递测试编译 | Apache-2.0；原始 JAR 无许可文件，保留 v1.0.0 源仓库 LICENSE |

初始化审查日期为 2026-10-14，由本次项目初始化工作核对 POM、实际 JAR 与其中的声明。CSV 记录来源、范围、制品 SHA-256 和许可文件路径；BOM 的 SHA-256 对应 POM，其余对应 JAR。

同日阶段二将已有 FontBox 和 pdfbox-io 改为字体模块的直接内部依赖，未增加 Maven 坐标。版本和原有许可依据保持一致。模块间 `api` 仅传递项目自有类型，第三方解析器使用 `implementation` 隔离。锁文件及 `gradle/verification-metadata.xml` 固定解析与 SHA-256 校验。

阶段三新增两个项目内布局模块，未新增 Maven 坐标。布局核心只通过字体、文本和绘制模块复用基础设施，不引入 PDF 后端生产依赖。

基础表格继续使用这 9 个模块和已有字体，未新增 Maven 坐标、原生组件、字体或外部资源；列宽、行高与整行分页由项目自研实现。

组件首版新增 `export4j-components`，总计 10 个项目模块，继续使用原有 15 个 Maven 坐标。目录、页区和定位由项目实现；PDFBox 在后端内部建立书签、批注及 AcroForm。基础示例表单使用未嵌入的 ASCII Helvetica；进阶表单完整嵌入调用方显式提供的 Unicode 字体。PNG／JPEG 图片由代码生成。

进阶能力未新增 Java 运行依赖：PNG／JPEG 使用 JDK ImageIO，SVG 子集使用 JDK XML 解析器。新增三个 Liberation Sans 2.1.5 变体，来源、OFL 原文及哈希见[字体记录](../assets/fonts/README.md)。干净依赖环境补齐现有 JUnit BOM 5.14.3 的 `.module` 和 6.1.3 的 `.pom` 校验项，SHA-256 与 Maven Central 核对，未更改依赖版本。

当前采用结论：上述运行依赖按原始许可及声明分发；JUnit 仅用于测试，不进入生产运行依赖。PDFBox / FontBox 的许可证包含 BSD 类条款、字体 OFL 和其他资源声明，不能仅按 Apache-2.0 标签处理。完整原文随制品保留，未修改、单独销售或重命名其中的字体资源。

生产批次新增 SVG／Raster、DOCX 与 BOM，总计 14 个项目模块，外部 Maven 坐标仍为 15 个。JDK java.desktop 承载 OpenType／Java2D，ZIP／XML 承载 DOCX，不新增生产依赖。新增 Noto Naskh Arabic 静态字体来自固定官方提交，SIL OFL 1.1 原文及 SHA-256 已登记于[字体记录](../assets/fonts/README.md)，示例 JAR 同时携带字体与许可。

公共复杂文本、模板与受控导出批次仍使用上述模块与 Maven 坐标。文本模块内部增加对模型模块的单向依赖，以传递执行检查点。新增 Source Sans 3.052R 静态 CFF 字体及 OFL 原文，来源、字节数、哈希与保留字体名见[字体记录](../assets/fonts/README.md)。核心库继续不携带示例字体。

以后新增或升级的每项记录应包含：

- Maven 坐标或其他明确标识、精确版本和来源。
- 使用模块、运行／构建／测试用途、直接或传递关系。
- 许可证标识、许可证原文或官方来源、审查证据。
- 版权与 NOTICE 要求、是否随制品分发、需履行的义务。
- Java 兼容性和关键能力验证结果。
- 审查结论、审查日期和负责确认的维护者。

没有完成审查的条目不得标为“通过”。发布时应把实际解析的全部依赖与记录核对，并由维护者确认目标分发方式下的义务。

## 构建与 CI 工具

Gradle Wrapper 由 Gradle 9.2.1 官方工具生成，自带 Apache-2.0 声明。分发 ZIP 的 SHA-256 已写入 Wrapper 配置，Wrapper JAR 也已与官方校验值核对：

~~~text
distribution: 72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f
wrapper jar: 423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9
~~~

保留的 CI 草案使用 `actions/checkout` 与 `actions/setup-java`，按完整提交 SHA 固定，源项目许可证为 MIT。它们不进入自研库或发行包。工作流仅支持手动触发，未覆盖完整验收流程，也未在托管平台执行验证。

独立 PDF 验证使用 Python 3.11+、[pypdf 6.10.0](https://pypi.org/project/pypdf/6.10.0/)（BSD-3-Clause）、[pypdfium2 5.13.0](https://pypi.org/project/pypdfium2/5.13.0/)（Apache-2.0 / BSD-3-Clause，随 wheel 保留 PDFium 及第三方许可）和 [Pillow 12.3.0](https://pypi.org/project/Pillow/12.3.0/)（MIT-CMU，wheel 另带所含第三方库声明）。版本固定于 `scripts/requirements-verification.txt`。外部 Poppler 为 GPL-2.0-or-later 验证工具，由环境或系统包管理器安装。这些工具不进入 Java 依赖、JAR 或示例发行包；本地验证版本见[进阶验收](verification/phase-3-advanced.md)。如未来打包工具环境，须连同 wheel 和原生依赖逐项保留许可。

独立 Markdown 验证使用 [markdown-it-py 4.0.0](https://pypi.org/project/markdown-it-py/4.0.0/) 及其依赖 [mdurl 0.1.2](https://pypi.org/project/mdurl/0.1.2/)，均为 MIT，版本固定于验证 requirements，原始许可随 wheel 保留在隔离环境。二者均支持当前 Python 3.11+ 验证基线，未进入 Java JAR、Maven POM 或运行 SBOM；若将验证环境打包分发须保留版权及许可全文。HTML 独立结构检查使用 Python 标准库 HTMLParser。

独立 DOCX 验证另用 [python-docx 1.2.0](https://pypi.org/project/python-docx/1.2.0/)（MIT），其 lxml 及底层 XML 库许可保留在验证环境，未进入 Java 制品。版本同样固定于验证 requirements；独立解析不代表 Word 视觉兼容。

AES-256 独立验证使用 [cryptography 50.0.2](https://pypi.org/project/cryptography/50.0.2/)（Apache-2.0 OR BSD-3-Clause，原生依赖声明随 wheel 保留），版本固定于验证 requirements。PDF/A-2b 使用 [veraPDF 1.30.3](https://software.verapdf.org/releases/1.30/verapdf-greenfield-1.30.3-installer.zip) 官方 Greenfield CLI，按 [安装说明](https://docs.verapdf.org/install/)仅安装至忽略的 `build/verification/verapdf`；许可为 GPL-3.0-or-later／MPL-2.0。两者均为验证工具，不进入 Java 制品。

detached CMS 样例及独立验签使用本机 Git 附带的 OpenSSL（Apache-2.0；实际版本见[验收记录](verification/phase-5-capabilities.md)）。测试私钥和自签证书仅在忽略的 `build/verification/signing` 中生成，不打包、不提供信任背书。示例中的签名回调由调用方显式配置，本库不依赖 OpenSSL、不管理私钥。

## 字体、图片和样本文档

资源不能仅凭可下载或可打开就视为可再分发。新增资源必须记录原始来源、作者或权利人、版本或哈希、许可证、用途和分发限制。

当前示例图片由项目代码生成。精确绘制示例的 PDF 标准字体不嵌入；文本示例及字体、文本、PDF 测试使用 `assets/fonts` 内的固定字体，详见[字体来源与哈希](../assets/fonts/README.md)。

2026-10-16 两层块格／XML 嵌套格由项目自主实现，仍为 16 模块。合成报告、XML 与 BlockCellsExample 生成的双色 PNG 采用 Apache-2.0，沿用已登记字体与验证工具，无新增外部依赖／资源。模板适配器仅在测试配置依赖 DOCX 适配器，以验证同一语义树的标准输出；生产仍只依赖模型与 JDK。

行内图像样例的透明蓝色 PNG 与橙色 JPEG 由 `InlineImagesExample.resource(...)` 生成，原始编码写入 DOCX，适用项目 Apache-2.0。没有新增外部图片、字体或样本文档。几何、媒体和独立渲染证据见[行内图像验收](verification/phase-6-inline-images.md)。

多浮动样例的 RGB 棋盘渐变图片由 `MultiFloatExample.image()` 生成，文本框／矩形由项目组件绘制，适用项目 Apache-2.0。沿用上述显式字体与独立验证工具，没有新增模块或运行依赖，见[多浮动验收](verification/phase-7-multi-floats.md)。

连续分栏样例由 `ContinuousColumnsExample` 生成，沿用 Liberation Sans、Noto Naskh Arabic 和霞鹜文楷 TC。独立脚本用 Python 标准库读取显式字体表、pypdf 读取最终 PDF 文字位置，并复用上述双渲染器／Pillow 工具；没有新增模块、运行／验证依赖或外部资源，见[连续分栏验收](verification/phase-8-continuous-columns.md)。

混合分栏样例由 `MixedColumnsExample` 生成，沿用上述字体；图片像素由项目代码生成，适用 Apache-2.0。独立脚本复用已有最终 PDF 矩阵／图片检查与渲染工具，没有新增资源或依赖，见[混合分栏验收](verification/phase-9-mixed-columns.md)。

Liberation Sans 从 PDFBox 3.0.8 JAR 原样提取；霞鹜文楷 TC 和站酷小薇体来自固定 Google Fonts 提交。三者采用 SIL OFL 1.1，保留各自版权、保留字体名及许可原文，未经修改、重命名或单独销售。它们随示例 JAR 分发，核心库不携带示例字体。运行时不自动下载资源。

Liberation Sans 粗体、斜体与粗斜体来自官方 2.1.5 发布包，未经修改。其独立许可文件随字体进入示例 JAR，根 NOTICE 指向两份原始 Liberation 许可记录。

当前示例和混排测试选用 Liberation Sans 与霞鹜文楷 TC。站酷小薇体保留用于阶段二历史样本复现；原字体“回”字的实心轮廓经独立渲染复现，阶段三改用新字体，不修改原字体字节。

字体额外核查嵌入、子集化、修改与再分发条件。样本文档使用自建或明确授权的脱敏内容，不引入客户数据、凭证和个人信息。

不能公开分发的本地测试资源不得进入仓库或发布包。可公开复现的验证应提供有明确许可的替代样本。

## LICENSE、NOTICE 与 SBOM

根目录 [LICENSE](../LICENSE) 使用 Apache 官方全文，适用于项目自研部分。第三方代码与资源保留原有声明。

根目录 [NOTICE](../NOTICE) 汇集当前运行依赖的原始归属声明。构建出的 JAR 携带自研 LICENSE、NOTICE 和第三方许可原文；示例发行包同时携带未修改的依赖 JAR。

当前 `sbom` 生成 CycloneDX 1.6 库运行清单，目标集合为 16 个自研库及 13 个第三方 Java 制品，携带实际 JAR 的 SHA-256；字段与字体资源另按本文件登记。每批须重新生成并由生产验证器核对最终制品，不能沿用前一批哈希。候选清单不能替代 SBOM。

XML 模板源码与普通 ZIP 打包复用既有适配器及 JDK ZIP／UTF-8 API，没有增加模块或第三方依赖。示例继续使用已登记的 Liberation Sans Regular 字节，原版权及 OFL 文本随模板 ZIP 的 `licenses/` 条目保存；图片由自编样例生成。独立输入由 Python 标准库 ZIP 与既有 Pillow 生成，二者不进入 Java 运行清单。

行内字段与注释锚点复用既有 JDK 物理字体塑形、布局和绘制模块，没有增加第三方依赖。示例图片由项目代码生成，复用已登记字体；独立验证使用现有 Python、Poppler 和 PDFium 工具。

常用编写与压力样例 `AuthoringExample`、`ReportStressExample` 沿用上述显式字体；棋盘图片由项目代码生成，适用 Apache-2.0。最终 CID／TJ 几何检查与 Windows 进程采样只使用已登记工具和 PowerShell，没有新增模块、第三方依赖或外部素材。

密集表单样例 `AdrFormExample` 沿用霞鹜文楷 TC 和 Liberation Sans；静态勾选由项目代码绘制。结构参考材料不作为分发资源，原始 PDF、嵌入字体和真实病例数据不进入仓库或制品。公开样例全部使用虚构病例，独立验证复用已登记工具，无新增依赖。范围见[表单实测](verification/adr-form-probe.md)。

通用静态表单与 `InlineGraphic` 采用项目自主设计的矢量几何、共享 JDK 塑形和已有绘制路径。`StaticFormsExample` 使用已登记的 Liberation Sans 与 Noto Naskh Arabic，ADR 继续使用原显式字体。新增测试图片由 JDK 生成，适用 Apache-2.0；没有新增第三方依赖或外部素材。验收见[阶段十六](verification/phase-16-static-forms.md)。

组件语义插槽与 `SemanticTable` 自主实现，复用已有 Table 测量、字体和后端。新增样例使用已登记字体与 JDK 生成的 PNG，不引入外部素材。设计与边界见 [ADR-0021](adr/0021-component-semantic-insertions.md)。

语义表格区域续排继续复用现有列宽、文字塑形及完整行拆分，未引入新依赖或外部素材。三个新合成示例沿用 Liberation Sans 和 JDK 生成图标。片段身份与字体区域委托为自主实现，见 [ADR-0022](adr/0022-semantic-table-region-flow.md)。

长合并格语义续排复用核心表格测量与自主网格分配器。`GridSemanticFlowExample` 使用全虚构文字、已登记的 Liberation Sans 正常／真实粗体和代码生成的蓝色 PNG，适用 Apache-2.0；无新增外部图片、字体、依赖或竞争库代码。来源与缓存表头校验见 [ADR-0024](adr/0024-grid-semantic-cell-flow.md)。

单向长表与语义列／行合并沿用既有 JDK、字体、PDFBox／FontBox 和验证工具，没有增加运行依赖。StreamingTableExample／CapacityExample 的数据源全为合成记录；ColumnSpanExample／RowSpanExample 的 PNG 由项目代码生成，适用 Apache-2.0。二维覆盖、分页、标准 OOXML 映射和测试自主编写。

阶段四十二未增加依赖。综合页区样例的标志 PNG 由 XmlSectionsExample 内的自研 Java2D 几何即时生成，沿用代码 Apache-2.0，用于离线资源、媒体关系及重复页区验证，不使用外部图像或字体扫描。
