# 阶段一百零二：有界语义长表、显式重放与逐页 PDF

本阶段完成 GAP-09 既定剩余清单中的语义块格／嵌套、纵向 owner 完整行拆分、有限 Auto 探测、未知总数／未来引用、显式目录／书签／组脚注及 PDF 累计对象控制。API 见[有界语义与重放长表](../streaming-replay-api.md)，架构决策见 [ADR-0102](../adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md)。没有新增缺口编号，也不把固定场景容量解释为长期无泄漏。

## 实现及行为边界

| 入口／能力 | 已交付行为 | 保留边界 |
| --- | --- | --- |
| `exportSemantic` | 只打开一次行源，按有限连通组复用语义表格游标；支持 Paragraph／Table 块格、最多 16 层表格、纵向 owner 跨页及完整源位置 | 单叶、连通组、源树及输出分别计预算；各层拆行开关独立；超高原子内容失败；重复表头仍不接受纵跨 |
| `exportReplay` | 无探测时两遍，有 Auto 时先做有限前缀探测再两遍；按完整语义 SHA-256 核对来源，第二遍使用最终总页数及目标 | 业务快照由调用方提供；不隐式重查数据，不缓存完整正文；探测不得突破行数／序列化字节上限补齐纵向组 |
| 显式未来语义 | 行／块目标、页码／编号字段、有限书签和前置目录，目标偏移按最终文件计算；组脚注预留在首次呈现页，只输出一次 | 脚注只能显式锚定连通组首行；过高注释、缺失目标、重复 ID 和字段框过窄失败；不自动推断全源标题或分节显示页码 |
| `PdfSpoolBackend` | 每页生成和释放独立 PDF 对象图；磁盘保存 xref／页索引，页树叶最多保留 256 个引用；保留最终导航、图文外链和图片字节 | 普通 PDF 1.7；字体按页嵌入，可能增大文件；本入口不自动添加加密、签名或 PDF/A／PDF/UA；普通 `PdfBackend` 的累计对象行为保持 |
| 失败及生命周期 | 来源先于写出器完成关闭；路径使用同目录原子替换；源变更、预算、输出、关闭、取消失败保留旧目标并清理自有临时项 | 目标流、临时父目录和业务连接由应用拥有；借用流上的失败可能留下已写字节，不自动重试 |

`StreamingTable` 仍是专门流规格，不是普通语义 `Block`，不隐式加入 XML、DOCX 或模型恢复序列化。完整文档页模板和导航／注释不能直接忽略，需使用本入口明确支持的计划。来源预算只计真实锚点，重复表头和生成文字按实际呈现计费。

## 组合输出与独立验收

`StreamingReplayConsumer` 的组合样例包含 3 个正文物理行、一个跨 2 行的长 owner、递归内表、90 个连续编号子段、行内图片及图文外链。Auto 探测、分页规划、正式写出各开源一次，打开和关闭均为 3 次，最终文件为 **11 页、255,666 字节**。

独立验证器 `scripts/verify_streaming_replay.py` 通过以下检查：

- 目录中的明细页为 2、最终目标为 11；正文每页恰有一个实际 PAGE／PAGES 表头，书签与 4 个内部链接指向最终物理页。
- owner 的 32 个续行、`NEST-001` 至 `NEST-090` 和后续物理行各按源顺序出现，无丢失或重复；组脚注仅在首次呈现页出现一次。
- 63 个表格矩形均在页区内；首遍 Auto 探测确定的两列实际宽度分别为 90.16211 pt 和 129.83789 pt。
- 2 个外部链接保持原目标，图片链接跟随末页。单个 2×2 RGB 图片对象的尺寸、颜色空间与解码后的 4 个像素均精确匹配输入。
- Poppler／PDFium 渲染全部 11 页，22 幅图像完成逐页复查；最大平均通道误差 1.902217，最大变化像素比例 0.006514。首末页、续页表头、脚注及末页图片没有观察到裁切或意外重叠。

最终冻结源码通过 `SemanticStreamingTableTest` 7 项、`StreamingReplayPdfTest` 8 项和 `PdfSpoolBackendTest` 7 项，共 **22 项定向行为测试，失败／错误／跳过均为 0**。覆盖源范围与真实行号、传递组预算、回调停止读源、探测／完整源变更、关闭异常及 suppressed 顺序、旧目标保护、独立会话、导航及超过 256 页的叶边界。文件结构、完整内容和双渲染作为独立证据，不由 PDFBox 自身读回代替。

本节最终输出由新的隔离 Maven 调用方解析本次本地发布坐标生成；11 页结构／内容／双渲染验证均重新执行，指标与开发阶段控制组一致。发布包、SBOM 和全仓构建按本批总验收单独记录；本地发布坐标验证不代表正式远程分发。未执行 GUI 阅读器和跨平台操作验收。

## 容量与连续退出

最终容量组使用本次隔离 Maven 调用方发行目录的 **32 个实际 JAR**，没有源码类目录。报告绑定类路径文件 SHA-256、实际 Java 21.0.10 版本及每个 JAR 的文件名／版本／SHA-256，结束后再次核对 JAR 未改变。每组独立 JVM 以 `-Xms32m -Xmx64m -XX:+UseG1GC` 运行，使用同一份显式 Liberation Sans 字体。布局只回调当前页，不收集整份 IR；实际 PDF 使用磁盘索引后端；重放核对两遍来源。每组分别采样观察堆、工作集、私有字节、句柄和临时盘，并核对源打开／读取／关闭及退出清理。

最终制品验收包含 **12 个独立 JVM**：布局、实际 PDF、重放各 1k／10k／100k 行，12 次连续失败／取消恢复，以及同时运行的两路 10k 行 PDF。摘要如下；时间为业务执行段，不含 JVM 启动和字体初始加载。

| 模式 | 正文行数 | 页数 | 来源遍数 | 执行时间 |
| --- | --- | --- | --- | --- |
| 布局 | 1,000／10,000／100,000 | 23／228／2,273 | 各 1 | 0.164／0.368／1.109 s |
| 实际 PDF | 1,000／10,000／100,000 | 23／228／2,273 | 各 1 | 2.243／14.423／157.476 s |
| 重放 PDF | 1,000／10,000／100,000 | 23／228／2,273 | 各 2 | 3.926／17.816／150.584 s |
| 两路并发 PDF | 各 10,000 | 各 228 | 各 1 | 21.562／21.841 s |

连续会话共 12 次：4 次源失败、4 次取消、4 次随后成功恢复；来源关闭 12 次，8 次失败均保持旧目标。所有 12 个 JVM 的自有临时文件最终均为 0。独立检查遍历 8 份容量 PDF 的 **5,504 页、242,000 行**，核对全部行 ID 和值的唯一顺序，再对每份的首／中／末共 **24 个抽样页**执行双引擎渲染，48 幅图像完成复查。

最终制品的两组 100k 行峰值如下。临时盘包含活动原子目标、当前中间页和后端索引；其中原子目标并不属于后端索引预算。

| 模式 | 页数 | 执行时间 | 采样堆使用最大值 | 进程工作集峰值 | 私有字节峰值 | 句柄峰值 | 临时盘峰值 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 实际 PDF，100k 行 | 2,273 | 157.476 s | 48.60 MiB | 213.40 MiB | 261.65 MiB | 464 | 20.16 MiB |
| 重放 PDF，100k 行 | 2,273 | 150.584 s | 48.69 MiB | 205.29 MiB | 254.11 MiB | 468 | 20.21 MiB |

两份最终文件分别为 20,898,374／20,899,142 字节，来源读取分别为 100,001／200,002 次，关闭分别为 1／2 次，退出临时文件均为 0。容量抽样最大平均通道误差 2.819222、最大变化像素比例 0.048697；`publishedJarsVerified` 和 `activeTemporaryBytesVerified` 均为 true。

开发阶段另保留一轮同样 12 组和两组 100k 复核，作为源码类路径控制；本节数值以最终发布坐标 JAR 这一轮为准。早期 Windows 目录枚举漏计仍打开的原子目标长度，该轮临时盘值无效，不能沿用。最终采样器使用共享读句柄读取活动文件；若中间页被独占，则显式刷新文件元数据并记录回退次数。独占、共享增长和删除竞态已用实际文件验证，最终完整运行的 `sharedHandleFallbacks` 为 **0**。曾因旧采样器访问拒绝中止的运行不计作容量或退出验收。

采样堆使用最大值不等于精确 GC 堆峰，工作集和私有字节也不等于 Java 堆。后端临时预算只涵盖当前页及索引，路径原子目标另受输出预算约束；实际磁盘需求还需考虑保留的旧文件。上述结果仅覆盖有限固定数据和本次平台，不证明无限期无泄漏、任意输入固定内存或跨平台性能。

## 复现入口

Java 21、Gradle Wrapper 9.2.1；独立依赖为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0，Poppler 26.07.0。Windows 在仓库根目录运行；Python 使用安装了 `scripts/requirements-verification.txt` 的隔离环境。

```powershell
.\gradlew.bat build sbom publishLocalBundle --no-daemon --console=plain
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<unique-validation-directory>" build streamingReplay installDist --refresh-dependencies --no-daemon --console=plain
python scripts/verify_streaming_replay.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>

$consumerBuild = "verification/consumer/build/<unique-validation-directory>"
$libraryDirectory = Join-Path $consumerBuild "install/isolated-library-consumer/lib"
$classpath = (Get-ChildItem -LiteralPath $libraryDirectory -Filter *.jar | ForEach-Object { $_.FullName }) -join [IO.Path]::PathSeparator
$classpathFile = Join-Path (Get-Location) "build/verification/streaming-replay-classpath.txt"
[IO.File]::WriteAllText($classpathFile, $classpath)
powershell -NoProfile -File scripts/measure_streaming_replay.ps1 -ClasspathFile $classpathFile -JavaExecutable (Get-Command java).Source -RequirePublishedJars
$capacity = Get-Content build/verification/streaming-replay-capacity-directory.txt
python scripts/verify_streaming_replay_capacity.py --capacity-directory $capacity --verify-active-temporary-bytes --require-published-jars
```

`-RequirePublishedJars` 拒绝类目录，报告记录类路径文件摘要以及每个实际 JAR 的文件名、文件名中声明的版本和 SHA-256，并在执行结束后再次核对 JAR 未变。消费程序自身没有版本的 JAR 如实记录空版本；发布的 Export4j 库必须具有版本。验证器的 `--require-published-jars` 检查这些证据，避免混淆源码类路径与最终制品容量。

Linux/macOS 使用 `./gradlew` 构建和运行消费程序；当前进程采样脚本依赖 Windows PowerShell，不把命令可迁移性写成实际跨平台容量验收。原始报告、进程采样、渲染和临时日志仅保留在忽略的 `build/verification/streaming-replay/`。
