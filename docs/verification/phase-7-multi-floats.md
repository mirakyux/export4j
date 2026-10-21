# 多浮动组件绕排验收

## 范围和环境

本批补齐同一首片段内多个左右矩形的逐行避让、错位／堆叠、封闭行带跳过、整组移页与浮动独占片段。旧 `FloatText` 委托共享算法，公共调用方式保留。指南见[多浮动 API](../multi-floats-api.md)，设计见 [ADR-0011](../adr/0011-multiple-rectangular-floats.md)。

环境为 Windows、Java 21、Gradle 9.2.1，继续使用显式 Liberation Sans、Noto Naskh Arabic 与霞鹜文楷 TC。独立工具沿用[上一批记录](phase-6-inline-images.md)中的 Python、pypdf、PDFium、Pillow 与 Poppler。RGB 图片由项目代码生成，没有新增模块、Maven 运行依赖或外部资源。

## 构建和测试

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:componentPdf :examples:advancedPdf :examples:production --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation :examples:multiFloats --no-daemon --console=plain
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_multi_floats.py
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
~~~

全量构建、SBOM、隔离 Maven 发布和调用方编译／运行通过，185 项测试，0 失败、0 错误、0 跳过。生产与测试源码继续使用 `-Xlint:all -Werror`，本批构建没有 Java 编译警告／错误。进阶样例输出的两条缺字替换警告属于预期运行诊断。

新增 7 项回归测试覆盖左右错位区域的全部行带、非整行顶部／底部、原文与组合字符顺序、浮动仅一次、列表顺序对正文几何无影响、封闭行带跳过、整页封闭的有限推进、剩余页高与后续组件位置、空文字／空列表、空正文整页浮动不追加空白续页、正文／子组件警告保留、UTF-16 代理对诊断、实际碰撞／越界／窄行／超高行／超量浮动／超尺寸子组件拒绝，以及协作取消与范围恢复。

隔离调用方使用 12 个库的 Maven 坐标和 BOM，新增公开 `MultiFloatText`／`FloatBox` 调用并生成 `consumer-floats.pdf`。仍使用独立构建目录并写入 `build/verification/consumer-build-directory.txt`；生产验证检查最新调用方原文、两个裁剪区域和双渲染器结果。

主要日志位于 `build/verification/`：`multi-float-tests.log`、`multi-float-example.log`、`multi-float-build.log`、`multi-float-build-final.log`、`multi-float-consumer-final.log`、`multi-float-independent-final.log`、`multi-float-advanced.log`、`multi-float-production-final.log`。

## 独立几何与全部页面

`verify_multi_floats.py` 直接解析最终 PDF 的内容流、图形变换、填充矩形和 ActualText 不可见锚点，不调用 Java 布局器或后端读回作为依据。预期浮动坐标来自输入矩形、页面设置及首片段剩余高度。

| 样例（`examples/build/multi-floats/`） | 结果 |
| --- | --- |
| `staggered` | 3 页、3 个浮动框、51 个正文行；左图／右卡片／下方左卡片同时或错位避让，中文／英文／阿拉伯语／组合字符顺序保留 |
| `blocked` | 1 页、2 个浮动框、2 个正文行；左右框封闭前两行带，首行从第 44 pt 开始 |
| `moved` | 2 页、2 个浮动框、2 个正文行；剩余高度不足时整组在第二页开始，较低浮动框不被截断或丢弃 |
| `float-only` | 2 页、1 个全宽／全高浮动框、2 个正文行；第一页仅输出浮动内容，第二页继续原文，不重复浮动框 |

共 8 个浮动框和 57 个正文行。所有正文 ActualText 按顺序拼接与原文（除明确换行符）完全一致；最终矩形坐标、宽高及文字行带误差小于 0.003 pt。逐行验证所有相交矩形的水平留白、固定网格、正文边界及续页全宽。两幅生成图片各输出一次。

全部 8 页通过 Poppler／PDFium 渲染并人工复查，错位绕排页另以完整尺寸检查；未见正文与浮动区域相交、文字丢失、矩形越界或重复。两个渲染器的最大均值通道误差为 1.489009；144 DPI PNG 缩放到 96 DPI 后的最大均值通道误差为 1.453993，均低于既有阈值。SVG 独立解析的页数、图片数和正文顺序与 PDF 一致，尚未在 Word／LibreOffice 中验证。

结果、全部渲染页和拼图保留于 `build/verification/multi-floats/`，摘要为 `report.json`。本脚本不修改持久视觉基线。

## 原有能力与边界

生产样例的 16 页继续通过结构、原文覆盖和双渲染器检查，其中旧单矩形 `FloatText` 保持 2 页及其续页行为。隔离调用方的新增 1 页也由独立工具渲染并人工复查。既有 Edge 实际保存副本 7 页复用原人工证据，本批没有重新操作阅读器。

进阶样例 7 页通过结构／可编辑表单／双渲染器和基线阈值检查。与此前相同，5 页基线像素差异为 0；第 4、5 页的表格编号字形有少量差异，均值通道误差 0.007064／0.009889、差异像素比例 0.0146%／0.0204%。原因尚未确定，未更新旧基线，也不宣称全部页面零差异。

本批浮动框仅位于组件首片段，按声明的矩形边缘避让；不提供多边形轮廓、跨页浮动锚点或自动拆分浮动内容。正宽区间容不下字形簇时明确失败。行内动态域／引用／脚注锚点、当前页连续分栏、合并格拆页、DOCX 页区／浮动锚点／完整交互、竖排、可变字体、PDF/UA 与 PAdES 长期验证仍待推进。持续压力、跨系统／JDK 与进程 RSS／资源释放也没有完整证据。

本次发布仅写入忽略的本地隔离 Maven 仓库。
