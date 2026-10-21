# 当前页连续分栏

`ContinuousColumns` 从当前页的剩余正文区域开始分栏，续页使用完整正文高度。栏宽保持一致，先从上到下、再从左到右填充；文字只塑形／换行一次，再把完整行分配到各栏。后续扩展已支持 `FlowColumn` 混合流与 `GridTable` 表格，见[混合分栏 API](mixed-columns-api.md)。PDF、SVG 和 PNG 消费同一份绘制结果。

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

`ContinuousColumns` 一次调用子组件，把逻辑区域按栏数组合为物理片段，验证顺序、数量、尺寸和续区推进。如果跳过整组首栏，首个实际片段必须高于原剩余高度，使现有正文分页器明确移页；不能通过一个仍能放入剩余区域的小片段隐式请求换页。

`FlowText` 和 `OpenTypeText` 的原有 `layoutParts` 调用继续可用，内部委托同一个完整行分配路径。区域、验证和绘制合成在同步边界检查取消／超时，不创建后台线程。原文字、轮廓、页面和文档命令预算继续有效。

## 字体、来源和边界

显式字体组的严格报错／占位警告策略保留。诊断仍使用原文 UTF-16 范围，合并到对应物理片段，再由文档布局器附加块 ID 与最终页索引。首行缩进只作用于整个原文第一行，不在每栏或续页重新应用。

连续分栏保留各文字实现已有的换行规则。普通段落路径在换行处消耗的 ASCII 空格不属于绘制文字；显式 `OpenTypeText` 保留逻辑行内容。新分栏不会重新塑形或改变这两种规则。验证同时比较相同栏宽下的未分页绘制结果、非空格字符顺序，以及显式轮廓原文。

文字首版证据见[验收记录](verification/phase-8-continuous-columns.md)；后续混合组件、嵌套流与表格已接入区域 SPI，见[混合分栏验收](verification/phase-9-mixed-columns.md)。任意普通组件通过 `FlowColumn` 按整块参与，旧 `PageableComponent` 保留其已测量片段为原子单位。没有连续栏内自动平衡、变栏宽、跨页浮动锚点、语义节栏或 DOCX 多栏映射；不代表 PDF/UA 结构化阅读顺序。
