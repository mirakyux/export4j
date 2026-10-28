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

源使用受支持的未嵌入简单字体或水平 Type0 时，可先通过 `.sourceFont(exactBaseFontName, PdfFont.of(fontBytes))` 显式提供静态 TrueType，并接受 `RENDER_FONT_MAPPING / DEGRADE`。映射复用[渲染子集](pdf-font-compatibility-api.md#渲染与脱敏的显式源字体映射)，不恢复可搜索文字，也不放宽任何页必须成功渲染的要求。有效传统 Type1 和受控 Type3 通过各自真实程序绘制，不调用隐式系统字体。

源含图层或静态标签时，先显式开启 `PdfAdapter.builder().allowPassiveStructures(true)`。脱敏使用默认 VIEW 的完整页面像素，新文件不复制关闭层、源标签树或隐藏资源；`PDF_LAYERS / DEGRADE` 和 `PDF_TAGS / DISCARD` 仍分别说明丢失的能力。默认视图渲染、读取和原对象保留的范围见[被动结构指南](pdf-passive-structures-api.md)。

默认严格失败。任何页不支持渲染，即使策略请求 SKIP_BLOCK，也不能产生脱敏候选；没有保留原对象或静默漏页的降级路径。非法结构、签名、权限、预算、取消和 I/O 不可忽略。

`prepare()` 完成全部清理、最终验证和确切编码，再允许原子路径写入。借用流不关闭或刷新。调用方必须定位**所有需要清除的可见出现**；未选区域的敏感文字仍可从图像读到，不提供自动发现或 OCR。

设计见 [ADR-0063](adr/0063-fresh-raster-pdf-redaction.md)，证据见[阶段五十五](verification/phase-55-pdf-redaction.md)。

## 保留选区外文字与矢量

`PdfAdapter.redactSelective(input)` 对受支持的不透明静态内容逐项重建。它保留选区外的可搜索填充文字、清理后的矢量轮廓和图像实例；不把所有页面变成图片。`area`、`fill`、`policy`、`execution` 和 `prepare` 的候选及原子输出契约与上述入口一致，区域同样使用旋转后的 CropBox 左上角物理 pt。

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("PDF_CONTENT_RECONSTRUCTION", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_CLIPPED_TEXT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_IMAGE_BOUNDARY", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_COLOR_MODEL", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var candidate = adapter.redactSelective(input)
        .area(0, 18, 25, 68, 22).fill(0)
        .policy(policy).prepare();
var preview = candidate.preview();
var actual = candidate.to(target);
assert preview.issues().equals(actual.report().issues());
```

| 内容 | 实际清理及保留方式 |
| --- | --- |
| 文字 | 字形可见包围框命中区域时删除整个字形编码，边缘可能扩大到区域外；其他字形保留实际位置、字号和填充颜色。保留文字仅接受有效嵌入的水平静态 TrueType 简单字体／Type0；重新生成真实字体子集、宽度及仅含保留字形的 ToUnicode，不复制源字体字典或源 CMap |
| 裁切文字 | 被源裁切完全遮去及不可见文字模式的文字丢弃；部分被源裁切路径截断的字形只保留可见轮廓，不保留隐藏 Unicode，要求 `PDF_CLIPPED_TEXT / DEGRADE`；不支持保留描边文字，用于修改裁切路径的文字模式直接拒绝 |
| 矢量 | 执行实际填充／描边及裁切，把选区从实际几何中扣除，再写全新轮廓；撤掉最终覆盖矩形也不会恢复选区内原路径 |
| 图像 | 每次实际调用独立解码、清洗并重新编码 RGB 像素；命中选区的像素填色，源裁切外及部分跨边界的像素保守清除，同时重建可见裁切。共享源图像的未选实例保留自身像素；边缘像素或抗锯齿可能变化，要求 `PDF_IMAGE_BOUNDARY / DEGRADE` |
| Form XObject | 执行普通不透明嵌套及重复实例的实际变换、资源和 BBox，结果展开为清理后的页面内容，不复制原 Form 流 |
| 页面与附属结构 | 按数值重建 MediaBox、CropBox、Rotate、UserUnit，保持原可见页视图；全新文档不复制原内容流、历史修订、元数据、附件、书签、表单、批注、标签或替代文字，它们按实际存在的 DISCARD 损失分别报告 |

默认严格失败。WARN／IGNORE 仅接受报告中列出的重建、丢弃及边缘变化；两者生成相同候选，IGNORE 仍保留完整实际损失。CMYK 填充转换为 RGB 时要求 `PDF_COLOR_MODEL / DEGRADE`。加密输入仍受 owner 权限控制，输出无加密要求 `ENCRYPTION_REMOVED / DEGRADE`。

透明绘制、透明组、图层、图案／渐变、软蒙版、非轴向图像变换、不支持的字体或绘制指令均拒绝产生候选，不提供保留原对象或漏处理部分内容的策略。PDF 指令操作数、BT／ET、BMC／BDC／EMC 和图形栈检查不依赖阅读器的容错；嵌套 Form、图形栈和标记深度累计收费。源字形及每次保留 Unicode 映射的 UTF-16 长度、图像像素、解码字节、操作、对象和最终输出均受预算控制，取消或失败保留旧目标。

这两个入口都需要调用方定位全部敏感出现。选择性入口不推断文字是否被后画的其他对象遮挡；未命中区域的文字仍可能保留可提取内容。它重建字体子集，但源字体程序的普通名称等标识仍可能出现在新字体中，不保证清除字体程序内任意私有载荷或隐写信息，也不执行敏感内容自动识别。对不受支持的内容可另行选择受支持的整页图片入口，并明确接受其能力损失。

## 选择性入口验收

`PdfSelectiveRedactorTest` 的 10 项测试通过。独立 [pypdf 输入](../scripts/create_pdf_selective_redaction_fixtures.py)包含 18 份 PDF：9 份成功输入，以及透明绘制、未知指令、累计深度、五类畸形文字／标记结构和 Unicode 展开预算的 9 份拒绝输入。[公开调用方](../verification/consumer/src/main/java/consumer/PdfSelectiveRedactionConsumer.java)使用本轮本地发布制品，在全新隔离 Maven 构建中完成 18 份 WARN／IGNORE 输出及 22 次拒绝；旧目标和源摘要保持。

[独立验证器](../scripts/verify_pdf_selective_redaction.py)核对可达对象及解码流、真实文字提取、新资源、每次图像调用的像素、来源页视图和实际损失。撤去最终覆盖层后，选区内原文字、路径及图像内容仍已删除；选区外 164 个样例字形的起点、字号、角度及双引擎实际墨迹框保持。Poppler／PDFium 产生的 80 张页面图、19 张联络图全部检查，含四种旋转方向和裁切。最终 Maven 输出的这 99 张 PNG 摘要与已目视产物全部一致；该证据不外推为任意输入或阅读器的安全保证。

复现时从仓库根执行，使用 `scripts/requirements-verification.txt` 和 Poppler；其他平台把 Wrapper 改为 `./gradlew`。`<consumer-build-directory>` 相对 `verification/consumer`，应使用新目录。

```powershell
python scripts/create_pdf_selective_redaction_fixtures.py
.\gradlew.bat :export4j-adapter-pdf:test --tests '*PdfSelectiveRedactorTest'
.\gradlew.bat build sbom publishLocalBundle
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build resolvedLibraries pdfSelectiveRedaction --refresh-dependencies
python scripts/verify_pdf_selective_redaction.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```
