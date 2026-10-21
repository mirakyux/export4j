# 当前页连续文字分栏验收

## 范围与环境

本批补齐当前页剩余区域中的连续文字分栏、完整续栏、最低行数移页、三栏与后续正文接续。新增核心 `RegionFlow`／`FlowRegions` 和组件 `ContinuousColumns`，`FlowText`／`OpenTypeText` 一次换行后分配完整行；旧 `MultiColumn` 的整页／有界平衡行为保持。指南见[连续分栏 API](../continuous-columns-api.md)，设计见 [ADR-0012](../adr/0012-continuous-text-regions.md)。

环境为 Windows、Java 21、Gradle 9.2.1，继续使用显式 Liberation Sans、Noto Naskh Arabic 与霞鹜文楷 TC。独立工具沿用[前批记录](phase-7-multi-floats.md)中的 Python、pypdf、PDFium、Pillow 与 Poppler。没有新增模块、Maven 运行依赖、Python 依赖或外部资源。

## 构建和测试

~~~powershell
.\gradlew.bat -g build/gradle-validation :export4j-components:test --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation :examples:continuousColumns --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:componentPdf :examples:advancedPdf :examples:production --no-daemon --console=plain
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_continuous_columns.py
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
~~~

全量构建、SBOM、本地隔离 Maven 发布和独立调用方编译／运行通过。193 项测试，0 失败、0 错误、0 跳过；生产和测试代码继续使用 `-Xlint:all -Werror`，本批编译没有 Java 警告／错误。进阶样例的两条缺字替换警告是预期运行诊断。

新增 8 项测试覆盖首组短栏／完整续栏、一次调用、原文顺序、栏间坐标、实际末片段高度和正文接续；最低断点行数、零／不同前缀高度、短栏跳过和不可能约束；复杂塑形／首行缩进保持；严格缺字与占位符 UTF-16 代理对位置／最终页上下文；非法栏宽、数量和尺寸、重复／跳过续区的自定义输出、固定区域拒绝跨页，以及取消／范围恢复。

复杂文字测试同时比较同一栏宽的未分页绘制结果与连续分栏结果，检查非空格字符顺序，并对显式 `OpenTypeText` 检查完整逻辑原文。普通段落路径原有的换行空格消耗规则没有改变，不能把这个规则误报为本次分栏丢字。

隔离调用方通过 Maven 坐标与 BOM 使用新 API，生成 `consumer-columns.pdf`，3 页、22 个原文行和 5 个占用栏。编译结果位于独立目录 `verification/consumer/build/validation-62b944318d02`，成功指针为 `build/verification/consumer-build-directory.txt`。生产验证读取本次指针，检查全部原文、每页裁剪数 `[2, 2, 1]`、后续正文和全部独立渲染页。

日志位于 `build/verification/`：`continuous-tests.log`、`continuous-example.log`、`continuous-build.log`、`continuous-consumer.log`、`continuous-independent.log`、`continuous-advanced.log`、`continuous-production.log`。

## 独立几何和全部页面

`verify_continuous_columns.py` 从最终 PDF 读取文字内容、变换后的文字基线和 ActualText 锚点，按输入页面尺寸、栏数、间距、前置高度及显式样例分配表检查坐标。普通文字基线使用 Python 标准库独立读取所配置字体的 `head`／`hhea` 表；不调用 Java 布局器或 PDF 后端读回作为几何依据。

| 样例 | 页数／正文行 | 独立结果 |
| --- | --- | --- |
| `partial` | 2 页／42 行 | 首组各 14 行，续页左栏 14 行；尾块从第二页 336 pt 开始 |
| `short` | 1 页／6 行 | 只占首栏，尾块从 240 pt 开始，不保留空栏整页高度 |
| `moved` | 2 页／5 行 | 首组仅容一行，最低两行策略使正文从第二页开始；尾块位于 138 pt |
| `three` | 2 页／24 行 | 首组各 5 行，续页左栏 9 行；尾块位于第二页 226 pt |
| `shaped` | 2 页／36 行 | 首组各 14 行，续页 8 行；中文／阿拉伯语／组合字符保留，尾块位于 204 pt |

共 9 页、113 个正文行和 12 个占用栏。所有原文行按顺序完整出现一次；最终坐标最大误差约 0.00000667 pt，低于 0.003 pt 阈值。逐行检查首组剩余高度、完整续栏、栏间位置与正文边界，并验证最后实际栏高后的普通正文恢复全宽。

全部 9 页通过 Poppler／PDFium 独立渲染并逐页人工复查，复杂混排首组另以完整尺寸检查。两个渲染器的最大均值通道误差为 1.575820；144 DPI PNG 缩放到 96 DPI 后相对 PDF 的最大误差为 1.896561，均低于既有阈值。SVG 独立解析的页数、轮廓标签及原文顺序与 PDF 一致。隔离调用方新增的 3 页也已独立渲染并人工复查。

结果、全部独立渲染页与拼图位于 `build/verification/continuous-columns/`，摘要为 `report.json`；调用方渲染位于 `build/verification/production/consumer-columns/`。本批没有修改持久视觉基线。

## 原有能力和剩余范围

生产样例的 16 页继续通过结构、原文覆盖和双渲染器检查，包含旧 `FloatText` 的跨页回归。进阶样例 7 页通过结构、可编辑表单、双渲染器与基线阈值检查。5 页与旧基线像素差异为 0；第 4、5 页仍有既有小差异，均值通道误差 0.007064／0.009889，差异像素比例 0.0146%／0.0204%。原因未确定，未更新基线，也不宣称全部页面零差异。

既有 Edge 实际保存副本 7 页复用原人工证据，本批未重新操作阅读器；自动字段往返与真实阅读器保存证据分别记录。

连续区域首版为 `FlowText`／`OpenTypeText` 文字流，尚不接受 `FlowColumn`、表格或任意混合组件，也不提供连续栏自动平衡、变栏宽、语义节栏或 DOCX 多栏映射。行内动态语义、合并格拆页、任意轮廓／跨页浮动锚点、DOCX 完整页区／交互、竖排、可变字体、PDF/UA 与长期签名验证仍待推进；跨系统和持续压力／进程 RSS／资源释放也没有完整证据。

本次发布仅写入忽略的本地隔离 Maven 仓库，包名／制品坐标继续使用 `com.mirakyux.export4j`。
