# 当前页连续分栏

`ContinuousColumns` 从当前页的剩余正文区域开始分栏，续页使用完整正文高度，先从上到下、再从左到右填充。默认等宽栏每次试排只塑形／换行一次；显式权重提供变宽文字栏，最低多行策略可触发有界重测。`FlowColumn` 混合流与等宽 `GridTable` 表格见[混合分栏 API](mixed-columns-api.md)。PDF、SVG 和 PNG 消费同一份绘制结果。

## 调用方式

字体由 `DocumentEngine` 显式提供；`body` 是调用方的原文。

~~~java
var style = new ParagraphStyle(12, 22, 0, 0, TextAlignment.LEFT);
var columns = new ContinuousColumns(
        new FlowText(body, style, RgbColor.BLACK, PaginationPolicy.BALANCED), 2, 16);
var block = new ComponentBlock("article", columns, 0, 8);
~~~

也可以传入 `OpenTypeText`，保留其方向、字距、连字和中文标点配置。完整可编译代码见 [ContinuousColumnsExample](../examples/src/main/java/com/mirakyux/export4j/examples/ContinuousColumnsExample.java)。

~~~powershell
.\gradlew.bat build :examples:continuousColumns
python scripts/verify_continuous_columns.py
~~~

输出位于 `examples/build/continuous-columns/`：五组共 9 页 PDF、SVG、144 DPI PNG ZIP，以及原文和输入配置。包含首组短栏、完整续栏、三栏、最低行数移页、中阿混排，以及分栏后的普通正文。

## 几何和分页

| 参数／行为 | 契约 |
| --- | --- |
| `content` | 实现核心 `RegionFlow` 的组件；标准实现为 `FlowText`、`OpenTypeText`、`FlowColumn` 和 `GridTable` |
| `columns` | 2～16 栏 |
| `gap` | 栏间距，单位 point，有限且非负；扣除全部间距后必须有正栏宽 |
| `columnWeights` | 可选 2～16 个有限正权重，按扣除间距后的正文宽度分配；非空时设置栏数，逐页重复 |
| 第一组栏 | 使用当前页剩余高度；每栏高度相同，栏宽与续页相同 |
| 续页 | 使用新页正文高度，重新从左上开始 |
| 最后片段 | 只保留最高已占用栏的实际高度，后续正文从该边界继续 |
| 绘制 | 每个占用栏裁剪到自己的视口，后端不重新换行或分栏 |

`FlowText` 保留 `PaginationPolicy` 的断点前／后最低行数；不能满足最低行数的首组短栏可以跳过，正文整体从下一页开始。新页也无法满足约束时明确失败，不输出空白片段重试。`OpenTypeText` 使用默认最低一行策略；通过 `FlowText` 的公共自适应塑形路径可以同时使用复杂文字和自定义最低行数。

末片段保留实际高度不等于末页平衡。例如最后仅有 6 行且首栏足够时，6 行都留在首栏，后续正文在这 6 行之后开始。旧 `MultiColumn` 的整页／有界平衡行为和构造器保持不变。

固定区域调用 `layout(context)` 仍要求只返回一个物理片段；需要跨页时放入语义 `ComponentBlock`，由文档布局器消费片段。

## 核心扩展契约

`RegionFlow.layoutRegions(context, regions)` 接收相同栏宽和 `FlowRegions(initialHeights, continuationHeight)`。高度单位均为 point；有限前缀最多 16 个，可包含零高度，不能超过正有限的续区高度。区域索引与物理页面／栏编号无关。

返回 `RegionFlow.Fragment(regionIndex, layout)`，使用区域内局部坐标。返回值不可变、非空、最多 100,000 个正高度片段；索引严格递增，每区至多一个片段。首组区域仅在下一原子片段放不下时可以跳过，续区不得跳过。结果必须适应指定宽高，无法推进时失败。自定义实现是可信、协作式代码，负责确定性与线程安全。

默认 `ContinuousColumns` 一次调用子组件，把逻辑区域按栏数组合为物理片段，验证顺序、数量、尺寸和续区推进。如果跳过整组首栏，首个实际片段必须高于原剩余高度，使现有正文分页器明确移页；不能通过一个仍能放入剩余区域的小片段隐式请求换页。

`FlowText` 和 `OpenTypeText` 的原有 `layoutParts` 调用继续可用，内部委托同一个完整行分配路径。区域、验证和绘制合成在同步边界检查取消／超时，不创建后台线程。原文字、轮廓、页面和文档命令预算继续有效。

## 字体、来源和边界

显式字体组的严格报错／占位警告策略保留。诊断仍使用原文 UTF-16 范围，合并到对应物理片段，再由文档布局器附加块 ID 与最终页索引。首行缩进只作用于整个原文第一行，不在每栏或续页重新应用。

连续分栏保留各文字实现已有的换行规则。普通段落路径在换行处消耗的 ASCII 空格不属于绘制文字；显式 `OpenTypeText` 保留逻辑行内容。新分栏不会重新塑形或改变这两种规则。验证同时比较相同栏宽下的未分页绘制结果、非空格字符顺序，以及显式轮廓原文。

文字首版证据见[验收记录](verification/phase-8-continuous-columns.md)；后续混合组件、嵌套流与表格已接入等宽区域 SPI，见[混合分栏验收](verification/phase-9-mixed-columns.md)。任意普通组件通过 `FlowColumn` 按整块参与，旧 `PageableComponent` 保留其已测量片段为原子单位。`.balanced(true)` 以最多 24 次确定性试排缩短各页公共栏高，不增加页数，不移入原来跳过的首页。自定义组件须可重复测量；试排返回的非法片段仍直接失败。默认构造保持不平衡；跨页浮动锚点、语义节栏或 DOCX 多栏映射仍未实现，不代表 PDF/UA 结构化阅读顺序。

## 变栏宽

```java
var columns = ContinuousColumns.builder(FlowText.builder(text).lineHeight(20).build())
        .columnWeights(List.of(1.0, 3.0)).gap(20).balanced(true).build();
```

扣除间距后第一栏占 1/4、第二栏占 3/4。每一行按自己所属栏的真实宽度换行，不缩放已测字形、不裁掉宽栏内容来适应窄栏。首行缩进仍仅用于整个原文第一行；短区和零高首区不消耗来源。权重在 setter 与构建边界快照，`columns(n)` 恢复等宽，空权重列表也恢复等宽。保留旧三／四参数构造；record 新分量不承诺二进制兼容。

`WidthAwareRegionFlow.layoutVariableRegions(context, regions, widths)` 是可选扩展，正有限宽度序列按逻辑区域索引循环，包含跳过的首区；每项不大于上下文宽度。`FlowText`、`OpenTypeText`、`RichTextComponent.flow()`、`FlowColumn`、`FontScope`、`FontRoleScope` 与 `StyleScope` 已接入。混合流逐区扣内边距、旋转子宽度序列，整块图片等普通组件按当前栏宽测量；各区边界重置间距。全局数量、来源、诊断及取消规则保持。

`FlowText` 与 `RichTextComponent.flow(policy)` 也支持最低多行策略：短首区可跳过，栏尾不足时按候选分配重新测量完整原文，再验证实际栏宽和行数。每次区域调用最多 32 次测量、4,000,000 个源 UTF-16 单位的重复工作；预算内没有已验证方案时明确失败。首行缩进和缺字诊断仍使用完整原文，不合并试排诊断。默认最低一行策略及等宽路径保持一次换行。见[最低行数设计](adr/0071-minimum-lines-in-variable-columns.md)及[阶段六十三](verification/phase-63-minimum-lines.md)。

~~~java
var columns = ContinuousColumns.builder(FlowText.builder(text)
        .lineHeight(20).pagination(PaginationPolicy.BALANCED).build())
        .columnWeights(List.of(1.0, 3.0)).gap(20).build();
~~~

`SemanticTable` 已接入变宽区域：完整行／合并行组按实际栏宽测量，支持原子嵌套格、最终字段和图文外链，见[语义表格变宽 API](variable-tables-api.md)。不同宽度的行内格完整行续排见[拆行指南](variable-table-continuation-api.md)；纵向合并拆分／递归子树续排、组件网格和其他旧分页组件仍明确拒绝；等宽路径保持。自定义宽度实现须保持每个片段适应自己的栏宽，非法结果不能通过平衡试排隐藏。文字基础实现与输出证据见[ADR-0067](adr/0067-width-aware-continuous-columns.md)及[阶段五十九](verification/phase-59-variable-columns.md)。
