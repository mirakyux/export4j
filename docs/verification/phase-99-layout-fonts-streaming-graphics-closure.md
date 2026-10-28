# 阶段九十九：复杂布局、字体、语义长表与高级图形完整验收

本轮完成 GAP-03、GAP-08、GAP-09、GAP-21 的原剩余清单，并关闭原有 GAP-03.1、GAP-08.1、GAP-21.1、GAP-21.2。没有删减既定目标或增加缺口编号。历史阶段九十八及更早记录保留当时范围，当前状态以[缺口表](../capability-gaps.md)为准。

## 完成清单

| 原缺口 | 本轮完成的行为 | API 与决定 |
| --- | --- | --- |
| GAP-03／03.1 | 递归 Paragraph／Table 格子树、纵向 owner 和组件网格按实际变宽区域完整行续排；任意闭合轮廓按实际行带避让，支持原文 UTF-16／未来区域锚点、跨页图形遮罩及真正的语义子流续排 | [变宽续排](../variable-table-continuation-api.md)、[网格](../grid-continuation-api.md)、[轮廓浮动](../contour-floats-api.md)、[ADR-0099](../adr/0099-variable-recursive-grid-and-contour-flow.md) |
| GAP-08／08.1 | 可选原生塑形、显式脚本／语言／方向／特性、更多复杂脚本、TTB 竖排与分页／纵中横、可变 glyf／CFF2、COLR／位图彩色字体；静态 name-keyed／CID-keyed CFF 原生 PDF 字体嵌入 | [原生文字](../native-opentype-api.md)、[文字验收](native-opentype.md)、[ADR-0101](../adr/0101-explicit-native-opentype-and-cff-embedding.md) |
| GAP-09 | 单次语义源支持块格、递归内表和纵向 owner 完整行续排；显式重放提供有限 Auto 探测、未知总数、未来引用、目录／书签及组脚注；磁盘索引 PDF 释放逐页对象；重复／失败／取消／并发容量 | [语义长表与重放](../streaming-replay-api.md)、[容量验收](phase-102-semantic-streaming-replay-and-pdf-spool.md)、[ADR-0102](../adr/0102-bounded-semantic-streaming-replay-and-pdf-spool.md) |
| GAP-21／21.1／21.2 | CMYK／ICC／专色、16 种混合、隔离透明组、渐变／矢量图案、复合裁剪；SVG 本地 defs／use、资源继承、marker、变换与裁剪组合；印刷色向 RGB 输出逐项报告实际损失 | [高级图形](../advanced-graphics-api.md)、[ADR-0100](../adr/0100-native-paints-and-offline-svg-resources.md) |

## 构建和制品

最终 Java 21／Gradle 9.2.1 的 `build sbom publishLocalBundle` 通过。JUnit XML 汇总为 **189 个测试类、1,747 项测试，失败、错误、跳过均为 0**。本轮修复了全仓检查发现的旧 CFF 轮廓断言，新的断言同时验证原生字形映射、显式轮廓路径及补充平面缺字位置；没有删除原有来源检查。

新隔离 Maven 调用方使用本轮发布坐标执行编译、普通调用、四项组合任务及既有竖排／长表／图形任务。最终清单为 **18 个工程模块、16 个自研库、BOM 的 16 条约束、29 个 Java 运行坐标**；SBOM、解析坐标及 JAR SHA-256 对账。二进制、源码、Javadoc、POM、LICENSE／NOTICE 及第三方许可一并核对。本地仓库验证不等于正式远程发布。

`export4j-opentype` 是真实可选模块，LWJGL Java 绑定不把平台 native classifier 传递到生产 POM。应用显式选择本机二进制并提供字体；原有纯 Java 核心路径保持可用。Windows 实际 HarfBuzz 版本、原生二进制摘要、内含许可和字体来源分别登记，不能以绑定头版本代替运行版本。

## 最终输出

| 单元 | 最终样例 | 独立断言与结果 |
| --- | --- | --- |
| 复杂布局 | 递归表格 2 页、变宽网格 5 页、曲线浮动 3 页、语义浮动 3 页，共 13 页 | 完整源 token 顺序与唯一性、三层子树、owner／表头、PAGE／PAGES、图片／文字链接；Poppler／PDFium 与原生 PNG 对照，全部页面复查 |
| 字体 | 37 组普通字体样例，另有 name-keyed／CID-keyed CFF 各四档 PDF/A，共 45 份 PDF、49 页及 41 页 SVG | 26 个 run／643 个字形的 GID、UTF-16 簇、GPOS 及真实轮廓；提取、查询、选中文字／非空选区、双渲染和原生 PNG 分别核对；SVG 经 resvg 和逻辑标签核对；8 份 CFF 归档文件全部通过 veraPDF |
| 语义长表 | 11 页组合 PDF，含 90 个子段、32 个 owner 续行、目录、最终字段、脚注、图片及内外链接 | 原文唯一顺序、63 个表格矩形、最终页目标、单图的尺寸／RGB 像素和链接；全部页面双渲染并复查 |
| 高级图形 | 5 份 PDF，4 份 SVG及对应 PNG，含 16 种混合和 PDF/A-2b | 原生 CMYK／ICC／专色、图案、渐变软蒙版、隔离组和状态；PDF 双渲染，最终 SVG 经 resvg、源 SVG 经 Chromium 对照；归档文件经 veraPDF |

以上新增共 **78 页 PDF** 完成独立渲染和逐页复查。字体的逐簇 RTL 锚点曾造成真实查询失败，现按实际 run／行位置保存逻辑源；CID CFF 的 Encoding CMap 曾缺少流字典声明，已补齐并重新验证八种归档组合。没有通过放宽合规或内容断言掩盖失败。

工具边界分别记录：PDFium 可能将窄格行尾连字符作为特殊字符，普通字形由独立 ToUnicode 提取核对；不规则曲线行首可能被阅读器按几何重排，因而同时检查 ActualText 标记顺序与全部 token 唯一性。逻辑选区不等于精确字形轮廓或脱敏框，也不能由本次语料推出所有阅读器兼容。

## 容量与资源

最终容量使用隔离调用方发行目录中的实际 JAR，在独立 JVM 以 64 MiB 最大堆运行：布局、真实 PDF 和完整重放各 1k／10k／100k 行，共九组，另有 12 轮成功／源失败／取消恢复及两路并发 10k 行 PDF，共 12 个 JVM。报告绑定 classpath 和每个实际 JAR 的摘要，拒绝以源码类目录冒充发布制品。

两种 100k 行 PDF 均为 2,273 页，执行时间分别为 157.476 s／150.584 s，观察堆使用最大值分别为 48.60 MiB／48.69 MiB。独立工具遍历 8 份容量 PDF 的 5,504 页，核对全部 242,000 行的 ID／值顺序及唯一性，再双渲染各份首／中／末页。采样同时记录堆使用、进程工作集、私有字节、句柄、吞吐及活动临时盘；12 个 JVM 退出后的自有临时文件均为 0，32 个实际 JAR 的运行前后摘要一致。完整数值、JVM 条件及重现命令见[流式验收](phase-102-semantic-streaming-replay-and-pdf-spool.md)。

临时盘采样使用共享句柄读取仍打开文件的当前长度，包含原子输出；文件短暂独占时刷新独立文件元数据并记录回退次数。该分支已用独占／增长／删除竞态分别验证，最终容量轮回退次数为 0。早期目录枚举漏计的峰值及采样中断轮不作为最终容量证据。语义来源 ID 集合受源节点预算约束；单页 PDF 对象不等于所有语义树恒定内存。观察堆不等于进程内存，有限容量不证明任意输入固定内存或无限期无泄漏。

## 复现

在仓库根目录使用 Java 21；Python 环境安装 `scripts/requirements-verification.txt`，配置 Poppler，准备 veraPDF。字体、原生制品和 SVG 浏览器均按 API 显式配置。

```powershell
.\gradlew.bat build sbom publishLocalBundle --no-daemon --console=plain
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<unique-validation-directory>" clean build run complexFlows openType streamingReplay gap21Complete verticalText streamingRowSpans gap21Graphics installDist resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
python scripts/verify_complex_flows.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_native_opentype.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_streaming_replay.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_gap21_complete.py --consumer-build-directory verification/consumer/build/<unique-validation-directory> --chromium <Chromium可执行文件>
python scripts/verify_production.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python .agents/scripts/verify_rules.py
```

Linux/macOS 使用 `./gradlew` 并显式选择本平台 native classifier；本轮没有据此宣称已完成跨平台实测。容量完整命令见专项验收。变宽／递归表格、最低行数／分栏、单向字段／媒体／合并、生产样例、能力样例和高级 PDF 等 **19 组既有输出独立回归全部通过**，不刷新历史视觉基线。

## 当前边界

这些关闭结论针对原表已定义目标：来源、预算、取消、原子输出和错误策略继续约束每个入口。SVG 仍为离线矢量子集；印刷色降级有实际损失；任意自定义原子文字不能靠裁切冒充跨页续排；流式快照由应用保证可重开。

README 两种语言、支持矩阵、路线图、执行计划和项目规约已同步。更广外部 DOCX、浏览器 CSS、设计器、跨平台／阅读器矩阵和正式分发继续归原有独立项，没有重新加回本轮四项的剩余清单。
