# 版本与发布规范

当前尚无正式发布版本。已提供本地 Maven 制品、BOM、SBOM、API 基线检查、显式 PGP 签名和独立调用方验证入口。当前默认坐标为 `com.mirakyux.export4j:export4j-*:0.1.0-SNAPSHOT`；正式远端分发与这些本地能力分别验收。

## 本地验证流程

~~~powershell
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities
powershell -File scripts/verify_local_consumer.ps1
python scripts/verify_production.py
python scripts/verify_capabilities.py
~~~

`publishLocalBundle` 发布 16 个库和 BOM 到 `build/repository`，不访问远程发布服务、不写用户 Maven 仓库。库携带源码、Javadoc、POM、Gradle 元数据和许可。SNAPSHOT 使用 Maven 时间戳制品，验证脚本通过 `maven-metadata.xml` 解析实际文件，不假设固定文件名。

`sbom` 写入 `build/reports/sbom.cdx.json`，CycloneDX 1.6 库运行清单包含 16 个自研库及 13 个第三方 Java 制品的 SHA-256；按实际坐标记录 Apache-2.0、BSD-2-Clause、BSD-3-Clause 和 MIT。第三方许可白名单遇到新增坐标会失败，须先审查。可选 OpenType 的 native classifier 不传递到生产 POM；应用显式选择，原生制品／内含许可另见 `third-party/native-artifacts.csv`。字体、JDK、测试工具和构建工具也单独登记，不混入 Java JAR 清单。

独立 `verification/consumer` 工程仅使用 Maven 坐标和 BOM，对项目 group 的解析限定本地隔离仓库，编译并实际输出 PDF／DOCX／SVG／PNG／HTML／Markdown。脚本用独立 Gradle home，刷新 SNAPSHOT，证明本地制品可消费；这不是 Maven Central、签名或远程 CI 验证。

根 `check` 包含 `apiCheck`。固定开发期基线、差异规则和行为兼容边界见 [Java API 与版本兼容](api-compatibility.md)。Java 21 的 Windows／Linux／macOS 构建及临时密钥签名分发已在远端通过，准确提交和运行记录见[阶段一百零五](verification/phase-105-publication-and-compatibility.md#远端-ci-验收)。这些检查使用本地构建的 Maven 仓库，不替代正式身份、Central 上传或真实远端仓库消费。

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

## 签名与可审查分发包

`-Pexport4jVersion=<明确版本>` 覆盖整个项目及 BOM 版本，`-Pexport4jRepositoryDirectory=<本地目录>` 指定隔离仓库；省略时继续使用开发 SNAPSHOT 和 `build/repository`。此参数只选择本地目录，不提供远程发布目标。独立消费者接受同名版本／仓库参数，默认仍限定本地项目 group。

`-Pexport4jSign=true` 显式启用 Gradle PGP 签名，从外部进程环境读取 `EXPORT4J_SIGNING_KEY` 与可选的 `EXPORT4J_SIGNING_PASSWORD`，使用内存密钥。私钥、密码和 token 不写入 Gradle 属性文件、脚本、提交或日志。未启用签名时不要求密钥；启用而缺少密钥时明确失败。

```powershell
.\gradlew.bat -Pexport4jVersion=<明确的非SNAPSHOT版本> -Pexport4jSign=true -Pexport4jRepositoryDirectory=<本地暂存目录> publishSignedBundle
python scripts/prepare_maven_bundle.py --repository <本地暂存目录> --version <相同版本> --public-key <公钥文件> --output build/verification/distribution
```

命令中占位符须替换后执行；Linux／macOS 使用 `./gradlew`。`publishSignedBundle` 只把 16 个库和 BOM 暂存到本地，不连接 Central。完整 POM 包含项目、SCM、维护者和许可；二进制、源码、Javadoc、POM 及 Gradle 元数据均须有有效签名。准备脚本使用独立 GnuPG 验签并验证篡改拒绝，核对实际 JAR 与 SBOM、许可及坐标，生成标准目录结构 ZIP 和 MD5／SHA-1／SHA-256／SHA-512 校验文件。调用前须安装 GnuPG，并准备可验证的公开密钥。

`scripts/verify_signed_distribution.py` 可用临时密钥和 `0.0.0-verification...` 标记版本演练整个流程，随后以独立复制的消费者编译并生成输出。消费者实际解析的每个自研 JAR 必须与该批签名仓库具有相同 SHA-256；同坐标、同版本不能替代准确字节核对。构建输入清单同时绑定生产源码、构建／验证脚本、消费者源码及资源，签名与消费期间变化即失败。此测试身份不作为正式签名身份；脚本要求全新忽略目录，私钥仅存在临时 keyring 和签名进程环境，结束后清理。演练包可审查，不自动上传。

准备脚本、临时密钥、正式密钥、ZIP、Central 上传和真实远端消费必须分别记录结果。当前没有配置自动上传或自动正式发布任务。

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
