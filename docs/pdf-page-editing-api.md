# 已有 PDF 的页面与叠加编辑

`PdfAdapter.editPages` 编辑经过严格预检的静态 PDF，不重排原内容。页索引从零开始，操作按声明顺序执行。

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.adapter.pdf.PdfPageEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("source.pdf"), "");
var style = new PdfPageEditor.TextStyle(
        PdfFont.of(Files.readAllBytes(Path.of("explicit-font.ttf"))), 12, 0x224466, .7);
var policy = ProcessingPolicy.builder()
        .feature("PAGE_CROP", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = adapter.editPages(input).policy(policy)
        .rotate(0, 90)
        .text(0, "Page 1", 24, 90, style, PdfPageEditor.Layer.OVER_CONTENT)
        .crop(0, new PdfPageEditor.Rectangle(10, 10, 200, 250))
        .prepare();
prepared.to(Path.of("edited.pdf"));
```

## 坐标与顺序

坐标是**当前未旋转 MediaBox 左上角的物理 pt**，先于阅读器 /Rotate。X 向右、Y 向下；文字 Y 是基线。非零 MediaBox 原点与 UserUnit 由适配器换算。叠加和裁剪框必须完整位于 MediaBox；文字以实际字体宽度、上升／下降量检查，不自动缩小或换行。

`rotate` 增加顺时针 90 度的整数倍。`scale` 围绕原生 PDF (0,0) 等比缩放原内容和 Media／Crop／Bleed／Trim／ArtBox，保持 UserUnit 与阅读器旋转；后续操作使用新框。UNDER_CONTENT／OVER_CONTENT 明确前置或后置内容流；多次前置按内容流插入顺序叠放。

文字可作为水印、页码或标签，页码是调用方明确传入的静态值。`image` 接受已验证 ImageResource，可用于图像印章；不涉及签章认证。

## 损失与拒绝

裁剪是 PAGE_CROP／CROP／DEGRADE，效果 `OUTSIDE_CROP_HIDDEN_NOT_REMOVED`；默认严格失败，WARN／IGNORE 需明确 DEGRADE。隐藏内容仍能从源内容流恢复，绝不能称为脱敏。相同策略也用于 ENCRYPTION_REMOVED／PREPARE；仍须先配置 allowDecryptedCopy 并通过 owner 验证。

字体显式快照且有 16 MiB 上限，只接受静态 TrueType，检查嵌入权限、保留禁止子集标志。简单水平文字支持受限脚本；组合标记、双向及需塑形的脚本为 TEXT_SHAPING／STAMP_TEXT，默认 ERROR，仅允许 SKIP_MATCH 跳过整个叠加，保留 issues。缺字、非法 Unicode、尺寸、权限、预算、取消和 I/O 继续失败，不能靠忽略策略强行写出。

静态入口继续拒绝交互对象、签名、标签、图层、归档声明等未实现结构。原有文档属性和原内容流保留；XML／PDF 字节整体相同不在契约中。此能力不包含原文字替换、OCR 或真正内容删除。

## 准备、预算和输出

prepare 固定请求快照并完成一次编码。PreparedPdf.preview 返回 PREVIEW；成功 to 返回 ACTUAL，issues 一致。请求限当前线程，准备结果可用于不同目标并发写出。输出为有界内存快照，不能解释为硬 RSS 限制。

PdfLimits 控制输入、对象、页、解码、文字、图片像素及输出。单次最多 10,000 项编辑，且不超过 maxObjects；新增字体／图片编码累计受 maxTotalInputBytes 控制。图片调用按实际使用次数保守计费。

路径输出同目录临时文件、最终检查后原子替换，不降级复制；失败保留旧目标。借用流不关闭、不刷新，物理失败可能留下部分字节。例见 [PdfPageEditingExample](../examples/src/main/java/com/mirakyux/export4j/examples/PdfPageEditingExample.java)。
