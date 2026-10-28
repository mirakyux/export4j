---
name: release-export4j
description: '准备或验证 Export4j 版本、JAR/POM、BOM/SBOM、隔离 Maven 调用方及正式发布时使用；按实际构建配置核对制品和发布目标。'
---

# 制品与发布

阅读[发布规范](../../../docs/releasing.md)、[依赖记录](../../../docs/dependencies.md)及当前 `build.gradle.kts`、版本目录和锁文件。命令和目标必须以实际配置为准。

## 准备与验证

1. 确定版本、模块范围、公共 API 和行为兼容性。模块统一版本，由 BOM 对齐；开发 SNAPSHOT 不能作为已发布稳定版。
2. 检查变更范围，保护无关改动。使用 Gradle Wrapper 验证构建、测试、Javadoc、依赖锁和受影响输出。
3. 运行本地制品与隔离调用方：

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
```

4. 按[输出验收](../verify-export4j-output/SKILL.md)执行对应 Python 验证与必要独立工具。核对本次成功调用方目录，不能复用旧结果。
5. 检查二进制、源码、Javadoc、POM、Gradle 元数据、BOM、LICENSE／NOTICE 及资源许可。公共签名和传递依赖必须符合模块边界。
6. 核对 `build/reports/sbom.cdx.json` 与实际运行制品、版本和 SHA-256。该清单不覆盖 JDK、字体、测试及构建工具，其义务另外登记。

## 实际发布边界

`publishLocalBundle` 将 16 个库及 BOM 写入项目的 `build/repository`，用于隔离验证。它不代表远程仓库或签名发布成功；不自动替换成 `publishToMavenLocal`。

远程发布前需有明确任务授权、可核实的目标、命名空间权限、版本和签名配置。只执行任务已授权的动作，不增加重复确认。配置尚未存在时，完成制品准备并说明具体缺口，不编造远程任务、凭据或成功状态。

凭据、私钥和签名材料由仓库外部安全配置提供，不写入源码、文档或日志。已发布正式版本不得覆盖；发布失败保留脱敏证据，按仓库规则处理。

## 发布后与说明

从实际目标仓库重新解析制品，核对版本、依赖、校验及签名，并在独立调用方中生成文档。发布说明包含具体行为变化、不兼容项、依赖变化、已知限制和验证摘要。

分别说明构建、本地制品验证、提交、推送、上传和远程消费结果；前一步成功不能替代后一步证据。
