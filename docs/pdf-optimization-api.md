# PDF 优化

`PdfAdapter.optimize(input)` 完整重写受支持的被动 PDF；默认重新压缩无过滤／单层 Flate 流，并去重字节与属性完全一致的简单 RGB／Gray 图片和 TrueType FontFile2 程序流。不改变解码后内容，不扫描系统字体。其他资源保留，不承诺所有对象都能去重。

```java
var adapter = new PdfAdapter();
var input = adapter.input(source, "");
var candidate = adapter.optimize(input).prepare();
var statistics = candidate.statistics();
candidate.to(target);
```

`statistics()` 给出确切输入／输出字节、重新压缩流数、去重资源数，以及重采样图片数和前后像素数。文件更小不是成功条件；重新序列化、JPEG 头或已经紧凑的输入都可能使文件增大。`prepare()` 完成编码和检查，`to` 返回 ACTUAL 报告。路径原子替换，借用流不关闭、不刷新。

有损重采样需显式尺寸及策略，尺寸是编码像素上限，保持比例，不按页面推测 DPI：

```java
var policy = ProcessingPolicy.builder()
        .feature("IMAGE_RESAMPLING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("JPEG_ENCODING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
adapter.optimize(input).downsampleImages(1200, 1200).jpegQuality(.9)
        .policy(policy).prepare().to(target);
```

不设置 `jpegQuality` 时，新像素无损编码；双三次缩小本身仍需 IMAGE_RESAMPLING／DEGRADE。JPEG_ENCODING 另行报损。只处理简单 8 位 RGB／Gray 图片；软蒙版、其宿主、stencil、额外 Decode／ICC 等复杂资源只能通过 IMAGE_RESAMPLING_FORMAT／SKIP_MATCH 保留原图，不能无提示改变透明关系。IGNORE 仍保留损失记录。

加密输入仍须显式 owner 解密复制授权及 ENCRYPTION_REMOVED／DEGRADE。签名、脚本、非法结构、权限、预算、取消和 I/O 不能通过损失策略绕过。图层／静态标签默认拒绝，显式 `allowPassiveStructures(true)` 后只接受专用验证通过的[被动结构子集](pdf-passive-structures-api.md)，保留原结构并对优化候选再次预检；不重建标签或图层关系。当前不优化任意交互或归档 PDF，也不保证硬 RSS 上限。

设计见 [ADR-0061](adr/0061-measured-pdf-optimization.md)，证据见[阶段五十三](verification/phase-53-pdf-optimization.md)。
