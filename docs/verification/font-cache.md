# 字体缓存验收

本记录对应[字体缓存 API](../font-cache-api.md)和 [ADR-0114](../adr/0114-idle-expiring-font-caches.md)。新缓存属于当前开发版，不包含在已发布的 RC3 中。

验收日期：2026-11-13。以下结果来自本地构建与隔离分发消费，不代表远端 CI、Maven Central 发布或部署。

## 复现

使用 Java 21、仓库 Wrapper、按 `scripts/requirements-verification.txt` 固定的 Python 验证环境、Poppler、GnuPG 及站点依赖，从仓库根目录运行。以下输出目录用于首次执行；重跑须改用本次尚不存在的新目录，保留已有证据。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:textPdf apiCheck
python scripts/verify_signed_distribution.py --version 0.0.0-verification-font-cache-20261113 --native-classifier natives-windows --output build/verification/font-cache-signed
python scripts/verify_font_cache.py --consumer-build-directory build/verification/font-cache-signed/consumer-project/verification/consumer/build --output-directory build/verification/font-cache-20261113/signed-output
python scripts/verify_font_cache.py --consumer-build-directory build/verification/font-cache-signed/pom-only/consumer-project/verification/consumer/build --output-directory build/verification/font-cache-20261113/signed-pom-output
python scripts/verify_driverless.py --consumer-build-directory build/verification/font-cache-signed/consumer-project/verification/consumer/build --output-directory build/verification/font-cache-20261113/driverless
npm --prefix site run build
```

签名验证器使用临时验证身份构建实际 Maven 仓库、签名包、BOM 和 SBOM，并建立普通 Gradle 元数据消费及仅 POM 消费两个隔离项目。两者均执行 `fontCache`，从实际 Maven JAR 调用公共缓存 API。普通消费路径和仅 POM 消费路径使用同一批已核验制品，不以当前源码的 classes 替代。

## 实际结果

| 检查 | 本地结果 |
| --- | --- |
| 构建、SBOM、本地发布包、文字样例及 API 检查 | 通过；最终构建为 225 个任务，其中 87 个执行、138 个 `UP-TO-DATE` |
| JUnit XML 汇总 | 18 个模块、286 个套件，共 2512 项，失败 0、错误 0、跳过 0；部分结果随增量构建复用，不声称全部在同一次重新执行 |
| 签名分发 | 18 个组件、87 个签名制品通过；篡改制品被拒绝，临时私钥环已删除；BOM 的 17 项约束及 SBOM 的 17 个库已核对 |
| 隔离消费 | 普通元数据及仅 POM 两种路径均解析 18 个项目组件、17 个库 JAR；每种路径的 17 个 JAR SHA-256 均与本地 Maven 制品一致 |
| 字体缓存行为 | 默认缓存实际启用；显式禁用、命中、无后续请求的自动过期、过期后重载、在用时清空以及调用方字体过期后仍可用均通过 |
| 无打印驱动路径 | 无桌面 JVM 禁止加载打印类，3 个负控制真实失败；5 个调用方通过，生产打印 API 引用为 0，17 个项目 JAR 和 33 个运行时 JAR 摘要已核对 |
| 文档站 | 准备 415 个公共文档页并完成构建；保留已有的压缩后 chunk 超过 500 kB 警告 |

两个隔离调用方分别生成 `disabled`、`cold`、`warm`、`expired`、`page-session` 和 `spool` 六种 PDF，每种完整 1 页。独立检查了逻辑文字、嵌入字体程序及 PDFium 字符选择几何；Poppler 与 PDFium 各自渲染全部六页，并逐像素与同一引擎的禁用控制页比较，均一致。每份输出的嵌入字体程序摘要均为 `3aab506d064446356ee37e288f82b117a33cbb8d489e401b12e05029642d707c`。

视觉复查已查看两种引擎的禁用控制整页；其余模式的完整页面由上述同引擎逐像素相同验证覆盖。没有将两种引擎彼此的像素差异或 PDF 文件字节差异作为失败依据，也不把自动比较记成逐页人工检查。

本地证据分别保存在 `build/verification/font-cache-20261113/` 下的 `cache-review-final-audit.json`、`signed-output/report.json`、`signed-pom-output/report.json`、`driverless/report.json` 和 `measurement/report.json`；签名及消费汇总为 `build/verification/font-cache-signed/distribution-report.json`。这些目录为忽略的本地验收产物，不随源码分发。

## 本地制品身份

本次本地验证 BOM 坐标为 `com.mirakyux.export4j:export4j-bom:0.0.0-verification-font-cache-20261113`；17 个库使用相同 group 和版本。此版本仅用于本地隔离验证，没有上传，未执行远端仓库消费。签名构建记录的输入清单 SHA-256 为 `6c14a1c61690d3a9b0f962afdf728fbbf056a4166c03538e6ec34d4e770c077d`。

| 本地相对路径 | SHA-256 |
| --- | --- |
| `build/verification/font-cache-signed/maven-central-bundle.zip` | `5bc211b59762b50b6e96313f4d51fd572c2fb56a3cc22dd792477b1062e2c44a` |
| `build/verification/font-cache-signed/sbom.cdx.json` | `173c42d4c476fceaeb87b71d142159f901119dfd476d7135f92f6b1550c714df` |

下表库路径均相对于 `build/verification/font-cache-signed/repository/com/mirakyux/export4j/`，也是对应同名制品的实际 Maven 文件路径。

| 库及相对路径 | SHA-256 |
| --- | --- |
| `export4j-font-core/0.0.0-verification-font-cache-20261113/export4j-font-core-0.0.0-verification-font-cache-20261113.jar` | `b6910d670a367504c2076fb8b71606e7dd0347d7d6e28c3b115420224fa3656f` |
| `export4j-text-core/0.0.0-verification-font-cache-20261113/export4j-text-core-0.0.0-verification-font-cache-20261113.jar` | `0a2ce13b5ae13e72b67e98880c9415eec0310510aa207b166a0b7b2437e0d2e3` |
| `export4j-backend-pdf/0.0.0-verification-font-cache-20261113/export4j-backend-pdf-0.0.0-verification-font-cache-20261113.jar` | `ff40bfae026adff8e300c973fb2785b5e08e66818f49270f26e68e2fed92e049` |

两个调用方的完整运行库摘要分别在 `build/verification/font-cache-signed/consumer-project/verification/consumer/build/resolved-library-sha256.txt` 和 `build/verification/font-cache-signed/pom-only/consumer-project/verification/consumer/build/resolved-library-sha256.txt`。FontBox 仍使用 `org.apache.pdfbox:fontbox:3.0.8`，运行 JAR SHA-256 为 `a1915c24e3edbe0ecec93896dfbf6d41427810b663ade97bd4e8bae86ec3fdab`。

## 固定输入重复导出测量

测量使用已核验普通消费路径的实际 `distZip`，顺序启动 3 对新 JVM：禁用／启用、启用／禁用、禁用／启用。每个进程先完整导出 1 次冷启动样例，再完整重复导出 5 次；计时不与构建或独立渲染并行，不强制 GC，也不改变输出质量。JVM 为 Java 21，固定 `-Xms256m -Xmx512m -XX:+UseG1GC -Djava.awt.headless=true`。

固定输入是同一个单页文档及显式 `assets/fonts/ZCOOLXiaoWei-Regular.ttf`，字体为 6,313,808 字节，SHA-256 为 `a42b620140f493db42f741351dfbf343c0936d58588ee8004b8b2a218d997ff1`。先取每个进程 5 次重复导出的中位数，再取同配置 3 个进程中位数，得到：

| 指标 | 禁用缓存 | 启用缓存 |
| --- | --- | --- |
| 主线程累计分配／次 | 48.32 MiB | 7.70 MiB |
| 完整重复导出耗时／次 | 44.45 ms | 24.86 ms |
| 各进程重复耗时中位数范围 | 42.16–50.29 ms | 22.46–31.19 ms |
| 冷启动耗时范围，单独记录 | 476.19–522.65 ms | 439.14–496.95 ms |

以下命令在已核验分发完成后，从仓库根目录运行，可重建相同的 3 对新 JVM 输入条件。`fontCacheBenchmark` 任务和下列直接 JVM 入口均使用同一 `FontCacheConsumer`；直接运行可固定 JVM 参数并避开构建计时。每次输出中的 `measurement.xml` 分别记录第 1 次冷启动与其后 5 次重复样本。

```powershell
$cacheConsumer = (Resolve-Path 'build/verification/font-cache-signed/consumer-project/verification/consumer').Path
$cacheMeasurement = [IO.Path]::GetFullPath('build/verification/font-cache-measurement-new')
Expand-Archive -LiteralPath "$cacheConsumer/build/distributions/isolated-library-consumer.zip" -DestinationPath "$cacheMeasurement/runtime"
$cacheClasspath = "$cacheMeasurement/runtime/isolated-library-consumer/lib/*"
$cacheJava = Join-Path $env:JAVA_HOME 'bin/java'
$cacheOrder = @(@('false', 'true'), @('true', 'false'), @('false', 'true'))
Push-Location $cacheConsumer
try {
    for ($cachePair = 0; $cachePair -lt $cacheOrder.Count; $cachePair++) {
        foreach ($cacheEnabled in $cacheOrder[$cachePair]) {
            $cacheOutput = "$cacheMeasurement/$($cachePair + 1)-$cacheEnabled"
            & $cacheJava '-Xms256m' '-Xmx512m' '-XX:+UseG1GC' '-Djava.awt.headless=true' "-Dconsumer.outputDirectory=$cacheOutput" '-cp' $cacheClasspath 'consumer.FontCacheConsumer' $cacheEnabled
            if ($LASTEXITCODE -ne 0) { throw 'font-cache measurement failed' }
        }
    }
} finally {
    Pop-Location
}
```

该结果仅说明这一页固定字体输入、固定算法及 GC 条件下的主线程分配与重复完整导出耗时。它不是进程 RSS、峰值堆、空闲后堆存量、长期稳态或通用吞吐结论，不能推导任意文档的固定收益。

## 核对重点

- 内容哈希、外部输入快照、文件变化、命中仍受输入预算约束及失败不缓存。
- 容量、空闲续期和无后续加载的自动释放；统计仅观察，不能以观察触发清理冒充自动过期。
- 并发解析独占、缓存关闭／清空不破坏在用对象、失败与取消后不会复用未确认状态。
- 每个导出拥有自己的 PDF 字体／编码／子集，导出关闭后才归还解析对象；过期只释放缓存持有的引用。
- 守护清理器不继承请求上下文，在缓存空或关闭后停止，不调用生产 `System.gc()`。
- 缓存开启、关闭、命中、过期重载与流式 PDF 的文字、字体、坐标和独立双引擎渲染一致。

源字节权重、主线程累计分配、堆存量及进程 RSS 分别记录。重复加载命中或过期条目数归零不能单独证明稳定提速、RSS 降低或长期无泄漏；外部仍持有的字体与引擎不会被强制释放。
