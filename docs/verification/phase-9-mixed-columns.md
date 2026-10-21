# 混合组件与表格连续分栏验收

## 范围和环境

本批把 `FlowColumn` 与 `GridTable` 接入既有 `RegionFlow`，支持从当前页剩余区域开始的文字／图片／形状混合流、嵌套内边距、逐占用栏表头和完整合并行组。构造器、旧分页方法、`MultiColumn` 整页／平衡模式及后端职责保持。指南见[混合分栏 API](../mixed-columns-api.md)，设计见 [ADR-0013](../adr/0013-mixed-content-regions.md)。

环境为 Windows、Java 21、Gradle 9.2.1，沿用显式 Liberation Sans、Noto Naskh Arabic 和霞鹜文楷 TC，以及既有 Python、pypdf、PDFium、Pillow、Poppler 工具。图片像素由项目代码生成，没有新增模块、依赖、外部资源或系统字体回退。

## 构建、测试和调用方

~~~powershell
.\gradlew.bat -g build/gradle-validation :export4j-components:test :examples:mixedColumns --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:production :examples:continuousColumns --no-daemon --console=plain
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_mixed_columns.py
python scripts/verify_continuous_columns.py
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
~~~

全量构建、SBOM、本地隔离 Maven 发布及调用方编译／运行通过。202 项测试，0 失败、0 错误、0 跳过。源码和测试继续使用 `-Xlint:all -Werror`，没有 Java 编译警告／错误；进阶样例两条缺字替换警告属于预期运行诊断。

新增 9 项回归测试覆盖图片仅一次、子组件测量一次、全部首组短栏利用、局部坐标和实际末片段高度；逐栏表头复用／正文行唯一性、合并行组移栏及表头不能单独成片段；嵌套内边距和对齐、区域间距重置、旧分页片段原子回退、零高度短区与普通正文接续；重复表头的 UTF-16 代理对警告和最终块／页上下文；非法尺寸、重复／跳过续区、超量输出及取消／同步范围恢复。

隔离调用方通过 Maven 坐标和 BOM 调用 `ContinuousColumns(FlowColumn(...))`，组合文字、图片和表格。新增 `consumer-mixed-columns.pdf` 共 3 页，正文原文 8 行、图片绘制 1 次、表头 2 次、4 个正文表格行各 1 次；尾块在第三页继续。独立脚本检查每页裁剪数 `[3, 2, 0]` 和全部原文／图片／表头覆盖，再由两套渲染器验证并人工复查全部新增页。

隔离调用方目录为 `verification/consumer/build/validation-9a0c3144a6a9`，成功指针为 `build/verification/consumer-build-directory.txt`。生产验证读取本次指针，不复用旧目录输出作为新结果。

主要日志位于 `build/verification/`：`mixed-regions-example.log`、`mixed-regions-build.log`、`mixed-regions-consumer.log`、`mixed-regions-independent.log`、`mixed-regions-text-regression.log`、`mixed-regions-advanced.log`、`mixed-regions-production.log`。

## 最终 PDF 几何和全部页面

`verify_mixed_columns.py` 根据明确的输入样例坐标表，从最终 PDF 检查文字绘制指令矩阵、ActualText 原文锚点和图片矩形，不调用 Java 布局器或后端读回作为几何依据。普通文字基线依据显式字体的 `head`／`hhea` 表计算。

验证时发现 pypdf 的右侧单元格文字回调可能在合成空格后带出已重置的 `Tm`。共享检查函数已改为在 `TJ`／`Tj` 指令之前捕获真实绘制矩阵，再与提取文字关联；原连续文字样例也重新通过此路径检查。这个修正仅涉及独立工具，不改变 PDF 绘制。

| 样例 | 页数／占用栏 | 结果 |
| --- | --- | --- |
| `mixed` | 2 页／4 栏 | 标题、固定图片、24 行正文、内部尾块和表格按顺序接续；图片仅一次，末片段后正文位于第二页 396 pt |
| `table` | 2 页／3 栏 | 首组每栏 5 个正文行，续页 11 行；表头逐栏重复，21 行左右单元格各出现一次，尾块位于 340 pt |
| `merged` | 2 页／1 栏 | 54 pt 剩余高度容不下表头与合并组，整组在第二页开始；跨两行图片只输出一次，尾块位于 184 pt |
| `nested` | 2 页／3 栏 | 双层内边距逐栏重复；中文／阿拉伯语／组合字符 24 行按 4／6／14 分配，尾块位于 372 pt |

共 8 页、11 个占用栏、114 个正文绘制文字命令（含实际重复表头）和 2 幅图片。最终位置最大误差小于 0.000001 pt，低于 0.003 pt 阈值。原文、单元格和图片顺序／覆盖，以及 SVG 页数、轮廓标签顺序与图片数均通过检查。

全部 8 页经 Poppler／PDFium 独立渲染并逐页人工复查，未见图片重复、文字覆盖、表格裁断或正文错位。两套渲染器最大均值通道误差为 1.360671；144 DPI PNG 缩放至 96 DPI 后相对 PDF 的最大误差为 1.497885，均低于既有阈值。结果位于 `build/verification/mixed-columns/`，摘要为 `report.json`，含全部渲染页及拼图。

## 回归和剩余边界

旧连续文字分栏 9 页继续通过原文、最终坐标、SVG／PNG 与双渲染器检查。语义表格、文本、自动分页和组件示例重新生成成功；生产示例 16 页继续通过结构、原文覆盖和独立渲染验证。

进阶样例 7 页继续通过结构、可编辑字段和视觉基线阈值。5 页基线差异为 0；第 4、5 页保留既有微小差异，均值通道误差 0.007064／0.009889，差异像素比例 0.0146%／0.0204%。原因未确定，本批未刷新基线。Edge 保存副本 7 页复用原人工证据，未重新操作阅读器。

普通组件和旧分页片段保持原子性，合并行组与单元格内部不拆栏；过高内容明确失败。连续栏自动平衡、变栏宽、语义分节栏、DOCX 多栏映射、跨页浮动锚点和 PDF/UA 阅读顺序仍未实现。持续压力、跨系统／JDK 与进程 RSS／资源释放仍没有完整证据。

本次仅验证忽略目录中的本地隔离 Maven 仓库，保留 `com.mirakyux.export4j` 包名和制品坐标。
