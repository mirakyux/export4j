# 版本与发布规范

当前尚无正式发布版本。已提供本地 Maven 制品、BOM、SBOM 与独立调用方验证。当前坐标为 `com.mirakyux.export4j:export4j-*:0.1.0-SNAPSHOT`。

## 本地验证流程

~~~powershell
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities
powershell -File scripts/verify_local_consumer.ps1
python scripts/verify_production.py
python scripts/verify_capabilities.py
~~~

`publishLocalBundle` 发布 14 个库和 BOM 到 `build/repository`，不访问远程发布服务、不写用户 Maven 仓库。库携带源码、Javadoc、POM、Gradle 元数据和许可。SNAPSHOT 使用 Maven 时间戳制品，验证脚本通过 `maven-metadata.xml` 解析实际文件，不假设固定文件名。

`sbom` 写入 `build/reports/sbom.cdx.json`，CycloneDX 1.6 库运行清单包含 14 个自研库及 4 个第三方制品的 SHA-256。第三方许可白名单遇到新增坐标会失败，须先审查；字体、JDK、测试工具和构建工具不在该运行清单中，另按依赖与资源记录核对。

独立 `verification/consumer` 工程仅使用 Maven 坐标和 BOM，对项目 group 的解析限定本地隔离仓库，编译并实际输出 PDF／DOCX／SVG／PNG。脚本用独立 Gradle home，刷新 SNAPSHOT，证明本地制品可消费；这不是 Maven Central、签名或远程 CI 验证。

脚本每次使用独立消费者构建目录，避免之前打开的输出文件阻塞清理。模板、受控导出、CFF 与 PDF 安全／归档／签名的附加验收见[能力记录](verification/phase-5-capabilities.md)；省略外部验证器参数时不包含归档合规或验签结论。

## 版本与兼容性

版本采用[语义化版本](https://semver.org/lang/zh-CN/)。公共 API 未稳定时使用 `0.x`，并在每次发布说明中显式列出不兼容变化。

达到 `1.0.0` 后：

- 主版本用于公共 API 或已声明契约的不兼容变更。
- 次版本用于向后兼容的能力新增。
- 修订版本用于向后兼容的问题修复。

预发布版本使用 `-alpha.N`、`-beta.N` 或 `-rc.N`。开发版本不能冒充已发布稳定版本，已发布制品不得覆盖。

兼容性除 Java 签名外，还包括单位、坐标、资源关闭、错误行为、诊断代码和已声明的格式支持。布局修复可能改变视觉结果，应记录变化及相关基线。

同一发布批次的项目模块统一版本，由 BOM 对齐。第三方依赖版本独立管理。

## 发布前检查

1. 固定发布提交，确认变更范围、工作区和版本号。
2. 在干净环境中使用 Gradle Wrapper 完成已配置的构建和测试。
3. 运行对应阶段的结构、独立工具及视觉验证，记录环境与结果。
4. 核对公共 API 差异、不兼容行为和迁移说明。
5. 验证依赖清单、许可证、资源许可、必要 NOTICE 与 SBOM。
6. 检查二进制、源码、Javadoc、POM 和 BOM 内容及依赖范围。
7. 在干净的调用方项目中解析制品并运行示例。
8. 检查 README、发布说明、安全支持范围与私密报告渠道。
9. 确认发布目标、凭证、签名和校验流程；获得相应发布授权后发布。

Maven Central 是规划发布渠道，命名空间与凭证需要实际配置。凭证和签名私钥不得写入仓库、日志或文档。

## 发布说明

发布说明至少包含版本、发布日期、主要行为变化、不兼容改动、依赖升级、已知限制和验证摘要。日期及支持范围以实际发布记录为准。

未完成的能力应归入后续计划，不能写成当前版本特性。

## 发布后验证

从目标仓库重新解析已发布制品，核对版本、依赖、签名和校验信息，运行最小示例。

构建通过、上传成功和调用方可用属于不同验证结果。发布失败时保留证据，按目标仓库规则处理，不覆盖已存在的正式版本。
