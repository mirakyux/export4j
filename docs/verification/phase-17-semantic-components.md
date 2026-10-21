# 阶段十七验收：语义组件组合

日期：2026-10-15。本批推进 GAP-03 的语义 IR 前置条件与固定视口组合子集，不关闭长语义表格区域流。自主实现语义插槽及组合，没有新增第三方依赖、字体或外部素材。

## 行为和兼容

- ComponentLayout 新增有序 SemanticPlacement，保留源 Table、TableFragment、实际字体组和字体角色；旧五参数构造器兼容纯静态布局。
- FlowColumn、RowComponent、BoxComponent、GridTable 两条绘制路径、PreparedCellFlow、MultiColumn、ContinuousColumns 和 MultiFloatText 保留插槽顺序及局部变换。
- SemanticTable／`.semanticTable(Table)` 支持固定视口内的富文本、图片、最终 PAGE／PAGES 和引用字段；嵌套表格可作为书签、目录或引用目标。所有字段在目录偏移和分节编号确定后解析，不重新换行或分页。
- 普通单元格警告保留嵌套表格 ID、行列和原文 UTF-16；生成值使用局部 UTF-16 及 INLINE_FIELD 来源。测量异常保留语义源、行列、错误代码及 cause。
- 字段、图文外链和锚点共享命令栈几何，避免只平移绘制而遗漏交互矩形。FontScope 和字体角色快照用于最终字段，禁止隐式系统字体回退。
- `commands()` 遇语义插槽明确失败；自定义组合器使用 ComponentSemantics.append。纯静态组件保持旧绘制命令和仿射变换能力。

契约见[语义组合 API](../semantic-components-api.md)及 [ADR-0021](../adr/0021-component-semantic-insertions.md)。项目尚未发布；record 新分量保留旧构造调用的源码兼容，不等于承诺已发布制品的二进制兼容。

## 构建与行为测试

Gradle XML 统计为 **48 个测试类、293 项 JUnit**，0 失败、0 错误、0 跳过。新增 16 项核心／组件综合回归，覆盖：

- 插槽首尾及同位置绘制顺序，嵌套 append 偏移和 Restore 状态隔离。
- 完整矩形裁剪可用，旋转／缩放／部分或空裁剪明确拒绝；未解析字段不能绘制。
- 目录偏移、分节显示页码、嵌套目标及双向引用，最终预留宽度与几何保持。
- 两层列、行、组件网格及连续栏中的原图、文字外链和图片外链坐标。
- FontScope、真实字体角色、缺字策略、代理对 UTF-16 及窄字段错误来源。
- 全文档 ID、源文字、单元格、语义源和 placement 预算；超高表格失败，短表整块移页。
- 多页 GridTable 表头重复语义源明确拒绝，包括普通与拆行路径；原始 commands 和页区装饰不丢弃语义内容。

全仓 build、Java `-Xlint:all -Werror`、Javadoc、源码包、SBOM、本地 Maven 制品成功。隔离调用方从坐标解析、编译和运行成功，目录读取成功指针，未检查被占用的历史目录。隔离调用方无 JUnit 源码，其运行断言不计入上述 293 项。

## 独立输出验收

新示例 3 页，隔离调用方 2 页，共 **5 页**均经 pypdf／PDFium 几何检查及 Poppler／PDFium 192 DPI 渲染，全部页面人工复查通过。

| 输出 | 独立结果 |
| --- | --- |
| 示例目录和两分节 | 目录偏移 1；显示页码 2／7、总页数 3；8 条最终字段逻辑行完整 |
| 嵌套语义来源 | 3 张表格、5 个源单元格及 5 个最终 PDF 描边矩形；原 UTF-16 字段／图片索引保持 |
| 示例交互 | 2 个目录链接、3 个引用链接、4 个图文外链、2 张图片和 2 个书签；目标物理页与坐标匹配 |
| 显式静态 SVG 预览 | 全部 20 条逻辑文字顺序精确匹配；交互保留在 PDF 中，未声称 SVG 含交互 |
| 隔离调用方 | 2 页、4 条最终字段行、双向引用 2 个、外链 2 个及图片 1 张；读取成功构建目录 |

检查器独立解析最终 PDF 命令栈、闭合描边、ActualText 范围内字形轮廓及 Image Do 矩形；图片外链精确匹配实际图像，文字外链覆盖实际轮廓或下划线范围。内部 manifest 仅作交叉核对，固定值和几何另由脚本声明。

跨渲染器沿用 blur 2 px、平均通道误差小于 4、变化比例小于 0.06 的阈值；本批最大值为 **0.610110／0.007413**，未更新基线。工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 与本机 Poppler。报告和全部 5 页联系表位于 `build/verification/semantic-tables/`。

## 命令与证据

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:semanticTables :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:mixedColumns :examples:multiFloats :examples:continuousColumns :examples:gridContinuation :examples:staticForms :examples:inlineFields :examples:inlineImages :examples:capabilities :examples:production --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation :examples:adrForm :examples:authoring --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe scripts/verify_semantic_tables.py
~~~

主构建日志为 `build/verification/semantic-build.log`，调用方日志为 `semantic-consumer.log`。既有进阶、混合栏、合并格、静态表单、多浮动、连续栏、行内字段、图像、生产、能力、ADR 和编写 API 的 12 组独立脚本通过，日志为 `build/verification/semantic-verify_*.log` 及 `semantic-advanced.log`。未自动更新任何基线。

## 复评与下一入口

固定视口组合子集已经通过行为、独立输出与隔离调用方验证。剩余前置条件是语义表格区域分配和重复呈现身份；不能把一次源 ID 复用当作新文档节点，也不能提前绘制字段绕过解析。

每张嵌套表格仍必须放得进一个完整视口，每个语义源首批只呈现一次。多页重复 GridTable 表头、语义装饰、旋转／缩放和部分裁切明确拒绝。DOCX 嵌套表格不在本批范围。组件先在默认内层限额内准备，再检查更小的调用方全局限额；不是端到端流式或完整测量前限额传播。

下一批按[缺口表](../capability-gaps.md)推进完整行表格的 RegionFlow、重复表头与逐次字段，再验证自动列宽、图片、拆行及全局预算。GAP-04 默认样式贯通继续开放，不从本批组合能力推断已解决。
