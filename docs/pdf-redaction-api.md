# PDF 区域安全脱敏

`PdfAdapter.redact(input)` 把受支持 PDF 全部页面转换为全新的图片 PDF，在编码前清除指定区域像素。输出不复制原文字、字体、附件、元数据、批注对象、隐藏资源或增量历史。指定区域内的图像内容也会真正清除。

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("RASTERIZATION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PAGE_GEOMETRY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("FORMS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("COMMENTS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
adapter.redact(input).area(0, 18, 25, 68, 22).dpi(144).fill(0)
        .policy(policy).prepare().to(target);
```

`area(page,x,y,width,height)` 的 page 从零开始；坐标是**查看旋转后的 CropBox 左上角物理 pt**，不同于页级编辑的未旋转 MediaBox 坐标和对象编辑的原生左下角坐标。区域须完整位于可见页面。按实际像素比例取整，并外扩一个像素；默认黑色，`fill` 为 24 位 RGB。DPI 默认 144，允许 `(0,1200]`，分配前检查累计像素。

全文变为 RGB 图像，包括选区外的文字和矢量；搜索、复制和语义编辑能力会丢失。RASTERIZATION／DEGRADE 必须明确接受。源 CropBox、旋转或 UserUnit 规范化时要求 PAGE_GEOMETRY／DEGRADE；其他字段、评论、链接、书签、附件和元数据沿用[渲染损失策略](pdf-raster-api.md)。加密输入仍要求 owner 解密复制授权，且输出无加密需 ENCRYPTION_REMOVED／DEGRADE。

默认严格失败。任何页不支持渲染，即使策略请求 SKIP_BLOCK，也不能产生脱敏候选；没有保留原对象或静默漏页的降级路径。非法结构、签名、权限、预算、取消和 I/O 不可忽略。

`prepare()` 完成全部清理、最终验证和确切编码，再允许原子路径写入。借用流不关闭或刷新。调用方必须定位**所有需要清除的可见出现**；未选区域的敏感文字仍可从图像读到，不提供自动发现或 OCR。当前也不提供保留矢量、保留其他可编辑文字的选择性内容流脱敏。

设计见 [ADR-0063](adr/0063-fresh-raster-pdf-redaction.md)，证据见[阶段五十五](verification/phase-55-pdf-redaction.md)。
