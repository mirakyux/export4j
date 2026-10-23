# 单向长表图片与链接

固定列 `StreamingTable` 表头和正文可使用 `TableCell.mixed` 编写文字、显式 PNG／JPEG 与被动文字／图片链接，横向合并保持。设计见 [ADR-0073](adr/0073-single-pass-images-and-links.md)。

```java
var format = CellFormat.builder().fontSize(10).lineHeight(18).build();
var picture = new InlineImage(imageResource, 16, 8, 0, "产品图",
        "https://example.com/product");
var cell = TableCell.mixed(List.of(
        new InlineText("查看产品 ", InlineStyle.of(10), "https://example.com/details"),
        picture), format);
var row = new TableRow(List.of(cell));
```

`imageResource` 为调用方显式加载的不可变 `ImageResource`。源通过既有 `TableRowSource` 提供，无须收集所有正文行。图片占原文一个 UTF-16 U+FFFC 位置，真实文字及缺字诊断的位置不改变；图片不能超过文字宽度和固定行框，超出时明确失败。

图片默认随整行移动，显式[拆行](streaming-row-split-api.md)时随其完整文字行移动一次；重复表头复用已测量的图片位置，逐次输出链接。文字链接按实际字形位置生成，图片链接使用最终图片矩形；不获取 URL 内容。协议继续限制为 HTTP／HTTPS／mailto，拒绝脚本、文件及带凭据的 HTTP 地址。

## 逐页消费与预算

`StreamingTableLayouter.PageConsumer.accept(page, links)` 和 `PageWriter.accept(page, links)` 同步接收当前页的外链。链接使用绝对物理页索引、左上角原点和 pt 坐标。消费结束后调用方可以释放页面及链接列表。旧实现遇到非空链接明确失败，不会生成缺少链接的成功结果。

`StreamingTableLimits.maxLinks` 默认 10,000，计实际链接矩形，包含重复表头；超限异常保留真实页、行、物理列和原文 UTF-16。相邻同目标且同高的连续文字矩形可合并。空运行仍计入运行额度，无文字范围时不生成注释。

PDF 后端分别累计 `PdfLimits.maxInteractions` 和 `maxInteractionCodePoints`，检查链接属于当前页且在页面内。图片仍执行显式资源字节／像素限制，以及每次实际绘制的 PDF 图片像素预算。PDFBox 的页树和交互对象会累积，不承诺恒定内存。

实际页码及准确声明总数见[字段 API](streaming-fields-api.md)；纵向合并、块格、自动宽度、未来引用及其他单向边界见[长表 API](streaming-table-api.md)。可运行示例为 `:examples:streamingMedia`，独立检查为 `scripts/verify_streaming_media.py`。
