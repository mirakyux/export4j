# 第三方许可记录

`dependencies.csv` 记录实际锁定的 Maven 坐标、用途、许可证来源、SHA-256 和证据路径。

`licenses/` 中的文件来自实际依赖 JAR。JSpecify 的 JAR 没有附带许可文件，使用其 v1.0.0 源仓库 LICENSE；JUnit BOM 使用 POM 许可声明和同版本 JUnit 原文。

这些文件是第三方原始法律声明，不属于项目自研代码，不改写或重新授权。PDFBox 和 FontBox 的 LICENSE 含内嵌组件及字体条款，应保留全文。

审查流程和采用结论见[依赖与资源管理](../docs/dependencies.md)。升级依赖时同步锁文件、CSV、原始声明和根目录 NOTICE。

示例与测试的字体独立登记在[字体资源](../assets/fonts/README.md)，同目录保存各自许可原文。它们不属于 Maven 坐标清单。

`native-artifacts.csv` 单独记录应用显式选择的原生 classifier；普通 OpenType 模块 POM 不默认携带平台二进制。已验收的 Windows x64 包含 LWJGL、HarfBuzz、libffi、libpng 和 zlib，其完整声明保存在 `licenses/`。libpng 1.6.55 与 zlib 1.3.2 来自原生包记录的 HarfBuzz 提交 `9f2f03173b7fee860cc00d999857d09fa4a362e2` 下 `subprojects/libpng.wrap` 和 `subprojects/zlib.wrap`；libpng 版本另经实际二进制字符串核对。其他 classifier 的存在不等于已经执行跨平台验收。
