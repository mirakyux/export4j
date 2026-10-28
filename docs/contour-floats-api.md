# 任意轮廓与跨区域浮动

`ContourFloatText` 让一条显式 OpenType 文字流绕过非零绕组的任意闭合轮廓。轮廓由 `VectorPath` 的直线、三次贝塞尔曲线与闭合段表达；长度为 pt，坐标为组件左上角坐标。旧的 `MultiFloatText` 仍提供首片段矩形浮动。

```java
var contour = new VectorPath(List.of(
        new VectorPath.MoveTo(new Point(0, 0)),
        new VectorPath.LineTo(new Point(100, 0)),
        new VectorPath.LineTo(new Point(0, 160)),
        new VectorPath.Close()));
var floating = new ContourFloatText.Floating(
        picture, 0, 100, 160, contour, 4,
        new ContourFloatText.AfterText(0, 0));
var flow = new ContourFloatText(
        new OpenTypeText(body, ParagraphStyle.of(12), RgbColor.BLACK),
        List.of(floating));
```

`picture` 是调用方提供的组件，`body` 是原始正文；字体由 `DocumentEngine` 的显式字体组提供。可以直接放入 `Documents.component(...)` 或固定宽度的 `FlowColumn`。构造器进行结构快照，不接管组件、字体或图片资源。

## 锚点及续排

- `AtRegion(region, y)` 指定从当前区域流起点计算的零基区域及局部纵坐标。指定未来区域会明确保留中间的空区域。
- `AfterText(sourceOffset, gap)` 指定原始正文的 UTF-16 位置，浮动从包含该位置的文字行下方开始，额外计入轮廓净距与 `gap`。不切割或重编号正文；代理对中的低位位置拒绝。
- 多个文字锚点按原文顺序解析；已经定位的浮动只影响其后续文字。每次测量保留完整原文、双向文字及塑形上下文。
- 浮动可跨多个区域。图像及普通矢量组件按互不重叠的物理区域遮罩分片；包含文字或语义插槽的原子组件必须留在单一区域，避免重复其逻辑文字。可续排文字或语义表格应使用 `RegionFlow` 子组件。
- 可续排子组件按完整行或自身原子片段输出，声明的浮动总高度不足以放下全部来源时明确失败。轮廓是调用方显式指定的视觉遮罩；位于遮罩外的子组件绘制会被裁切，因此应按轮廓编写子内容。

## 避让与边界

每个完整文字行带与扩张了 `clearance` 的曲线区域相交，然后按所有相交范围排除水平区间。该行采用最宽的剩余连续区间，等宽时取左侧；不会把轮廓整体包围盒用于所有行。没有可用区间，或区间不足以放下下一个完整字形簇时，跳到下一行带。后端仅绘制已经定位的文字与遮罩，不再换行。

每条流最多 64 个浮动，每个路径最多 4,096 段，最多 100,000 个区域／文字行。锚点及过窄行带引起的完整源测量累计最多 4,000,000 UTF-16 单位；轮廓相交累计最多 20,000,000 个段工作单位。几何值、页区、源高度、取消和已有输出预算保持明确错误，不返回部分成功。

此入口的横向位置和内容宽度由调用方明确指定；它不从 HTML 浮动规则或 DOCX 锚点自动推断排版，也不承诺与其他排版软件相同的分页。示例由隔离调用方任务 `complexFlows` 生成。

PDF 的 ActualText 按原始正文顺序保存。部分阅读器会按不规则行首位置重新猜测多栏顺序；独立验收分别核对原始标记顺序、实际可提取内容唯一性与绘制几何，不把某个工具的几何排序当成所有阅读器的阅读顺序保证。
