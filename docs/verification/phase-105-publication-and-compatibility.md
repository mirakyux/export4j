# 阶段一百零五：API、平台与签名分发

本阶段推进 GAP-14.2 的平台验证和 GAP-14.3 的 API／分发准备。签名分发包与远端发布分别记录，有限平台和阅读器样例不推断通用兼容。

## 固定主分支远端基线

对提交 `1665cd7597609e9c7db561cea12b8ca1083e99bb` 实际调度原有 GitHub Actions：
[Build 运行记录](https://github.com/mirakyux/export4j/actions/runs/40655943053)。

| 环境／检查 | 实际状态 | 证据与边界 |
| --- | --- | --- |
| Linux、Temurin Java 21、原有 Build | 失败 | `export4j-opentype:test` 被严格依赖验证拒绝：两个 `natives-linux` JAR 尚无审查过的校验值；不是测试成功后遗漏报告 |
| Windows、Temurin Java 21、原有 Build | 取消 | 原工作流启用 matrix fail-fast；不能计为失败或通过 |
| macOS、Java 21、原有 Build | 未执行 | 原工作流没有该平台 |
| 新 Windows／Linux／macOS 工作流 | 本地配置已补，远端未执行 | 显式 classifier、关闭 fail-fast、API／制品／坐标调用方及原生 OpenType；远端需要包含该配置的提交 |
| 新签名分发 CI job | 本地配置已补，远端未执行 | 临时验证密钥，不读取正式发布秘密，不上传 Central |

已从 Maven Central 下载并对照其发布 SHA-1，补入 Linux／macOS x64 与 arm64 共八个原生 JAR 的 SHA-256。它们进入严格校验清单不代表在这四种平台都运行成功。运行时实际 native 版本必须分别读取，不以 Java 绑定头版本代替。

## 本地自动检查

`scripts/test_api_compatibility.py` 三项真实 Java 编译回归通过：删除／缩小成员、改变常量／泛型及增加必需抽象方法拒绝；record 保留旧构造器与访问器的扩展通过；公共返回值泄漏第三方类型拒绝。固定旧提交的 16 库／638 个公开类型基线来自独立源码 `assemble`；已验证候选的比较结果为 0 个破坏项、44 个增加项、0 个第三方类型泄漏，逐项差异写入 `build/reports/api/compatibility.json`。候选继续变更时须重新构建比较，不使用旧 JAR 冒充新输入。

独立 GnuPG 临时夹具已验证真实分离签名成功、改字节后拒绝，以及关闭该 keyring 的 agent 后移除私钥目录。Windows 的 Git／MSYS GnuPG 显式转换路径，并将短 keyring 目录放在忽略的 `build/` 下，避免 agent socket 路径上限；这项冒烟验证不替代全部 Maven 制品签名检查。

签名准备与独立调用方使用：

```powershell
python scripts/verify_signed_distribution.py --version 0.0.0-verification --native-classifier natives-windows --output build/verification/gap14-current/signed-r4 --gradle-home build/gradle-validation
```

该命令要求新输出目录，创建并清理临时私钥；报告仅描述测试身份。独立 GnuPG 验证每个 `.pom`／`.module`／二进制／源码／Javadoc 签名及篡改拒绝，校对 SBOM 实际版本和 JAR 摘要，再在复制的独立 Maven 调用方中实际编译运行，不修改原调用方锁文件。BOM 的 POM 须准确约束同版 16 库；`resolved-libraries.txt` 核对 16 个实际库 JAR，`resolved-components.txt` 从 Gradle 解析图另外核对 BOM 和库共 17 个组件，BOM 不作为 JAR 计数。`resolved-library-sha256.txt` 从消费者实际解析文件计算摘要，必须逐一匹配该批签名仓库，避免将同坐标的旧缓存当作新制品。结果写入输出目录的 `distribution-report.json`；运行成功后方可把对应条目登记为通过。

### 已完成的签名输入快照

Windows x64、Java 21、GnuPG 2.4.9 的 `signed-r4` 批次已实际完成；复现时应使用另一个全新输出目录。这是临时验证版 `0.0.0-verification`，覆盖其捕获的工作树输入，不代表正式版本或后续修改后的同一批字节。

| 检查 | 实际结果 |
| --- | --- |
| 本地签名构建 | 17 模块成功，82 个 POM／Gradle 元数据／二进制／源码／Javadoc 均经独立 GnuPG 验签 |
| BOM 与 SBOM | BOM 准确约束同版 16 库；SBOM 的 16 个自研库版本和 JAR SHA-256 与该批签名仓库一致 |
| 坐标消费 | 复制的独立消费者 `build run resolvedLibraries` 成功；实际 16 个库 JAR、解析图 17 个组件包含同版 BOM；逐个解析 JAR 的 SHA-256 与该批签名仓库一致 |
| ZIP 与校验文件 | 820 条目，164 个制品／签名文件的 656 个 MD5／SHA-1／SHA-256／SHA-512 校验全部吻合 |
| 许可字节 | 48 个 JAR 的 LICENSE、NOTICE 和全部第三方许可，共 1,680 项嵌入内容与捕获源清单的 SHA-256 一致 |
| 负控制与清理 | 修改已签字节明确拒绝；将真实库的 SBOM SHA-256 改错后明确拒绝；本批临时私钥目录已移除 |

`build-inputs.json` 逐文件记录实际生产源码、Gradle 配置、签名／打包脚本、消费者源码／锁和字体／许可输入，并确认签名及消费全过程未变化；HEAD 字段只表示基准提交，不把有修改的工作树当作该提交的准确字节。该批独立 SBOM 保存在同一输出目录，避免后续构建覆盖证据。后续修复改变 JAR 时须重新签名及消费验证，不能沿用本批结论。

| 本批证据 | SHA-256 |
| --- | --- |
| 构建输入清单 | `9cdf752e679a93ce5876ea00d1c71d3e493b26a2b06418b786c9d7de93461027` |
| 独立 SBOM | `7b1003530cbb2cb54d05bfb9cc354413cb0a970f1a665b8eb5b219de1d403660` |
| Maven 目录结构 ZIP | `fccffd88b65a84dddd2d4910ca6e68976a7bf1365ed2df5780420a88b5bb7e81` |

正向报告为该目录的 `distribution-report.json`，附加 ZIP／许可／错误 SBOM 负控制为 `negative-controls/report.json`。这些结果不包含 Central 上传、正式签名身份或远端仓库消费。

## 阅读器矩阵的归属

| 目标 | 既有证据 | 不能据此推断 |
| --- | --- | --- |
| Windows Word 保存与字段／页区／标准表格回读 | [恢复闭环](phase-90-recovery-closure.md)、DOCX 各阶段及 GAP-12 的专项验收 | macOS Word、LibreOffice、所有未知 OOXML 结构 |
| PDF/UA 辅助阅读器 | [PDF 标准](phase-96-pdf-standards.md)的指定生成／已有标签文档 | 任意标签 PDF 或所有阅读器均合规 |
| OpenType 查询／提取／选择与独立渲染 | [原生 OpenType](native-opentype.md) | 所有桌面阅读器和不同平台的复制／搜索交互完全一致 |
| 其他格式／输入组合的预检与实际损失 | 各格式 API、GAP-24 和专项验收 | 任意输入无损互转或完整浏览器兼容 |

矩阵中未实际运行的平台、应用和输入组合保持未执行状态。正式发行前应基于目标应用补实际打开、查询、选择、编辑、保存、关闭和重开回读，不将程序化读写替代交互证据。

## 正式发行前置条件

当前尚未正式发布。Central 命名空间、正式签名身份、正式版本及从真实远端仓库的干净消费仍需实际核验。准备 ZIP 和临时密钥验证不替代正式上传、身份确认或远端消费结果。
