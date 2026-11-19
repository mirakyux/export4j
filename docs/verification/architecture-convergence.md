# 跨入口规则与操作上下文验收

本记录对应当前开发版对六项架构优化的接入：相对列宽、操作期字体绑定、共同语义准备、XML 静态语法计划、字体来源／缓存，以及布局测量状态。问题与取舍见[架构研究](../architecture-reference-review.md)及[工程笔记](../../.agents/notes/implemented/architecture/2026-11-19-reference-architecture-convergence.md)。参考项目源码未进入 Export4j。

## 覆盖范围

| 项目 | 可观察结果与控制 |
| --- | --- |
| 相对权重 | 单列、双列及固定＋比例混合的 `1`／`1e308` 等价输入；模型快照、极端小值／总量、HALF_EVEN 边界、非法输入及目标单位溢出拒绝；PDF 实际边线与 DOCX `tblGrid`／`tcW`／`tblW` |
| 分栏精度 | 保留历史两栏精确列宽和换行；三栏 `[10,1,1]` 在 100 pt 字形边界保持一页及全部 120 个字形，不放宽断言 |
| 操作期字体 | Markdown、HTML、XML 动态内容及预编译语法的同一业务角色；regular／bold／italic／boldItalic 四种真实物理变体及 Word 样式声明；未知角色和缺失变体拒绝 |
| 配置继承 | plain 文本保留共享 regular 角色；仅在确需推导变体时拒绝歧义，显式 CSS family 可解除歧义；保留空角色、黑色、false 的覆盖意图 |
| 后端与输出 | starter 实际使用借用的 TEMP_FILE 后端和解析池；exporter 关闭后池可复用，最后由所有者关闭；PDF／DOCX 输出预算及自定义后端预算失败保留旧目标 |
| 共同准备 | 完整／逐页同源多页内容、导航冲突、来源错误位置、累计预算及语义来源；逐页保持紧凑证明／重放 |
| 静态语法 | prepare 不打开 parser、不加载字体；片段静态展开，资源／主题／预算按次验证；并发、取消、数据快照及多个动态源共用累计预算 |
| 字体缓存 | bytes 私有快照及内容标识、外部数组隔离、文件重读、同键合并、异键独立、等待打断、失败不缓存、clear／close 与新一代载入隔离、清理调度失败 |
| 测量状态 | 变宽／试探／免计费模式、行数与嵌套来源在异常和取消后恢复，后续布局保持一致 |

starter 对 Markdown／HTML 校验完整的正文、六级标题和单元格角色配置，避免全强调内容把未知基础角色洗掉。TEXT 及不含动态富内容的原生 XML 不预先要求未使用的标题／单元格主题角色。底层内容导入器没有物理字体注册表，继续允许由后续引擎解释的角色；完整字体登记和输出校验归 starter／引擎。

## 构建与制品消费

从仓库根目录执行，预先配置 Java 21，并使用安装了 `scripts/requirements-verification.txt` 的验证 Python：

```powershell
.\gradlew.bat build sbom publishLocalBundle apiCheck `
  :examples:easyExport :examples:math :examples:contentBlocks :examples:markdownImport `
  --no-daemon --max-workers=2 --console=plain
python scripts/test_api_compatibility.py
powershell -NoProfile -File scripts/verify_local_consumer.ps1 -VerificationPython python
powershell -NoProfile -File scripts/verify_starter_consumer.ps1 `
  -Version 0.1.0-SNAPSHOT -OutputDirectory build/verification/starter-single-architecture
```

Linux/macOS 使用 `./gradlew`；两个 PowerShell 消费流程也可按脚本中的任务在对应平台执行。新验证必须使用新输出目录。`verify_local_consumer.ps1` 分别从项目本地 Maven 仓库走 Gradle 元数据和仅 POM 两条路径，执行 `architectureConvergence` 及既有统一入口、数学、字体和富内容调用；它不替换类路径中的同名类。仅 starter 消费另验证一个直接坐标、未声明 BOM 的装配。

独立结构与渲染使用本次两个成功调用方目录：

```powershell
python scripts/verify_architecture_convergence.py `
  --consumer-build-directory <本次元数据调用方目录> `
  --consumer-build-directory <本次仅POM调用方目录> `
  --output-directory build/verification/architecture-output
python scripts/verify_easy_export.py `
  --consumer-build-directory <本次元数据调用方目录> `
  --consumer-build-directory <本次仅POM调用方目录> `
  --output-directory build/verification/architecture-easy-export
```

验证器从标准 OOXML、PDF 字形／边线及运行 JAR 摘要检查结果；所有新增 PDF 页由 Poppler 和 PDFium 渲染，等价比例、完整／逐页及 XML／预编译语法分别比较同引擎全页像素。联系图生成与实际视觉复查分开登记。`--soffice <明确可执行文件>` 可另外执行 DOCX 独立阅读器渲染，缺少工具时不会把该项计为通过。

API／本地签名分发另用 `scripts/verify_signed_distribution.py --version 0.0.0-verification-a7 --native-classifier natives-windows --output build/verification/a7-sign --offline`，只创建一次性验证密钥及本地验证版本。脚本检查全部当前库、BOM、SBOM、签名、篡改拒绝和两种元数据消费，不上传制品。`--offline` 使用已缓存的外部依赖与本次本地仓库，不跳过签名／摘要校验，也不降低 TLS 要求；全新环境须先正常取得依赖。

## 受控局部测量

三次独立 Java 21 JVM，固定 `-Xms256m -Xmx256m -XX:+UseG1GC`，使用相同输入、预热及算法。测量期间没有并行构建或渲染。以 `ThreadMXBean` 记录主线程累计分配，原始探针和样本在 `build/verification/architecture-fixes-20261119/`。

| 场景 | 修前／逐次解析 | 修后／复用计划 | 解释 |
| --- | --- | --- | --- |
| 13,110,528 字节 bytes 来源，预热后同来源命中 100 次 | 约 1.25 GiB 分配 | 3,200 字节分配 | 来源创建和首次解析在计时外；缓存仍只载入一次 |
| 200 段、6,213 字符 XML，预热后 compile＋render 100 次 | 中位 165,328,768 字节、397.4 ms | 中位 136,744,416 字节、134.6 ms | 减少约 17.3% 分配；一次 prepare 单独耗时约 31–43 ms |

字体缓存另以两条冷载入线程和一条稳定命中线程做对照；修后稳定命中线程的 monitor 阻塞样本为零，原样本约 0.2–0.4 秒。它验证冷解析移出大锁的局部行为，不构成公平吞吐基准或尾延迟保证。XML 探针每个进程均先测逐次解析再测语法复用，计时受 JIT／次序影响；分配减少与正确模型对照才是采用入口的主要依据。

这些数字不表示整篇导出吞吐、峰值堆、RSS 或长期存活内存变化。bytes 来源对象自身持有编码快照；TTL 只释放缓存引用，调用方持有的 face 和活动租约仍延长寿命。没有增加生产显式 GC 或恢复 FontBox 补丁。

## 本次结果与边界

验收完成日期：2026-11-20。Java 21.0.13、Gradle Wrapper 9.4.1；Python 依赖按验证清单固定。研究与最初测量从前一天开始，原始目录保留原名。实际结果如下：

| 检查 | 结果 |
| --- | --- |
| 全仓构建及 Java 测试 | 19 个有测试模块合计 2,762 项，0 失败、0 错误、0 跳过；`build sbom publishLocalBundle` 通过 |
| API 门禁 | RC2 固定发布基线对当前完整工作树，0 不兼容声明、114 项新增、0 外部实现类型泄漏；兼容检查器的 6 项正负控制通过 |
| SNAPSHOT 制品消费 | 元数据和仅 POM 两条路径各 18 个库 JAR；全部与项目本地 Maven 仓库逐项 SHA-256 一致，含匹配 BOM |
| 仅 starter 装配 | 两条路径均只声明一个 starter 坐标、未声明 BOM；14 个项目模块、26 个运行制品，扫描 10,474 个 Java 21 有效类，0 重复提供者；两条路径及本地仓库摘要一致 |
| 新夹具 | 每个调用方 13 份 PDF 共 23 页、10 份 DOCX；标准 OOXML、实际 PDF 表格边界、四种真实字体变体和预算／缓存生命周期通过 |
| 双引擎及等价对照 | 每个调用方全 23 页经 Poppler／PDFium 渲染；6 组等价来源分别比较两个引擎，全部像素相同；两条元数据路径的 46 张渲染图全部逐像素一致 |
| 签名制品再次验收 | 独立目录的 18 个库＋BOM、92 个签名制品、SBOM／BOM 校验与篡改拒绝通过；两种元数据消费均通过并核对全部 18 个库摘要；两组新增夹具重新独立验收，92 张图逐像素等于对应 SNAPSHOT 渲染 |
| 既有输出回归 | 统一入口的样例及两种消费、六级标题／正文／单元格主题传递、公式、Java／XML 内容块和 Markdown 导入检查通过；保留原视觉基线 |
| 视觉复查 | 已查看新增 23 页的全部双引擎联系图、角色实际页面，以及统一入口、公式与内容块的输出；未见裁切、重叠或意外空页。相同像素的其他消费副本复用该视觉结论 |
| 文档与规则 | 434 页文档站本地构建通过；工程笔记由提议迁为已实现，项目规则校验通过；本地预览用临时 Git 索引纳入选定新增文档，真实索引未暂存 |

最终补查与复验证据位于 `build/verification/architecture-final-20261120/`：构建 `build.log`、测试汇总 `test-summary.json`、消费摘要 `snapshot-consumer-hashes.json`、单 starter 证明 `starter-verification.json`、结构与渲染 `output/`、签名制品渲染 `signed-output/`，以及各专题检查目录。最终制品重新生成并独立检查，`visual-baseline-reuse.json` 将这些像素与此前已查看的对应图片逐项比较，全部相同；未自动刷新基线。最初研究／性能测量及视觉复查证据仍保存在 `build/verification/architecture-fixes-20261119/`。

本地签名版本为 `0.0.0-verification-a7`，仓库在 `build/verification/a7-sign/repository/`，`distribution-report.json` 记录 19 个组件及完整签名和隔离消费结果。验证包 `maven-central-bundle.zip` 的 SHA-256 为 `c53e31f5c8b98cb789b17d1bcc9dfdfd1abc70cb50e01074fde0a7f575a75021`；该版本仅用于验证，没有上传，临时私钥目录已删除。正式开发坐标仍为 `com.mirakyux.export4j:export4j-starter:0.1.0-SNAPSHOT`，其余模块同版本。

提交前补查还复现了极小总量的双重舍入：权重 `0.75, 1`、总量 `Double.MIN_VALUE` 时，原实现错误地将两份都接受为 `Double.MIN_VALUE`；第一份的精确比例为 `3/7` 个最小单位，应舍入为零并拒绝。新增 3 项回归覆盖正确拒绝、可表示份额、HALF_EVEN 半单位及 normal 边界，私有精算分支按最小 double 单位执行精确整数舍入。最终 SNAPSHOT 与签名 model JAR 的独立调用探针均验证修复；所有正常样例的最终像素保持相同。独立份额舍入不承诺总和严格守恒。

首次并行全仓运行的一项设计器 HTTP 测试超时，单独复测和后续完整构建均通过，没有放宽超时断言。仅 POM 的在线刷新曾因 Maven Central TLS 握手中断失败，离线重建及签名版本两条消费均成功并核对真实 JAR 摘要。首次临时签名目录／版本组合触发 Windows 长路径的 Python 文件检测限制，改用上述短目录和短验证版本完成全套签名及消费；未把失败日志计为通过。新增 PDF 网格验证器也先纠正了将填充边缘与内缩描边中心线混在一起的检查方式，现按真实描边外缘验证，保持 0.001 pt 容差。

未执行 Microsoft Word 的打开、编辑、字段更新和保存回读；独立 DOCX 阅读器不可用时明确记录跳过，标准 OOXML 检查不能替代阅读器动作。有限样例不证明任意字体、模板、Word 分页或恶意输入沙箱。本地构建、临时签名和消费不代表正式版本上传、发布或部署。
