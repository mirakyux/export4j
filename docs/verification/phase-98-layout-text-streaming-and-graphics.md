# 阶段九十八：变宽纵向续排、竖排、单向合并与绘制透明度

本阶段固定推进 GAP-03／08／09／21 的四个单元。它们均完成实现和独立验收；各主项及更广扩展仍按[缺口表](../capability-gaps.md)保留明确边界，没有新增缺口编号。

## 交付范围

| 缺口 | 已交付行为 | 保留边界 |
| --- | --- | --- |
| GAP-03.1 | [变宽行内格纵向 owner 续排](../variable-table-continuation-api.md)：按完整原文及实际区域宽度准备，保留原锚点／UTF-16、行号、图片、链接和最终字段；空覆盖只延长 owner 矩形，全覆盖行不产生虚假正文 | 变宽递归块子树、组件网格及复杂浮动未接入；不缩放既有布局冒充重排 |
| GAP-08.1 | [显式竖排字格](../vertical-text-api.md)：CJK 直立、ASCII 逐字侧置、选定竖排标点、两种列顺序及换列来源；显式字体、缺字策略和预算保持 | 原子组件不跨页拆分；通用竖排字体特性、组合／连接脚本、纵中横、可变／彩色字体和 CFF2 继续开放 |
| GAP-09 | [有界单向纵向合并](../streaming-row-spans-api.md)：显式启用固定列正文，按有限传递连通组读取、准备和原子分页，不预读下一独立组；默认仍为单行预读 | 表头纵跨、与拆行组合、块格、嵌套和 Auto 列仍拒绝；PDFBox 页树／COS 对象仍累积 |
| GAP-21.1／21.2 | [独立填充／描边透明度和 SVG 仿射变换](../graphics-opacity-api.md)：三后端一致的逐次 source-over、嵌套状态恢复、继承透明属性和有界变换解析 | 组透明度、混合模式、CMYK／专色／完整 ICC、渐变、图案、滤镜和更广裁剪组合继续开放 |

单向合并保留旧构造器；新增 record 分量的反射／解构形状变化在 API 中明确。透明度属于不绘制的状态命令，仍计命令预算；PDF/A-1b 拒绝非不透明值，PDF/A-2b 的最终透明文件另经合规检查。设计见 [ADR-0098](../adr/0098-explicit-vector-opacity-and-svg-transforms.md)。

## 构建与独立调用方

Java 21、Gradle Wrapper 9.2.1 的最终源码通过 `build sbom publishLocalBundle`，JUnit XML 汇总为 **180 个测试类、1,677 项测试，失败／错误／跳过均为 0**。本阶段新增 43 项行为测试：变宽纵向 owner 2 项、竖排 18 项、单向合并 13 项、透明度与 SVG 10 项，覆盖来源唯一性、实际几何、预算、取消、源消费、失败原子性及状态恢复等回归。

新的隔离 Maven 调用方通过全部 11 个任务，仅解析本次本地仓库的发布坐标。15 个库、BOM 的 15 条约束、26 个 SBOM 运行时成分及调用方解析结果一致，JAR／源码／Javadoc／POM／摘要和必要许可经独立核对。本地 bundle 验收不表示正式远程发布。

Windows 从仓库根目录复现：

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:variableSplitTables --no-daemon --console=plain
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=build/<unique-validation-directory>" clean build run verticalText streamingRowSpans gap21Graphics resolvedLibraries --refresh-dependencies --write-locks --no-daemon --console=plain
python scripts/verify_variable_vertical_spans.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_gap08_vertical_text.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_streaming_row_spans.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_gap21_graphics.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
python scripts/verify_production.py --consumer-build-directory verification/consumer/build/<unique-validation-directory>
```

Linux/macOS 使用 `./gradlew`。Python 验证依赖为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0，PDF 独立渲染使用 Poppler 26.07.0 和 PDFium。图形验证还要求显式可用的 veraPDF，位置可通过脚本参数设置。调用方构建目录必须为本次成功生成的目录。

## 输出验收

| 单元 | 实际文件及检查 | 结果 |
| --- | --- | --- |
| 变宽 owner | 非平衡／平衡样例各 4 页、新坐标调用方 6 页；完整来源顺序、唯一性、实际栏宽、覆盖矩形、最终页字段、图片像素及图文外链 | 14 页、28 幅双引擎图像全部查看；边界内无越界或缺失，两个渲染器的可见墨迹覆盖差为 0 |
| 竖排 | 4 组 PDF／SVG／PNG；UTF-16、字格／列序、选定标点、实际像素方向及 PDFium 提取／查询／选中 | 4 页、8 幅双引擎图像及 4 幅原生 PNG 全部查看；4 次完整非空白原文查询各命中一次，选中文字一致；最大平均通道误差 0.171597 |
| 单向合并 | 3 页合并组、1 页传递组；计数、真实 PAGE、物理格几何、图片／链接、晚期源失败的旧目标保护、关闭及临时文件清理 | 4 页、8 幅双引擎图像全部查看；最大平均通道误差 0.526962；基础样例 7 行、12 个真实源格、8 次读取、关闭 1 次 |
| 绘制／SVG | paint、svg-input、透明 PDF/A-2b 各 1 页及 2 份 PNG；62 处像素控制、文字透明／搜索、继承覆盖、状态恢复、非交换变换／中心旋转／skew、viewBox 裁剪和 8 次拒绝 | 3 页、6 幅双引擎图像及 2 幅原生 PNG 全部查看；最大平均通道误差 0.197348，透明最终文件通过 veraPDF PDF/A-2b 检查 |

新增共 **25 页 PDF、50 幅双引擎图像及 6 幅原生 PNG** 完成逐页复查。SVG 独立检查覆盖 XML 属性及对应几何，未执行浏览器 SVG 渲染，不记作浏览器兼容通过。竖排 PDFium 选区为逻辑锚点矩形，不能当作完整轮廓覆盖或精确脱敏框，也不表示通用阅读器或 PDF/UA 合规。

10 组既有独立回归全部通过：变宽拆行、变宽原子表格、递归表格、单向字段、单向横向合并、单向图片／链接、生产样例、能力样例、高级 PDF 及混合栏。规则检查、文档链接和 Git 差异检查通过。

## 单向合并容量

三组分别使用新调用方发行目录中的发布坐标 JAR，在独立 Java 21 进程中以 `-Xmx64m` 运行。每组 `maxBufferedRows=3`，每页回调检查读取不超过一个待放置连通组；不收集完整来源或所有布局页。

| 正文行数 | 输出布局页 | `next()` 次数 | 来源关闭 | JVM 最大堆 | 布局耗时 |
| --- | --- | --- | --- | --- | --- |
| 1,000 | 24 | 1,001 | 1 | 64 MiB | 0.226 s |
| 10,000 | 239 | 10,001 | 1 | 64 MiB | 0.490 s |
| 100,000 | 2,381 | 100,001 | 1 | 64 MiB | 1.084 s |

复现时对 `streamingRowSpans` 任务分别传入 `--args="--capacity 1000"`、`10000`、`100000`；任务已设置 64 MiB 最大堆。运行三组后重新执行单向合并独立验证器，它会核对生成的容量计数。

耗时不含 JVM 启动及字体加载，也不含绘制／PDF 编码。最大堆配置不是 RSS 或堆峰测量，本组结果不证明真实 PDF 恒内存、长期无泄漏或跨平台性能。长期容量继续归 GAP-09。

## 状态整理

GAP-04 的继承、角色、Java／XML 主题及分域／图层级联已有既定证据，登记为已验收。GAP-07 的安全声明式模板、递归 include、源码 ZIP 及声明式恢复登记为已验收子集；完整 CSS 仅归 GAP-18.1，设计器仅归 GAP-13。父项与扩展项不相加计数。

当前状态、剩余目标与历史记录分开展示；历史阶段中的“下一项”只反映当时顺序。GAP-03／08／09／21 的本阶段单元已验收，更广范围继续保留部分支持状态。
