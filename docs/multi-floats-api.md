# 多浮动组件绕排

`MultiFloatText` 在一个正文组件的首片段内放置多个矩形浮动组件，文字逐行同时避让左右区域，续页恢复全宽。它接受现有 `OpenTypeText` 和核心 `Component`；图片、文本框、形状及其他能完整放入指定区域的组件可以组合使用。PDF、SVG 和 PNG 消费同一份预计算绘制指令。

## 调用方式

以下 `image`、`card` 为调用方已准备的组件；字体仍由 `DocumentEngine` 显式传入。

~~~java
var floating = new MultiFloatText(
        new OpenTypeText(body, new ParagraphStyle(12, 26, 0, 0, TextAlignment.LEFT), RgbColor.BLACK),
        List.of(
                new MultiFloatText.FloatBox(image, 96, 68, 0, 10, MultiFloatText.Side.LEFT),
                new MultiFloatText.FloatBox(card, 90, 88, 24, 10, MultiFloatText.Side.RIGHT)));
var block = new ComponentBlock("article", floating, 0, 8);
~~~

完整可编译示例见 [MultiFloatExample](../examples/src/main/java/com/mirakyux/export4j/examples/MultiFloatExample.java)：

~~~powershell
.\gradlew.bat build :examples:multiFloats
python scripts/verify_multi_floats.py
~~~

输出至 `examples/build/multi-floats/`，包含左右错位绕排、封闭行带、整组移页、浮动独占首片段四组 PDF／SVG／PNG ZIP；原文与输入几何单独保存，用于独立检查。

## 几何与分页契约

`FloatBox(content, width, height, top, gap, side)` 的长度单位均为 point，坐标遵循左上原点。

| 参数 | 含义与校验 |
| --- | --- |
| `content` | 在固定视口内完整布局一次，返回尺寸必须放得入声明区域；绘制裁剪至声明矩形 |
| `width`／`height` | 正有限尺寸；整个矩形必须放入新页正文视口 |
| `top` | 相对本组件首片段顶部的非负偏移，随首片段一起移页 |
| `gap` | 朝正文一侧的非负水平留白，不增加上下边距 |
| `side` | `LEFT` 靠本组件左边，`RIGHT` 靠右边；较小的子组件在其声明矩形内左上对齐 |

浮动列表防御性复制，最多 64 个，允许空列表。实际矩形可以相接，不能相交；同侧的纵向重叠及两侧的实际碰撞均明确失败。留白区域可以相交。

每行按固定 `lineHeight` 形成完整行带；只要行带与某矩形的垂直区间相交，就在该行扣除矩形宽度和水平留白。左右区域同时存在时，文字使用中间的单个连续区间，缩进和对齐以这个区间为基准。没有足够空间放入一个字形簇的正宽区间仍明确失败。

当左右留白封闭整行宽度时，算法跳到最近浮动区底部之后的行网格位置，不消耗原文，也不逐个扫描空白行。若首片段全部行带被封闭，先输出一次浮动组件，再在下一片段继续正文；不会无限生成空白页。

有浮动组件且正文为空时，只输出浮动内容并保留其最深边界，不额外预留文字行或生成空白续页。

所有浮动矩形必须同时放入首片段。如果当前页剩余高度不足以容纳它们，或有正文而不足一个完整文字行，整组从新页开始。浮动组件只输出一次；正文只塑形／换行一次，再按完整行分页。即使正文提前结束，首片段仍保留全部浮动组件的最深边界，后续正文从该边界之后继续。

## 字体、诊断与兼容

文字沿用 `OpenTypeText` 的显式字体、双向文字、字形簇、固定行距和缺字策略。正文的诊断保留原文 UTF-16 范围并分配到对应片段；子组件诊断在首片段合并，索引仍属于子组件自己的文字。最终上下文提供所属组件块和页面，当前没有为每个浮动框增加独立语义节点 ID。

每次布局都使用局部状态；列表和结果不可变，自定义子组件须遵守现有线程安全与确定性契约。布局在行带分配、子组件调用边界和绘制合成时检查同步取消／超时；不会创建后台线程。仍适用 `OpenTypeText` 的 200,000 原文码点、100,000 输出行及轮廓规模限制，以及文档级页数、文字、行和命令预算。

旧 `FloatText` 公共构造器和 `Side` 保留，内部委托同一个避让算法；其原有 `floatWidth + gap` 视口检查继续保留。正常单浮动几何与分页保持一致，子组件现在明确裁剪至声明区域。

本能力是块内首片段的矩形边缘绕排。浮动框不会自动寻找任意页面／段落锚点，不提供多边形轮廓绕排、跨页拆分浮动内容或 DOCX 浮动 DrawingML 映射。正文保持逻辑原文顺序，浮动内容按列表顺序先绘制；该顺序不等于 PDF/UA 结构化阅读顺序。验收证据与剩余范围见[本批记录](verification/phase-7-multi-floats.md)。
