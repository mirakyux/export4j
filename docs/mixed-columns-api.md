# 混合组件与表格连续分栏

`FlowColumn` 和 `GridTable` 已实现核心 `RegionFlow`，可以直接传给 `ContinuousColumns`。文字、固定尺寸图片／形状、嵌套流和表格从当前页剩余区域开始，续页使用完整栏高；最后物理片段按实际最高栏接续普通正文。基础几何见[连续分栏 API](continuous-columns-api.md)。

## 混合流

~~~java
var mixed = new FlowColumn(
        List.of(title,
                new BoxComponent(176, 66, image),
                new FlowText(body, style, RgbColor.BLACK, PaginationPolicy.DEFAULT),
                table),
        PageMargins.uniform(4), 8, FlowColumn.Alignment.START);
var columns = new ContinuousColumns(mixed, 2, 16);
var block = new ComponentBlock("article", columns, 0, 8);
~~~

`title`、`image`、`style` 和 `table` 是调用方已准备的组件／样式，字体仍显式传给 `DocumentEngine`。图片／形状通常通过 `BoxComponent` 指定尺寸；直接填满视口的组件会占用一个完整栏。完整可编译示例见 [MixedColumnsExample](../examples/src/main/java/com/mirakyux/export4j/examples/MixedColumnsExample.java)。

| 子组件 | 区域内行为 |
| --- | --- |
| `FlowText`／`OpenTypeText` | 一次换行后按完整行占用剩余区域和后续区域，保留首行缩进及原文顺序 |
| `FlowColumn` | 可嵌套；每个占用区域重复自身内边距，并传递子组件的实际剩余高度 |
| `GridTable` | 一次测量单元格，默认按完整合并行组分配；显式拆分模式支持完整行格内续排，每个占用区域重复表头 |
| 普通 `Component` | 按新栏可用宽高测量一次，整块移动，不能裁切／拆开以适应短栏 |
| 其他 `PageableComponent` | 一次调用原有 `layoutParts`；每个测量片段保持原子性，后续片段从新区域开始，不能重新适应多个短栏 |

内边距在每个占用栏重复；间距只出现在同一区域内相邻子内容之间，移栏后不保留额外前导间距。`START`／`CENTER`／`END` 控制栏内子组件的水平对齐。不会输出仅有内边距的空片段。

当前区域放不下下一个原子内容时，先结束已有内容。后续短区仍放不下时继续跳过；完整续区也放不下则明确失败。嵌套流为子组件构造等宽的剩余区域前缀，保留其区域归属，不重新测量已消费的文字或图片。每次混合布局最多放置 100,000 个子片段。

## 表格

`GridTable` 的行／列合并、单元格内边距、背景、边框和垂直对齐沿用现有规则。列宽按当前栏宽和显式权重分配；栏数增加可能使文字换成更多行。默认要求单元格内容完整放入新栏的测量视口。

默认跨行合并把相连行组成一个不可分割组。每个占用栏先输出完整表头，再输出至少一个正文行组；表头与首个正文组放不入短栏时整组移到后续区域，不输出只有表头的栏。若二者合计超过完整新栏，立即失败。

显式 `allowRowSplit(true)` 支持完整行文字跨栏，图片与固定组件整块移动，逐栏重复表头及内边距，详见[续页指南](grid-continuation-api.md)。`FlowColumn.Builder.text()` 默认使用可分页文字，固定高度文字使用 `fixedText()`。

重复表头复用同一次测量的绘制和诊断；正文行组只输出一次。表格最多返回 100,000 个区域片段，网格原有 1,000,000 槽／单元格预算继续适用。表格的组件来源仍是块级来源，不等于语义 `Table` 的逐单元格来源；`TableFormat.allowRowSplit` 的语义文本行拆页规则不改变。

## 兼容、诊断与验证

`FlowColumn`／`GridTable` 原有构造器、`layoutParts` 和固定区域 `layout` 调用保留，内部使用同一个区域分配路径；固定区域仍要求单片段。旧 `MultiColumn` 整页／平衡模式继续可用。

字体组、原文 UTF-16 警告范围和子组件绘制顺序保留。重复表头诊断随每个实际输出的表头重复；最终文档附加块 ID 与物理页索引，索引仍属于对应子组件原文，不合成为跨子组件字符偏移。自定义子组件仍是可信代码，必须确定、协作取消并自行声明线程安全。

测量、子组件边界、区域推进和绘制合成检查同步取消／超时，不创建后台线程，也不访问系统字体或远程资源。PDF、SVG、PNG 共享预计算指令，后端不再次决定换行／分栏。

~~~powershell
.\gradlew.bat build :examples:mixedColumns :examples:continuousColumns
python scripts/verify_mixed_columns.py
python scripts/verify_continuous_columns.py
~~~

样例输出至 `examples/build/mixed-columns/`：图文表格混合、表头重复、合并图像行组整体移页、嵌套中阿混排四组共 8 页 PDF／SVG／PNG ZIP，附原文和输入配置。独立几何、全部渲染页、调用方与回归证据见[验收记录](verification/phase-9-mixed-columns.md)。

本能力没有连续栏自动平衡、变栏宽、跨页浮动锚点、语义分节栏或 DOCX 多栏映射。合并行组与旧分页组件片段保持完整；PDF/UA 标签阅读顺序和任意组件的内部拆分仍需单独实现。
