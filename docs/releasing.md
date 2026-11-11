# 版本与发布规范

`0.1.0-rc.2` 已发布到 Maven Central，当前尚无稳定版。开发默认坐标仍为 `com.mirakyux.export4j:export4j-*:0.1.0-SNAPSHOT`。本地构建、签名、Central 发布和远端消费分别验收。

已发布候选的变化及已知限制见 [0.1.0-rc.2 说明](releases/0.1.0-rc.2.md)。工作流会把 `docs/releases/<版本>.md` 中的说明附入对应 GitHub Release；具体发布状态以 Central 及工作流结果为准。

## 已发布候选记录

`0.1.0-rc.2` 的[发布工作流](https://github.com/mirakyux/export4j/actions/runs/42262570964)已成功完成，`release` 与 `github-release` 任务均通过；[GitHub Release](https://github.com/mirakyux/export4j/releases/tag/v0.1.0-rc.2)于 2026-11-07（北京时间）发布，标签指向构建提交 `88d2bcd81011b1b16c9461f75fb22df247dd422f`。Maven Central 已提供同版本的 16 个库与 BOM；该运行记录构建、签名、上传及远端隔离消费结果。RC 为候选版本，不表示 API 已稳定或提供生产 SLA。

## 手动 GitHub Actions 发布

[Release to Maven Central](../.github/workflows/release.yml) 只接受 `workflow_dispatch`。在 GitHub 仓库的 **Actions → Release to Maven Central → Run workflow** 中选择待发布分支，输入尚未发布的新版本，例如 `0.1.0-rc.3`。点击运行会构建该分支当前提交并自动发布到 Central，远端验证成功后创建对应 GitHub Release；普通 push、PR 和标签不会触发发布。工作流需先进入默认分支，页面才显示手动入口，见 [GitHub 官方说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)。

首次使用前，在 **Settings → Secrets and variables → Actions** 配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `EXPORT4J_SIGNING_KEY` | 完整 ASCII armored PGP 私钥，包含 BEGIN／END 行及实际换行 |
| Secret | `EXPORT4J_SIGNING_PASSWORD` | 私钥密码；仅无密码私钥可省略 |
| Secret | `CENTRAL_TOKEN_USERNAME` | Central Portal 的 Generate User Token 返回的 username |
| Secret | `CENTRAL_TOKEN_PASSWORD` | 同一 Central user token 返回的 password |
| Variable 或 Secret | `EXPORT4J_SIGNING_FINGERPRINT` | 对应公钥的完整大写指纹；两者均存在时优先使用 Variable |

Central 凭据使用 Portal user token，不使用网页登录密码或 GitHub token。生成位置与鉴权协议见 [Central 官方 API 文档](https://central.sonatype.org/publish/publish-portal-api/#authentication-authorization)。

GitHub Release 使用工作流自动提供的 `GITHUB_TOKEN`，无需另建 PAT 或 Secret。构建与 Central 发布任务保持仓库只读权限；后续独立任务才获得 `contents: write`，只读取同一运行保留的公开制品，不执行项目源码。

私钥在个人机器上导出到仓库外的受控文件，再把完整内容录入 Secret；不要粘贴到聊天、提交或构建日志。将下列占位符替换为实际路径和指纹后执行：

```text
gpg --armor --output <仓库外私钥文件.asc> --export-secret-keys <完整指纹>
```

公钥须事先发布到 Central 支持的公钥服务器。工作流会核对私钥、公钥完整指纹及公钥服务器可检索性，避免上传后才因缺失公钥而校验失败。

流程依次执行：

1. 校验明确的非 SNAPSHOT 版本，拒绝临时验证版本、Central 已存在的任一同版本模块及已存在的 `v<版本>` 标签，记录提交与构建输入。
2. 用 Java 21、Node.js 24 和 Gradle Wrapper 构建、测试、检查 API、生成示例并在内存中签名；不导出 CI 私钥文件。
3. 审计本批 SBOM，独立验证 16 个库与 BOM 的 82 个制品、签名、许可、版本和篡改拒绝，生成上传 ZIP。
4. 在新调用方中解析本批签名制品，核对 16 个 JAR 的 SHA-256，检查生产样例的结构和双渲染，以及能力样例的 CMS 签名和篡改拒绝。
5. 通过官方 API 以 `AUTOMATIC` 模式上传一次，等待 `PUBLISHED`。此步骤会对外发布指定版本。
6. 从 Maven Central 下载准确字节、签名和校验文件，再验签、核对 SBOM，并用只配置 Maven Central 的新调用方编译、生成和检查输出。
7. 独立任务再次核对提交、版本、发布包 SHA-256 及远端验证结果，在准确的构建提交上创建 `v<版本>` 标签和 GitHub Release。附带签名 Maven 发布包、公钥、SBOM 与验证报告，并生成提交变更说明。带版本后缀的 RC／beta 等标记为 prerelease，稳定版标记为 Latest。

上传前后会核对同一构建输入；同一时间仅运行一个发布流程。私钥只进入签名相关步骤，Central token 只进入上传与查询步骤。工作流的 Artifact 保留公开 ZIP、公钥、SBOM、部署 ID、报告及样例联系表，不包含私钥或 token。

Central 发布等待上限为 30 分钟，公开仓库分发等待上限为 10 分钟。公开制品尚不可见、网络暂时失败、限流或服务器错误时，只重试下载读取，并共享同一截止时间。上传失败或等待超时后，先用保留的部署 ID 查看 Portal；请求失败并不证明服务器未接收，工作流不会自动重复上传或删除部署。已完成发布但后续验证失败时，保留证据并为修复选择新版本，不能覆盖原版本。

GitHub Release 步骤失败不回滚已发布的 Central 制品。先核对该运行的结果；仅需重试 GitHub 步骤时使用 **Re-run failed jobs**，不要重跑 Central 上传。既有标签必须准确指向该构建提交，工作流不覆盖已存在的 Release 或错误标签。若上传附件期间留下草稿，先在 GitHub 核对并处理该草稿再重试。

自动验证会保存渲染联系表，发布者仍需按变更范围完成视觉及阅读器验收。该工作流不包含人工 Word 编辑保存，也不内置 veraPDF；PDF/A 合规仍需按发布前检查单独提供证据。

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

根 `check` 包含 `apiCheck`，使用从 Maven Central 已发布的 `0.1.0-rc.2` 库 JAR 提取的基线，差异规则、逐库来源和行为兼容边界见 [Java API 与版本兼容](api-compatibility.md)。Java 21 的 Windows／Linux／macOS 构建及临时密钥签名分发在历史提交 `cc132e7` 已远端通过，准确运行记录见[阶段一百零五](verification/phase-105-publication-and-compatibility.md#远端-ci-验收)。阶段一百零六记录的本地安全／兼容门禁不能沿用该历史提交的 CI 结论；后续 RC2 发布及远端消费以本页的实际工作流记录为准。本地构建仓库的检查仍不替代 Central 上传或真实远端仓库消费。

Windows Word／PDF 实际编辑、保存、重开及最终 JAR 回读见[阶段一百零七](verification/phase-107-windows-reader-compatibility.md)。该历史批次完成构建和隔离消费时未重新签名；阶段一百零六的签名摘要只对应其历史批次，后续 RC2 的签名和远端结果独立记录。应用验收不引入 Office、浏览器或打印服务运行依赖，也不替代其他平台或其他发布批次的验收。

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

命令中占位符须替换后执行；Linux／macOS 使用 `./gradlew`。启用签名的 `publishSignedBundle` 先执行 `check`（含测试和候选 API 门禁）、`publishLocalBundle` 及 `sbom`，只把 16 个库和 BOM 暂存到本地，不连接 Central。完整 POM 包含项目、SCM、维护者和许可；二进制、源码、Javadoc、POM 及 Gradle 元数据均须有有效签名。准备脚本使用独立 GnuPG 验签并验证篡改拒绝，核对实际 JAR 与 SBOM、许可及坐标，生成标准目录结构 ZIP 和 MD5／SHA-1／SHA-256／SHA-512 校验文件。调用前须安装 GnuPG，并准备可验证的公开密钥。

`scripts/verify_signed_distribution.py` 可用临时密钥和 `0.0.0-verification...` 标记版本演练整个流程，随后以独立复制的消费者编译并生成输出。消费者实际解析的每个自研 JAR 必须与该批签名仓库具有相同 SHA-256；同坐标、同版本不能替代准确字节核对。构建输入清单同时绑定生产源码、构建／验证脚本、消费者源码及资源，签名与消费期间变化即失败。此测试身份不作为正式签名身份；脚本要求全新忽略目录，私钥仅存在临时 keyring 和签名进程环境，结束后清理。演练包可审查，不自动上传。

准备脚本、临时密钥、正式密钥、ZIP、Central 上传和真实远端消费分别记录结果。构建 CI 的临时签名演练始终不上传；只有上述手动发布工作流会以正式凭据上传。

## 发布前检查

1. 固定发布提交，确认变更范围、工作区和版本号。
2. 在干净环境中使用 Gradle Wrapper 完成已配置的构建和测试。
3. 运行对应阶段的结构、独立工具及视觉验证，记录环境与结果。
4. 核对公共 API 差异、不兼容行为和迁移说明。
5. 验证依赖清单、许可证、资源许可、必要 NOTICE 与 SBOM；对这批实际 SBOM 运行 `python scripts/audit_dependencies.py --sbom <该批SBOM路径> --output <审计报告路径>`，记录命中项及适用性，查询失败不能视为干净结果。
6. 检查二进制、源码、Javadoc、POM 和 BOM 内容及依赖范围。
7. 在干净的调用方项目中解析制品并运行示例。
8. 检查 README、发布说明、安全支持范围与私密报告渠道。
9. 确认发布目标、凭证、签名和校验流程；获得相应发布授权后发布。

Maven Central 为当前远端发布渠道，命名空间与凭证须实际配置。凭证和签名私钥不得写入仓库、日志或文档。

## 发布说明

发布说明至少包含版本、发布日期、主要行为变化、不兼容改动、依赖升级、已知限制和验证摘要。日期及支持范围以实际发布记录为准。

未完成的能力应归入后续计划，不能写成当前版本特性。

## 发布后验证

从目标仓库重新解析已发布制品，核对版本、依赖、签名和校验信息，运行最小示例。

构建通过、上传成功和调用方可用属于不同验证结果。发布失败时保留证据，按目标仓库规则处理，不覆盖已存在的正式版本。
