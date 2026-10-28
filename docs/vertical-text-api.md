# 竖排文字 API

`VerticalText` 是显式的竖排组件：每列从上向下排列，列顺序默认从右向左，也可选择从左向右。CJK 字符保持直立，ASCII 字母和数字逐字顺时针侧置；这不是把整段横排结果旋转。长度单位为 pt。

```java
var content = VerticalText.builder("天地玄黃宇宙洪荒")
        .fontSize(18)
        .cellAdvance(32)
        .columnAdvance(36)
        .height(128)
        .columnOrder(VerticalText.ColumnOrder.RIGHT_TO_LEFT)
        .build();
var document = Documents.builder().component("vertical-body", content).build();
engine.export(document).backend(new PdfBackend()).to(Path.of("vertical.pdf"));
```

引擎仍要求显式 `FontGroup`。字体、字号与预先生成的轮廓同时用于测量和绘制；PDF、SVG 和 PNG 后端消费相同最终几何，不重新塑形或决定换列。省略字号、字格间距和颜色时，采用组件上下文的默认字号、行高和颜色；省略列间距时采用字格间距。`height` 是固定列高，省略时采用新页可用高度。

## 明确支持范围

- CJK 汉字、预组合假名／谚文、注音及全角字符按独立 Unicode 标量直立排列。ASCII 字符逐字侧置，不自动把一串数字合成为纵中横。
- 选定逗号、顿号、句号、冒号、分号、问号、叹号、省略号、破折号及括号使用 Unicode Vertical Forms 中的明确对应字形，例如 `「` 使用 `U+FE41`、`。` 使用 `U+FE12`。字体必须实际提供对应形式；不会悄悄退回横排标点。
- 自然换列以完整字格为单位；CR、LF 和 CRLF 显式换列，CRLF 只换一次。每个字符保留原文 UTF-16 范围，绘制使用的竖排形式不改变逻辑文本。
- `layoutDetails(context)` 返回不可变 `Result`，同时包含组件布局及 `Placement` 来源映射：原文范围、列序号、最终字格、实际码点、侧置状态及字体摘要。换列标记的实际码点为 `-1`。
- 缺字默认失败；显式占位策略返回原始 UTF-16／码点与替代字体诊断。字体回退只在显式字体组内发生。非法 Unicode、组合／连接脚本等不支持的塑形不能通过占位策略绕过。

## 容量与边界

组件当前整块放置，不跨页拆分。高度必须容纳完整字格，所有列必须放入可用宽度；字形轮廓超出预留字格时明确失败，不裁切、不缩字。显式 `maxCodePoints`／`maxColumns` 只允许收紧默认上限，分别最多 100,000 个码点和 10,000 列；轮廓工作总量最多 2,000,000 个路径段。布局检查现有同步取消范围，不执行 I/O，不保留全局字体缓存。

这是一套有明确字格和方向的排版子集。当前不提供通用 OpenType `vert`／`vrt2`／`vkrn` 特性、原生 `vhea`／`vmtx` 排版、组合字符、连接脚本、纵中横、注音旁注、竖排禁则或任意竖排文档的语义导入导出。模型中通过组件接入；DOCX／HTML／Markdown 没有该组件的原生语义映射，沿用既有组件拒绝或显式损失规则。PDF 逻辑文本和 SVG 标记保留原文，但不据此承诺所有阅读器的竖排搜索／选区或 PDF/UA 合规。

独立调用方任务为 `verticalText`；输出生成后运行 `python scripts/verify_gap08_vertical_text.py --consumer-build-directory <成功调用方目录>`，核对原文、字格、列顺序、标点形式及 PDF／SVG／PNG 和双引擎渲染。PDFium 的提取、跨字格查询及选中文字单独核对；工具可能在每个字格间插入换行，查询核对原文的非空白序列。选区矩形对应逻辑锚点，不能当作完整字形轮廓覆盖或精确脱敏框。

本单元的构建、独立输出、调用方及容量覆盖见[阶段九十八](verification/phase-98-layout-text-streaming-and-graphics.md)。
