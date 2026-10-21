# 绘制 API

当前接口用于精确定位的文字、路径、矩形和 RGB 图片。调用方可显式创建页面，或使用[文档布局 API](layout-api.md)完成段落自动分页。绘制层和后端不决定换行或分页。

## 最小示例

以下代码使用绘制核心与 PDF 后端模块：

~~~java
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;

public class HelloPdf {
    public static void main(String[] args) throws Exception {
        var page = new DisplayListBuilder(PageSize.A4)
                .drawText("Hello Export4j", new Point(36, 72),
                        new TextStyle(FontFamily.SANS_SERIF, 18, RgbColor.BLACK))
                .build();
        var document = new DisplayDocument(
                new DocumentMetadata("Hello", ""), List.of(page));
        try (OutputStream output = Files.newOutputStream(Path.of("hello.pdf"))) {
            new PdfBackend().write(document, output);
        }
    }
}
~~~

完整的两页示例在 `examples/src/main/java/com/mirakyux/export4j/examples/PdfExample.java`。仓库根目录执行 `.\gradlew.bat :examples:run` 即可运行。

## 坐标与绘制

- 单位为 point，`1 pt = 1/72 inch`。
- 原点位于页面左上角，X 向右、Y 向下；页外坐标有效，但输出受页面与当前裁剪区域限制。
- `drawText` 的位置是文字基线左端，不是字框左上角。
- `Rect` 的位置为左上角，宽高必须为有限正数。
- `PathBuilder` 支持移动、直线、三次贝塞尔与闭合；每个子路径以 `moveTo` 开始。
- 路径填充采用非零环绕规则；描边使用平直端点和斜接连接。
- `save` / `restore` 必须配对，作用于变换与裁剪；构建器和不可变页面都检查平衡。
- `transform(M)` 把当前矩阵 C 更新为 C*M。新局部点先经过 M，再经过原有 C。

## 图片与字体

`RgbImage` 接受从左上角逐行存储的 RGB 字节，每像素 3 个无符号通道。构造和读取均复制数组，图片对象可以安全共享。

图片绘制到给定矩形，宽高分别缩放，不自动保持比例。`RgbImage` 支持可选 straight alpha，原三参数构造器保持不透明。绘制核心的 `ImageDecoder.decode(byte[], maxBytes, maxPixels)` 有界解码 PNG／JPEG，后端以 soft mask 保存透明度；不读取 URL。

逻辑字体 `SANS_SERIF`、`SERIF`、`MONOSPACE` 分别映射到 Helvetica、Times Roman 和 Courier。可编码范围由所选标准字体决定，主要用于拉丁文字。

`drawText` 保留标准字体单行路径，不嵌入字体、不处理换行或回退。缺字返回 `UNSUPPORTED_TEXT`。

`drawGlyphRun` 接受已测量的 `GlyphRun`，位置同样表示基线左端。PDF 后端嵌入 run 的原始 TrueType 数据，并使用每个字形的显式 advance，不重新换行。段落调用方式见[字体和文本 API](text-api.md)。

`drawOutlinedText` 记录公共复杂文本的逻辑锚点，轮廓路径由布局结果给出。自定义绘制表面默认对此能力明确失败，支持时须覆盖该方法，不能丢失逻辑文字。PDF 用 ActualText 保留样本的逻辑文字，静态后端消费同一批既定路径，不重新塑形。

## 资源预算与诊断

`PdfLimits.DEFAULT` 的每文档预算如下：

| 项目 | 默认值 |
| --- | --- |
| 页面数 | 1,000 |
| 绘制命令数 | 1,000,000 |
| 图形状态栈深度 | 64 |
| 累计图像像素数 | 25,000,000 |
| 累计文本 Unicode 码点数 | 2,000,000 |
| 累计路径段数 | 1,000,000 |
| 不同字体的原始字节总数 | 64 MiB（67,108,864 字节） |
| 书签、链接、评论和字段总数 | 10,000 |
| 交互标题、作者、评论、字段名与初始值码点总数 | 1,000,000 |

每次图片绘制均计入像素预算，包括重复使用的资源。调用方可以构造 `PdfLimits` 并传给 `PdfBackend`，所有限制必须为正数。

字体按 SHA-256 去重计入预算，字形文字每次绘制都计入文本预算。原六参数及七参数 `PdfLimits` 构造器仍可调用，新增预算使用默认值。

PDF 页面宽高最多为 14,400 pt。无法表示为 PDF 后端浮点精度的数值返回 `INVALID_GEOMETRY`。

`PdfRenderException` 提供稳定错误代码、从 0 开始的页面和命令索引。索引 `-1` 表示该级位置不适用。错误代码包括 `BUDGET_EXCEEDED`、`INVALID_GEOMETRY`、`UNSUPPORTED_TEXT`、`INVALID_FONT` 和 `OUTPUT_FAILED`。字体加载或字形映射与测量结果不一致时返回 `INVALID_FONT`。

诊断默认不输出原文。I/O 失败保留原因；缺字错误不附带可能包含正文的底层错误消息。

## 所有权与并发

Display List 和图片为不可变快照，可跨线程共享。构建器不是线程安全对象，应限定在任务内部。

同一 `PdfBackend` 可用于并发输出，但每个调用必须使用独立输出流。后端释放内部 PDF 资源，始终保留调用方流的所有权。

输出失败后流可能包含部分字节，调用方应丢弃失败产物。结构预算不是墙钟超时、输出字节上限或不可信输入沙箱。

## 导航与交互

`DisplayDocument(metadata, pages, extras)` 接受后端中立的 `DocumentExtras`。原两参数构造器继续使用空交互。所有页索引从 0 开始，目标点和注释矩形使用页面左上角坐标，必须位于有效页面内。

支持有层级书签、内部页目标链接、被动 HTTP／HTTPS／mailto 外链、评论批注、文本字段、复选框、下拉框与单选组。空字体列表的文本字段使用 ASCII Helvetica；中文字段提供一个显式 TrueType `FontData`，完整嵌入以支持后续编辑，见[进阶 API](advanced-api.md#中文表单下拉框与单选组)。字段名全文档唯一；表单保持可编辑，保存当前值、默认值和外观流。交互数量与文字预算在输出前检查。JavaScript 与自动扁平化尚未提供。外部 CMS 签名、加密及归档入口见[能力指南](capabilities-api.md)。由正文 ID 自动解析目标的方式见[组件 API](components-api.md)。
