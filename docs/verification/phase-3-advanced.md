# 阶段三进阶 PDF 验收记录

- 日期：2026-10-14
- 版本：本地 `0.1.0-SNAPSHOT`
- 环境：Windows 11（build 26300）、Intel Core Ultra 5 125H（14 核／18 线程）、约 32 GiB 物理内存、JDK 21.0.10、Gradle Wrapper 9.2.1
- 范围：进阶报告组件与字体组策略；既有阶段记录保留其历史事实

## 构建与测试

实际通过的完整命令：

~~~powershell
.\gradlew.bat -g build/gradle-validation build :examples:run :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf --no-daemon --console=plain
~~~

最终补充多页目录奇偶模板断言后，再运行 `build :examples:advancedPdf`（使用同一 `-g` 和 `--no-daemon --console=plain`），构建通过；以下测试数来自最终 XML 结果。

结果：10 个项目模块构建成功，包含 Javadoc、源码 JAR、示例 ZIP／TAR；Java 编译使用 `-Xlint:all -Werror`。测试总数 128，失败和错误均为 0。

| 模块 | 测试数 |
| --- | --- |
| PDF 后端 | 31 |
| 组件 | 22 |
| 字体 | 4 |
| 绘制 API | 5 |
| 绘制核心 | 4 |
| 布局核心 | 37 |
| 模型 | 11 |
| 文本 | 14 |

原有示例重新运行：精确绘制 2 页、文本 1 页、段落报告 3 页、表格报告 3 页、基础组件报告 4 页。综合示例为 7 页。

默认 Gradle 用户目录出现 Kotlin DSL 解析失败（包括 `Unresolved reference: plugins`）。使用独立 `-g build/gradle-validation` 后，同一构建脚本正常通过；未删除或修改全局缓存，也不宣称默认目录已修复。首次下载仍执行 Wrapper 分发 SHA-256 和 Maven 制品校验。补齐现有 JUnit BOM 的两个校验项，未更改 Java 依赖版本。

## 行为覆盖

- 分页：最低片段行数、不可满足约束立即失败、保持相邻链、显式分页与无多余空页。
- 富文本：真实字体角色、颜色、字号、上下标、装饰和跨页 UTF-16 来源保留，未知字体角色带块上下文失败。
- 字体组：严格模式缺字失败、整个组回退优先、占位替换警告、补充平面码点来源长度、占位符也缺字时失败、非法 Unicode／复杂塑形不被替换掩盖。
- 组件：嵌套 Column 的内边距与来源顺序、首片段自适应、合并行组、重复表头、跨界／重叠／缺失槽拒绝。
- 分节：混合纸张、显示页码重置、首页／显示奇偶模板、目录与真实最终页链接。
- 语义：编号、题注、数字／页码引用、脚注页底预留与尾注编号。
- 资源：PNG／JPEG 解码及预算、SVG 子集与外部资源拒绝、alpha soft mask。
- 表单：完整字体嵌入、首次未使用的中文字符后续填写、下拉框、单选字段父子结构、保存重开、字段可编辑性。
- PDF 字体预算：正文与表单共享原始字体字节上限，同一字体去重，不同字体合计超限在写输出前失败。

源码包名、SPDX、模型 JDK-only 与公共接口第三方类型隔离另做静态自查；公共 API 的兼容重载和变化记录于 [ADR-0007](../adr/0007-advanced-layout-and-font-policy.md)。

## 独立 PDF 验证与视觉基线

工具版本：Python 3.12.14、pypdf 6.10.0、pypdfium2 5.13.0（PDFium 153.0.7999.0）、Pillow 12.3.0、Poppler 26.07.0。Java 运行库不依赖这些验证工具。

~~~powershell
python -m pip install -r scripts/requirements-verification.txt
python scripts/verify_advanced_pdf.py
~~~

本机执行使用已安装工具的 Python 完整路径；上述为等价的调用方命令。脚本不以 PDF 字节相同作为断言，结果在 `examples/build/advanced-verification/report.json`。

综合样本检查结果：

| 项目 | 结果 |
| --- | --- |
| 页面 | 7 页，其中 5 页竖向、2 页横向 |
| 目录／书签 | 4 个书签，最终物理页索引为 1、2、3、5 |
| 内部链接 | 6 个：4 个目录、2 个交叉引用 |
| 嵌套流 | FLOW-01～FLOW-62 顺序完整，无重复／遗漏 |
| 合并表格 | GROUP-01～GROUP-12 均存在，表头在两页重复 |
| 透明图像 | 13 次图像资源引用带 `/SMask`；不是 13 个不同图像 |
| 表单 | 4 个规范字段、5 个页面 widget，包含两个单选子 widget |
| 填写保存重开 | 中文姓名、多行备注、下拉选择和单选当前值全部更新，字段树与 widget 一致，外观非空，可编辑 |
| 原始 PDF | Poppler 与 PDFium 均渲染全部 7 页；PDFium 显式初始化表单环境 |
| 填写后 PDF | PDFium 渲染全部 7 页，检查第 3 页新中文值及选择状态 |

全部原始页面已人工检查，页眉页脚、方向切换、文字、脚注、合并边框和续页顺序正常。第 2 页的两个方框是显式缺字策略演示，并对应两条 `MISSING_GLYPH_REPLACED` 警告，原码点 U+4E2D／U+6587 替换为 U+25A1；其余中文使用真实字形。

视觉基线存入 `verification/baselines/advanced-poppler/`，96 DPI，7 个 PNG 及环境 manifest。基线只在全部页面检查后用 `--update-baseline` 显式建立；常规命令不会刷新。

同一渲染器与基线比较不模糊：平均通道绝对差最多 0.7／255，灰度差至少 25 的像素占比最多 2%。本机复跑各页两项差异均为 0。跨渲染器同时记录原始差异；因字形基线取整与抗锯齿不同，以半径 1 像素的高斯模糊辅助比较，要求平均差小于 4／255、明显变化像素小于 6%。最大实测值约 3.212 和 4.931%，位于密集文字页。该容差不能证明每个像素完全一致，需结合结构断言和人工检查。

另用相同尺寸白页替代第 6 页作为负向控制，平均差约 9.986、明显变化像素约 7.999%，超过基线阈值。负向图像只写入构建目录，未修改已审阅基线。

pypdf 6.10.0 的高层文本表单填充对本样本的完整 Type0 ToUnicode CMap 触发 `AttributeError: 'int' object has no attribute 'encode'`。未修改依赖或扁平化字段：验证脚本使用 pypdf 解析／写回，按样本完整 Identity-H／Identity CID 映射重建固定字号单字体文字外观；单选仍使用高层填充 API。此辅助代码只用于受限验收样本，不能视为通用表单编辑器或任意阅读器兼容证据。Java 测试另通过 PDFBox 实际填写、保存和重开新中文字符。

## 大文档性能基线

~~~powershell
.\gradlew.bat -g build/gradle-validation :examples:benchmark --no-daemon --console=plain
~~~

样本为 5,000 个段落、250 页；固定 Liberation Sans 字体，字体读取不计入计时，先以 200 个段落预热，再串行布局三次，JVM 最大堆 1 GiB。原始记录位于 `examples/build/layout-benchmark.json`。

| 指标 | 实测 |
| --- | --- |
| 三次耗时 | 330.833 ms、219.059 ms、122.938 ms |
| 中位耗时 | 219.059 ms |
| 堆内存池峰值之和 | 201,059,968 字节，约 191.7 MiB |
| JVM 最大堆 | 1,073,741,824 字节 |

堆内存池峰值可能发生在不同时间，该和不等于进程 RSS 或精确同时堆峰值。样本只测布局，不含 PDF 导出、复杂表格或并发；这是单机轻量基线，不是 JMH、SLA 或发布容量保证。未因文档更新反复测量并挑选最佳结果。

## 验证边界

托管 Windows／Linux CI 已配置六个示例及 Linux 独立验证，但本次尚未推送运行；Linux 的 Poppler 版本可能不同，首次运行需核对像素差异。没有图形 PDF 阅读器中的人工点击、批注编辑和保存证据，不能宣称所有阅读器兼容。

尚未实现复杂塑形、双向文字、完整中文禁则、图文绕排、多栏、完整 SVG、DOCX 或 SVG／Raster 后端。GridTable 合并行组与 Row 不可拆分；脚注采用保守的全节预留；引用固定单行；表单只用一个显式字体。正式发布、SBOM、并发压力和长期大文档验证仍需后续工作。
