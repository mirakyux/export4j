# 阶段十八验收：语义表格区域续排

日期：2026-10-15。推进 GAP-03 的 PDF 区域流子目标，复用核心表格算法，自主实现区域分配与呈现身份。没有新增第三方依赖、字体或外部素材；不扩大为 DOCX 嵌套或长合并网格字段支持。

## 行为与兼容

- `DocumentLayouter.layoutTableRegions` 返回独立逻辑区号及本地 TableFragment，复用列宽、富文本、字段、图片和完整行拆分。短初始区可跳过，完整续区必须推进；不生成空白片段。
- `SemanticTable`／`.semanticTable(Table)` 在 FlowColumn、嵌套列及 ContinuousColumns 中自然续排。完整表头逐区重复，PAGE／PAGES／引用字段在最终物理页、目录偏移和分节编号确定后解析。
- 显式 `allowRowSplit` 保留每格完整行、原 UTF-16 范围、firstLineIndex、continuedRow、图片及外链。原语义表格默认整行移动，不缩字或裁切。
- SemanticPlacement 增加片段序号及总数，旧五参数构造表示完整单片段。组合与字段解析保留元数据。注册器要求同宿主、同 Table 实例及字体配置的连续完整流，源预算只计一次，片段及输出预算逐次计入。
- FontScope 传递所有短初始区，避免先消耗一个短栏后将第二栏误当作完整页片段。
- 固定 RowComponent、BoxComponent、网格单元格及直接 `layout()` 仍要求单视口。GridTable 表头重复完整语义源、旋转／缩放／部分裁切及页区语义装饰继续明确拒绝。

契约见[语义组合 API](../semantic-components-api.md)和 [ADR-0022](../adr/0022-semantic-table-region-flow.md)。项目尚未发布；record 保留旧构造的源码兼容，不构成二进制兼容承诺。

## 构建与行为测试

Gradle XML 汇总为 **51 个测试类、313 项 JUnit**，0 失败、0 错误、0 跳过。本批增加 20 项回归，覆盖：

- 最多 16 个短区跳过、不计占用页预算；短区消耗与中途短区跳过。
- 完整行顺序、重复表头布局对象复用、拆行行号及图片／字段原位置。
- 目录偏移、重启编号、同页双栏字段及首片段书签。
- 重复／缺失／乱序片段、来源／宿主／片段总数／字体变化与跨分节复用拒绝。
- 源码点和单元格只计一次；实际片段、重复表头、补空续格及绘制文字仍受输出预算限制。
- 取消恢复、固定单视口失败、真实字体策略及代理对 UTF-16 诊断。
- FontScope 保留两个短首栏及整组字体策略。

全仓 build、Java `-Xlint:all -Werror`、Javadoc、SBOM 和本地 Maven 制品通过。隔离调用方从坐标重新解析、编译和运行成功。调用方没有 JUnit 源码，其运行断言不计入 313 项。

## 独立输出验收

三个新示例 11 页、隔离调用方 6 页，共 **17 页**均通过最终 PDF 结构与几何检查、Poppler／PDFium 独立渲染及全部页面视觉复查。

| 场景 | 固定合同与结果 |
| --- | --- |
| 双层嵌套列与短首区 | 4 页含目录及目标分节；18 个正文标记顺序唯一；2 个表片段、20 个描边单元格；表头显示 2／3，目标显示 7 |
| 连续双栏 | 4 页含目录及目标分节；23 个正文标记顺序唯一；3 个表片段、26 个描边单元格；同页两栏均显示 2，续页显示 3，目标显示 9 |
| 显式拆行长格 | 3 页、36 个完整行标记；字段原始行 4／14／29 分别解析为页 1／2／3；6 个描边单元格；2 张图片各一次，4 个图文外链与最终几何匹配 |
| 隔离 Maven 调用方 | 6 页；30 个正文标记顺序唯一，每页表头与 5 个正文行；6 个 PAGE／PAGES 值正确，36 个描边单元格与双层内边距匹配 |

独立检查器直接解析最终 PDF 的 CTM、描边、ActualText、图片 Do、链接及书签。声明的页数、源标记、表格坐标及字段值独立于 Java manifest；manifest 仅用于交叉核对源行、片段序号及 UTF-16。静态 SVG 预览另检查页数、逻辑文字顺序和图片，交互保留在 PDF。

跨渲染器使用 192 DPI、blur 2 px，沿用平均通道误差小于 4、变化比例小于 0.06 的阈值。本批最大值为 **2.104554／0.019903**。工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和本机 Poppler。未刷新任何视觉基线。

报告与全部 17 页联系表位于 `build/verification/semantic-table-flow/`。本次隔离目录由成功指针 `build/verification/consumer-build-directory.txt` 解析，为 `verification/consumer/build/validation-2f257e1010dd`；后续验证应继续读取指针，不硬编码历史输出。

## 命令与回归证据

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:semanticTableFlow :examples:semanticTables :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:mixedColumns :examples:multiFloats :examples:continuousColumns :examples:gridContinuation :examples:staticForms :examples:inlineFields :examples:inlineImages :examples:capabilities :examples:production :examples:adrForm :examples:authoring --no-daemon --console=plain
# FontScope 区域委托修正后的最终构建和受影响示例
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:semanticTableFlow :examples:semanticTables :examples:advancedPdf :examples:production :examples:mixedColumns :examples:multiFloats :examples:continuousColumns :examples:gridContinuation :examples:staticForms :examples:inlineFields :examples:inlineImages :examples:componentPdf :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe scripts/verify_semantic_table_flow.py
~~~

最终构建日志为 `build/verification/semantic-flow-build-final.log`，调用方为 `semantic-flow-consumer.log`，新输出为 `semantic-flow-output.log`。既有进阶、混合栏、合并格、静态表单、多浮动、连续栏、行内字段、图片、能力、ADR、编写 API、固定语义组合和生产共 **13 组独立脚本**全部通过，日志为 `build/verification/semantic-flow-verify_*.log`。进阶样例的两条缺字 WARNING 是预期调用方诊断；不宣称全部运行无警告。

本批 11 份文档的 252 个本地链接及标题层级通过检查，结果为 `build/verification/semantic-flow-docs.json`。

初次新增测试使用了不存在的 Builder 表头设置，已改用既有 Table 构造；来源预算测试也区分了原文预算和最终绘制文字预算。独立脚本最初只按 Y 坐标查找双栏引用，已增加同栏 X 范围，避免把同高度的两个正确链接误判为重复。以上修正后全部验证通过，不曾改宽阈值或自动刷新基线。

## 复评与下一入口

语义表格的 PDF 区域流、逐次字段及图文拆行子目标已验收。调用方可沿用同一 `.semanticTable(table)` 编写短表和长表，减少对固定／分页路径的选择。模型仍区分语义 Table 与合并 GridTable。

GAP-03 保持部分支持，因为 DOCX 嵌套尚未接入。长合并网格中的语义字段属于 GAP-06，仍未完成。单表先按默认内层限额准备，再验证调用方全局限额；测量前完整限额传播、流式数据源与整体驻留内存问题仍开放。下一项按[缺口表](../capability-gaps.md)推进 GAP-04 默认样式贯通，必须保留显式覆盖意图。

本地验证完成；没有 stage、commit、push、配置 remote 或正式发布。
