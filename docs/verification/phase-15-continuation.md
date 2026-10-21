# 阶段十五验收：嵌套文字与合并格续页

日期：2026-10-15。范围为 GAP-01、GAP-02，以及 GAP-03 的命名区分。其余能力仍见[缺口表](../capability-gaps.md)。本批算法自主实现，未新增生产依赖、字体或第三方样本文档。

## 交付与兼容

- `FlowColumn.Builder.text()` 创建自然续页文字，`fixedText()` 表达最小高度及整块移动。已有短文字的样式默认值保持。
- `GridTable.allowRowSplit` 默认 `false`，旧四／五参数构造器保留。显式拆分支持标准文字、OpenType 文字、FontScope 及混合列内图片；固定／自定义组件保持原子性。
- 文字一次测量，续页按完整行消耗。表头、背景、边框和内边距逐区重复，短格文字与图片仅输出一次。未消耗内容保留剩余高度，不裁切、不缩字。
- `.grid()` 在顶层和列内明确表达组件网格；列内旧 `.table()` 保留兼容。语义表格区域流、富文本字段合并和 DOCX 合并尚未实现。

契约及回调源码迁移见[API 指南](../grid-continuation-api.md)和 [ADR-0019](../adr/0019-grid-cell-continuation.md)。`GridTable` 的 record 分量增加；项目尚未发布稳定 API。

## 行为测试

全仓共 **266 项 JUnit**，0 失败、0 错误、0 跳过。新增 12 项测试覆盖：

- 普通嵌套文字完整行续页，固定文字超高明确失败。
- 120 行长合并格、短首区、左右不等长内容、逐页表头及重复调用确定性。
- 默认整组行为、固定格不自动拆分、超高原子内容与无空间表头失败。
- 分数区域高度、续页内边距、末行完整及首行缩进不重新应用。
- 图文混合格的图片唯一，自定义组件只测量一次。
- 错位跨行格、反向格声明、每格原文顺序及唯一性。
- 固定种子 40 组长短文字、内边距和区域高度组合；不产生未绘制却被消耗的行。
- 两格均无法开始的短首区跳过，即使其高度足以覆盖一条自然逻辑行。
- 显式字体作用域、原 UTF-16 缺字位置、表头警告按实际重复呈现。
- 准备阶段取消及调用方执行作用域恢复。

全仓构建、Javadoc、源码包、SBOM 与本地 Maven 制品通过。Java 编译沿用 `-Xlint:all -Werror`；未用宽泛抑制规则消除警告。隔离调用方仅引用发布坐标，不访问内部准备流类型。

## 独立输出检查

合成样例全部由项目代码生成，使用既有已登记字体；双色图片由 RGB 字节生成，无真实病例数据。缺字警告为 0；复杂文字允许有正常字体回退 INFO。

| 样例 | 结果 | 检查 |
| --- | --- | --- |
| 120 行中文长格，跨 2 行合并 | 3 页，120 行恰好一次 | 逐页表头，中文完整，两个右侧短格唯一，13 个可见格片段 |
| 短首区图文混合格 | 3 页，17 行、1 幅图片 | 图片完整且唯一，图后文字和后续正文保留，13 个格片段 |
| 合并格连续双栏 | 3 页，46 行 | 首组短栏，逐占用栏表头，21 个格片段，尾部正文保留 |
| 中文、阿拉伯文与组合字符 | 3 页，26 行 | ActualText 与 SVG 来源一致，完整行矩形位于格内 |
| 普通嵌套文字与固定标题 | 4 页，40 行 | 来源有序且唯一，固定标题与尾部文字各一次 |
| 隔离 Maven 调用方 | 4 页，24 行 | 新 `.grid()`、显式拆分、自然文字及固定文字入口运行成功 |

`pypdf` 检查原文、最终格矩形、表头、图片和复杂行锚点；PDFium 检查最终字形边界。没有面积重叠或越出页边距的内容。SVG 独立解析检查来源顺序。

全部 20 页使用 Poppler／PDFium 在 96 DPI 渲染并人工复查。沿用 blur 1 px、平均通道误差小于 4、变化比例小于 0.06 的既有容差；本批最大值分别为 **3.346339**、**0.041231**。未更新历史视觉基线。

进阶 PDF、混合栏、生产输出与 ADR 表单独立回归通过。原 ADR 密集样例保持 3／4 页、114 格、16 个选中标记、两种表头声明顺序均 98 pt；其私有固定勾选组件仍不支持格内续页，超高样例仍明确失败。

## 命令与证据

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:gridContinuation :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:mixedColumns :examples:production :examples:adrForm --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe scripts/verify_grid_continuation.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_advanced_pdf.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_mixed_columns.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_adr_form.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
~~~

新样例输出在 `examples/build/grid-continuation/`，独立报告及联系表在 `build/verification/grid-continuation/`。日志使用 `build/verification/gaps-*.log`。隔离调用方成功目录从 `build/verification/consumer-build-directory.txt` 读取，不检查占用的旧目录。

检查工具：pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 及本机 Poppler。首次样例校验因 Arabic 字体栈固定行框不足失败，已明确为复杂文字配置 22 pt 行距；正常字体回退 INFO 保留，不按缺字警告处理。首次调用方检查因旧成功目录缺少新增输出失败，随后使用本批独立构建目录通过。

## 未验证与后续

没有宣称任意嵌套组件均可拆分、语义表格与网格统一、富文本字段合并、一般 DOCX 无损映射、完整 OpenType 或阅读器通用搜索兼容。本批未新增长期压力、RSS 容量或跨系统阅读器证据；原 PDF/A／签名实现未改变，也未重新执行合规验收。

后续优先推进 GAP-03 的语义区域流、GAP-04 默认样式贯通和 GAP-05 通用静态表单。竞争库没有进入生产依赖或分发。
