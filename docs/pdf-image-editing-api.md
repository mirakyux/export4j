# PDF 图片绘制实例替换

`PdfAdapter.editImages(input)` 按页面中的实际绘制次序替换一个图片实例。共享的图片资源、重复调用的 Form 和跨页引用分别处理；只有命中的 `Do` 调用及其 Form 路径生成私有副本。

```java
var editor = adapter.editImages(input);
var instances = editor.instances();
var image = ImageResource.load(pngOrJpegBytes);
editor.replace(0, 2, image).prepare().to(target);
```

页码和 `occurrence` 均从零开始。`instances()` 按页面、实际深度优先调用次序返回图片像素尺寸及是否带蒙版；同一资源被画四次就有四个实例。后续替换继续读取前一次编辑后的文档，实例次序保持不变。内联 `BI` 图片仍不属于该入口。

新 PNG／JPEG 占据原图片的单位正方形，保留原有 `cm`、Form 的 Matrix／BBox、裁切与绘制顺序。像素尺寸可以不同，最终页面尺寸由原内容流决定。PNG 的显式 alpha 保留，JPEG 没有 alpha。新资源使用自身已验证的颜色编码，不继承源图的颜色空间或蒙版；不存在自动颜色管理或系统资源查找。

显式开启被动图层兼容时，替换资源保留源图像的已验证 `/OC` 成员引用；默认视图关闭的图像替换后仍保持隐藏。

源图含 stencil、Mask 或 SMask 时，默认拒绝。调用方可明确接受 `IMAGE_MASK / DISCARD`，其实际效果为 `SOURCE_MASK_REPLACED_BY_EXPLICIT_IMAGE_ALPHA`。WARN 和 IGNORE 使用相同替换结果，IGNORE 仍返回实际损失。带结构所有者的图片或 Form 调用路径只支持 `IMAGE_STRUCTURE / SKIP_MATCH`，避免私有克隆破坏标签归属；它们保持原样。

替换请求、实际调用、资源递归、执行深度、操作符、累计图片像素和总输入字节均受 `PdfLimits` 控制。非法索引、坏引用、循环 Form、权限、签名、预算和取消不由损失策略放行。`prepare()` 生成并预检完整候选；路径输出原子替换，失败保留旧目标。

这项操作保留原文档及其未使用资源，不能代替[安全脱敏](pdf-redaction-api.md)。独立夹具入口为 `scripts/create_pdf_instance_edit_fixtures.py`，调用方任务为 `pdfInstanceEditing`，检查脚本为 `scripts/verify_pdf_instance_editing.py`。
