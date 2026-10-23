# PDF 页面图像与嵌入图片提取

这两个入口处理已有 PDF，独立于新文档的 PNG 后端。源必须符合[被动 PDF 对象子集](pdf-object-editing-api.md)，读取要求提取权限，签名／脚本／未知活动结构保持拒绝。

```java
var rasterPolicy = ProcessingPolicy.builder()
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var pages = adapter.render(input.pages(2, 0))
        .dpi(144).background(0xFFFFFF).policy(rasterPolicy).prepare();
pages.images().getFirst().to(Path.of("first.png"));
pages.toZip(Path.of("pages.zip"));

var images = adapter.extractImages(input.allPages()).prepare();
images.toZip(Path.of("embedded-images.zip"));
```

## 页面渲染

采用 CropBox、查看旋转及 UserUnit；dpi 为物理像素／英寸，范围 `(0, 1200]`，默认 144。按缩放后尺寸向下取整且每边至少 1 像素，旋转后交换宽高。渲染采用 VIEW 可见性，不擅自绘制隐藏或 NoView 批注。源页索引从零开始，`Image.sourcePages()` 保留对应关系；输出按选择顺序命名 `page-0001.png` 等，允许重复选择。

默认 PNG／白色背景，`.transparent(true)` 保留 alpha；JPEG 不接受透明模式。`.format(PdfRasterRequest.Format.JPEG).jpegQuality(.9).background(0xEEF0F5)` 明确质量和背景。结果为 8 位 RGB／RGBA 预览，不承诺保留印刷色、专色或源色彩空间。PNG pHYs 按整数像素／米登记密度，JPEG JFIF 按整数 DPI 登记；实际尺寸按调用方的原始 DPI 计算。

渲染要求有效、嵌入的静态 TrueType，包含 Type0／CIDFontType2 和普通 TrueType；非嵌入字体、CFF 等没有隐式系统回退。字体资源、当前外观、ExtGState 字体和软蒙版组预先检查。NeedAppearances、缺少应有外观及当前不支持的图案通过独立功能键处理。文字缺少 Tf、畸形字体及无效资源不生成默认字体结果。

| 功能键 | 真实处理及必需 treatment |
| --- | --- |
| FORMS | 字段编辑能力和值成为像素，DEGRADE |
| LINKS／BOOKMARKS／ATTACHMENTS | 对应导航／对象不进入图像，DISCARD |
| COMMENTS | 仅可见外观成为像素，评论内容和编辑能力不保留，DEGRADE |
| DOCUMENT_METADATA | 不复制源 Info／XMP，DISCARD |
| JPEG_ENCODING | 有损像素编码，DEGRADE |
| RENDER_FONT／FORM_APPEARANCE／RENDER_PATTERN | 无受支持渲染路径，只能 SKIP_BLOCK 跳过整页，报告 PAGE_NOT_RENDERED |

默认 ERROR；WARN／IGNORE 的真实处理相同，IGNORE 仍保留 issues／losses。操作键 RASTERIZE，目标格式 PNG／JPEG，遵循共享覆盖顺序。用户明确选择查看图像不等于继续保留 PDF 交互或可搜索文字。

## 图片资源

`extractImages` 遍历所选页的图像资源，包括 Form、图案、软蒙版组及当前批注外观中的资源；也会提取未执行 Do 的已登记资源。相同 COS 图像对象只输出一次，保留访问顺序中的不同 sourcePages。它不定位页面上的图片矩形，不执行 OCR，也不把页面渲染当作资源提取。

输出为保留解码像素和软蒙版 alpha 的 PNG，不保留原 JPEG／Flate 文件字节。IMAGE_STENCIL 只支持 SKIP_MATCH，因 stencil 的颜色依赖调用上下文；IMAGE_PRECISION／DEGRADE 将超过 8 位的通道转换到 8 位；IMAGE_COLOR_SPACE／DEGRADE 将 CMYK 或 ICC 资源转换为 RGB。操作键 EXTRACT_IMAGES，目标 PNG。未知滤镜继续在输入预检中失败，不能通过忽略策略进入未实现的解码路径。

## 预算与输出

完整输入先验证 PdfLimits，跨适配器传入的快照也受当前额度控制。maxImagePixels 分别限制不同源图像声明和本次页面输出像素累计；maxContentOperators 限制预检及实际重复绘制，maxTextCharacters 限制实际绘制字形。页面缓冲、字体和解码库不具备硬 RSS 沙箱保证。

prepare 完成图像及 ZIP 编码，单独图像累计和 ZIP 各受 maxOutputBytes 限制。PreparedPdfImages 提供不可变列表、来源页、尺寸、媒体类型、防御性 bytes 和确切 preview。toZip 返回 ACTUAL 报告，只有实际输出成功才返回；逐图写出不关闭／刷新借用流，路径为同目录原子替换。无隐式临时磁盘缓存、后台任务或源文件名输出。

运行 `:examples:pdfRaster` 和 `scripts/verify_pdf_raster.py` 可检查示例页面、JPEG、表单外观、图片字节和独立调用方。
