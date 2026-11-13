# 第三方许可记录

`dependencies.csv` 记录实际锁定的 Maven 坐标、用途、许可证来源、SHA-256 和证据路径。

`licenses/` 中的文件来自实际依赖 JAR。JSpecify 的 JAR 没有附带许可文件，使用其 v1.0.0 源仓库 LICENSE；JUnit BOM 使用 POM 许可声明和同版本 JUnit 原文。

这些文件是第三方原始法律声明，不属于项目自研代码，不改写或重新授权。PDFBox 和 FontBox 的 LICENSE 含内嵌组件及字体条款，应保留全文。

审查流程和采用结论见[依赖与资源管理](../docs/dependencies.md)。升级依赖时同步锁文件、CSV、原始声明和根目录 NOTICE。

示例与测试的字体独立登记在[字体资源](../assets/fonts/README.md)，同目录保存各自许可原文。它们不属于 Maven 坐标清单。

可选 `export4j-math` 模块与数学示例依赖 JLaTeXMath 1.0.7，采用 GPL-2.0-or-later 及其原始 linking exception。该依赖仅进入选择数学模块的运行依赖及库运行 SBOM，核心模块不依赖它；BOM 约束数学模块的自研版本，不管理第三方库。SBOM 使用完整许可名称，避免将原始例外误记为另一个 SPDX 例外。原始库声明、GPL 全文与内嵌字体条款保存在 `licenses/jlatexmath-1.0.7/`。数学模块二进制 JAR 在 `META-INF/third-party/source/` 携带未经修改、包含字体资源的上游 sources JAR，以及 `jlatexmath-source-build/` 中三个原始 Maven POM；示例分发包也携带相同源码材料。Greek／Cyrillic 文本扩展字体包未引入。可选公式模块显式加载主 JAR 的五款 Computer Modern 字体（含文字斜体 `latin/optional/jlm_cmti10.ttf`）；原 ManuWeave 示例仍使用原四款字体。

常用文档的简历配图独立登记在[图片资源](../examples/src/main/resources/templates/common/assets/README.md)，保留 Pexels 作品、作者及许可链接；该照片不采用项目的 Apache-2.0。它不是 Java 依赖，未列入 Maven 坐标清单。

`native-artifacts.csv` 单独记录应用显式选择的原生 classifier；普通 OpenType 模块 POM 不默认携带平台二进制。已验收的 Windows x64 包含 LWJGL、HarfBuzz、libffi、libpng 和 zlib，其完整声明保存在 `licenses/`。libpng 1.6.55 与 zlib 1.3.2 来自原生包记录的 HarfBuzz 提交 `9f2f03173b7fee860cc00d999857d09fa4a362e2` 下 `subprojects/libpng.wrap` 和 `subprojects/zlib.wrap`；libpng 版本另经实际二进制字符串核对。其他 classifier 的存在不等于已经执行跨平台验收。

Linux／macOS x64 与 arm64 的八个 JAR 已实际下载并与 Central 发布 SHA-1 对照，SHA-256 同步进入 Gradle 严格校验与原生制品清单；二进制中的 libpng 1.6.55／zlib 1.3.2 字符串和 libffi 符号另作核对，Linux 内含声明保留 liburing 许可。复用保存的 HarfBuzz 原始版权声明不表示已经在这些平台测得相同运行版本。完整平台执行状态见[阶段一百零五](../docs/verification/phase-105-publication-and-compatibility.md)。
