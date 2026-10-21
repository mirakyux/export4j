# 第三方许可记录

`dependencies.csv` 记录实际锁定的 15 个 Maven 坐标、用途、许可证来源、SHA-256 和证据路径。

`licenses/` 中的文件来自实际依赖 JAR。JSpecify 的 JAR 没有附带许可文件，使用其 v1.0.0 源仓库 LICENSE；JUnit BOM 使用 POM 许可声明和同版本 JUnit 原文。

这些文件是第三方原始法律声明，不属于项目自研代码，不改写或重新授权。PDFBox 和 FontBox 的 LICENSE 含内嵌组件及字体条款，应保留全文。

审查流程和采用结论见[依赖与资源管理](../docs/dependencies.md)。升级依赖时同步锁文件、CSV、原始声明和根目录 NOTICE。

示例与测试的字体独立登记在[字体资源](../assets/fonts/README.md)，同目录保存各自 OFL 原文。它们不属于 Maven 坐标清单。
