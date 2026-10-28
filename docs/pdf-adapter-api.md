# PDF 处理 API

各入口有独立输入子集和损失契约，不因一个入口支持某种对象而放宽其他入口的拒绝规则。

| 任务 | 入口及指南 |
| --- | --- |
| 读页、普通字形提取、静态选页／合并 | `read`／`copy`／`merge`，见下文 |
| 旋转／裁剪／缩放、显式图文叠加 | `editPages`，[页级编辑](pdf-page-editing-api.md)；裁剪隐藏未删除 |
| 被动表单、批注、链接、书签、附件、Info | `readObjects`／`editObjects`，[对象编辑](pdf-object-editing-api.md) |
| 含被动对象的选页与跨来源合并 | `composeObjects`，[对象组合](pdf-object-composition-api.md) |
| 已有 PDF 渲染为 PNG／JPEG、提取图片资源 | `render`／`extractImages`，[渲染与提取](pdf-raster-api.md) |
| 流压缩、资源去重及显式图片重采样 | `optimize`，[优化](pdf-optimization-api.md)；不保证输出更小 |
| 有界真实文字替换 | `editText`，[文字编辑](pdf-text-editing-api.md)；不是安全脱敏 |
| 区域像素清除并新建纯图片 PDF | `redact`，[脱敏](pdf-redaction-api.md)；损失全文搜索及矢量编辑 |
| 恢复可编辑模型再转 DOCX／HTML／Markdown | `importDocument`，[语义恢复](pdf-semantic-import-api.md)；显式网格、几何推断，无 OCR |

下文描述默认严格读取／静态复制入口。显式 `allowPassiveStructures(true)` 可启用已验证图层／标签的读取和完整原文档保留路径，见[被动结构 API](pdf-passive-structures-api.md)；选页／跨来源组合仍拒绝这些文档。

`export4j-adapter-pdf` 的读取／静态复制通道提供逐页信息、文字提取、选择页复制与合并，直接处理既有对象，不经过布局引擎；语义恢复另走上表的明确损失通道。公共类型位于 `com.mirakyux.export4j.adapter.pdf`，签名不暴露 PDFBox。

## 常用入口

```java
var pdf = PdfAdapter.builder()
        .limits(PdfLimits.builder().maxPages(100).build())
        .build();

// 密码始终显式传入。无密码时传空字符串；不获取 URL。
var report = pdf.input(Path.of("report.pdf"), "");
var appendix = pdf.input(Path.of("appendix.pdf"), "");

var read = pdf.read(report);
for (var page : read.pages()) {
    String extracted = page.text();
    // 调用方决定如何使用文字和结构化诊断。
}

// 物理页零基。顺序与重复均有明确含义。
var result = pdf.merge(List.of(report.pages(2, 0), appendix.allPages()), Path.of("combined.pdf"));
pdf.copy(report.pages(0), Path.of("first-page.pdf"));
```

`input(byte[], password)` 在检查大小后复制字节。`input(InputStream, password)` 生成有界快照，最多读取 `maxInputBytes + 1` 字节，不关闭借用流。`input(Path, password)` 只打开调用方指定的本地文件，并关闭自身打开的流。密码最多 1,024 个 UTF-16 单元；它保留在 `PdfInput` 生命周期内，不通过 getter、`toString()` 或默认异常消息暴露。

`PdfInput` 不持有打开的文件或 PDFBox 文档；它可重复使用，也可跨线程共享。每次操作重新解析并核对当前适配器的预算。`pages(int...)` 拒绝空选择和负数，解析后检查上界；重复同一个页索引会生成多个物理页。`allPages()` 保持原顺序。

## 支持与拒绝边界

| 项目 | 首版行为 |
| --- | --- |
| 静态页面 | 保留内容流、嵌入字体／图像及资源、原始页面框、继承资源、旋转和 UserUnit；不重新绘制、换行或分页 |
| 页面选择 | 零基索引、重排、重复、多个来源合并；选择单页或多页即拆分到一个新文件 |
| PDF 版本 | 接受 1.0～2.0 的受支持静态结构；新文件版本至少为全部输入中的最高版本 |
| 压缩 | 无滤镜、单个 Flate；图像另允许单个 DCT；拒绝未知滤镜和多滤镜链 |
| 内容语法 | 前置有界括号／字典／字符串／十六进制／转义检查；操作符白名单，名称或裸 token 最多 256 字节；拒绝未知操作符，包括 BX／EX 中的未知操作符 |
| 图像 | 普通 XObject 图像按编码资源复制；拒绝 BI 行内图像；DCT 不栅格化，仅检查声明尺寸与编码字节预算 |
| 字体 | 页面复制保留已编码字体资源；静态 Type3 先校验声明与 CharProc 内容；复制不替换字体，渲染所需程序及映射见[字体兼容](pdf-font-compatibility-api.md) |
| 交互与签名 | 批注、链接、AcroForm、动作、目录书签、签名／权限字典、附件明确拒绝；不静默剥离 |
| 文档语义 | 默认拒绝图层、结构树、PDF/A OutputIntents 和集合；被动 BMC／BDC 普通字形可读，ActualText 不替代。显式被动结构选项只放开已验证的图层／标签；不声明 PDF/UA、PDF/A 或签名合规性 |
| 文档属性 | 新文件不复制 Info、XMP、语言及阅读器显示设置；遇到相应数据返回 `DOCUMENT_METADATA_NOT_COPIED` |

拒绝检查覆盖可达结构和交叉引用表中的对象。即使选择页未引用某个不支持对象，也不能用选择页操作绕过检查。首版也不提供压缩优化、图片重采样、覆盖文字、OCR、标签重建、复杂交互的合并保留或字节级无损保存。

## 文字结果的含义

`ReadResult.pages()` 返回全部物理页。每项包含零基索引、MediaBox、CropBox、旋转、UserUnit 和文字。框坐标采用 **PDF 原生左下角原点**，数值为默认用户空间单位；乘以 `userUnit` 后为 point。它与 Export4j 布局的左上角坐标不同。

文字采用 PDFBox 的内容顺序启发式，并把行分隔统一为 LF。它不恢复语义段落、表格或可靠的阅读顺序；扫描页和纯轮廓页不能获得普通字形文字。

发现标记内容的 `/ActualText` 时，结果返回该页的 `ACTUAL_TEXT_NOT_APPLIED`。适配器保留 PDFBox 普通字形提取结果，不擅自把多层 BDC 拼接或替换为逻辑文本。Export4j 轮廓文字的不可见 `x` 锚点可能因此被提取为 `x`；调用方须检查诊断，不能把该结果称为完整原文。复制页面仍保留原内容流中的 `/ActualText`。

## 密码与权限

文字读取要求输入密码有效，并具有提取权限。错误／缺少密码返回 `PASSWORD`，没有提取权限返回 `PERMISSION_DENIED`。Owner 密码具有相应权限。

复制加密 PDF 默认返回 `ENCRYPTED_COPY_REJECTED`。仅显式配置 `allowDecryptedCopy(true)`，并提供标准密码 handler 认证的 owner 密码，才允许生成未加密的新文件；结果返回 `ENCRYPTION_REMOVED`。Reader 密码即使全部权限开放也不能用来解除输出加密。身份判断使用 PDFBox 3.0.8 标准 handler 的认证状态：user 权限对象总是锁定只读，owner 对象保持未锁定；不单凭权限位推断身份，也不重新实现密码规范化。其他安全 handler 的复制明确拒绝。适配器不保存原加密、不寻找凭证、不绕过权限。

## 预算与执行控制

`PdfLimits.builder()` 的默认值如下。除单输入／单流限制外，预算在一次读取或合并操作内累计；重复引用同一个 `PdfInput` 仅解析一次，独立快照仍分别计入来源。

| 参数 | 默认值 | 计量范围 |
| --- | ---: | --- |
| `maxInputBytes` | 16 MiB | 单个编码快照；分配快照前验证 |
| `maxTotalInputBytes` | 64 MiB | 全部不同来源快照 |
| `maxInputs` | 32 | 不同来源快照数量 |
| `maxPages` | 1,000 | 源物理页累计与输出选择页累计分别受限 |
| `maxObjects` | 100,000 | 对象键、外层解析节点、图节点、内容 token／内层元素各自累计受限 |
| `maxDepth` | 64 | 页面树、对象图、内容结构、图形保存栈及提取中的 form 调用深度；配置上限 256 |
| `maxResources` | 10,000 | 资源字典引用中的 Font／XObject 等条目；重复资源字典引用可重复计数 |
| `maxDecodedStreamBytes` | 16 MiB | 单个不同流的预检展开字节；DCT 按原始编码字节计 |
| `maxDecodedBytes` | 64 MiB | 不同流的上述字节累计，包括 XRef／ObjStm |
| `maxTextCharacters` | 1,000,000 | 已观察字形文字和最终输出文字分别按 UTF-16 单元累计，输出空白也计入 |
| `maxContentOperators` | 1,000,000 | 内容预检中的不同流操作符；文字提取时的实际执行操作符另独立累计受限 |
| `maxImagePixels` | 16,000,000 | 不同图像流声明的宽×高累计 |
| `maxOutputBytes` | 64 MiB | 最终编码输出；另受 `ExecutionOptions.maxOutputBytes` 控制 |

内部解析器先对 XRef 流检查，再在 ObjStm 解密后、对象解析前，以 JDK `InflaterInputStream` 检查解压规模；其余流在内容解释前检查。内容扫描在 PDFBox 构建 token 前限制嵌套和操作符名；数组／字典元素在解析入口计数，解析错误不得被 PDFBox 当作 EOF 吞掉。延迟对象解析的失败同样跨越 PDFBox 的异常捕获边界保留，不能以空对象代替失败。

这些边界**不是恶意输入沙箱或精确 RSS／CPU 上限**。PDFBox 的对象流内部解析、字体内部解析及部分底层分配仍在第三方调用内发生；ObjStm 内部节点的对象预算在展开字节限制后、图检查时验证。DCT 声明尺寸不等于独立 JPEG 解码器的完整验证。适配器不解码图像，也不能限制外部阅读器后续解析的资源消耗。需要处理不可信大规模输入时，调用方仍应采用独立进程及操作系统资源限制。

`read(input, ExecutionOptions)` 与 `merge(selections, output, ExecutionOptions)` 在调用线程中传播取消、超时、进度及输出预算，不创建后台线程。阶段名为 `PDF_INSPECT`、`PDF_EXTRACT`、`PDF_COPY`、`PDF_SAVE`；进度只包含阶段和索引。阻塞输入流应自行提供 I/O 超时；第三方调用在返回或内部检查点才观察取消。

## 输出、异常和资源所有权

流输出始终借用；不会关闭调用方流。输入与全部页选择在写入首字节前预检。写入过程仍可能因 I/O、取消或输出预算失败而留下部分字节，调用方应丢弃失败输出。

Path 输出先在目标同目录建立临时文件，完成全部写入并关闭后执行 `ATOMIC_MOVE + REPLACE_EXISTING`。父目录须存在；不支持原子移动时失败，不降级为普通复制。提交前取消或失败保留原目标并清理临时文件；最后一个协作式取消检查位于提交前。

`PdfOperationException` 提供稳定 `code()`、零基 `inputIndex()`／`pageIndex()`；尚未到达对应项目时为 `-1`。默认消息不含正文、密码或路径。保留的 cause 用于可信诊断，可能带有底层细节，不能直接展示给非可信调用方。执行控制失败沿用模型的 `ExecutionException`。

实例不共享可变文档、缓存或打开资源；所有 PDFBox 文档在操作结束时关闭。库只复用已经登记的 Apache-2.0 PDFBox 3.0.8 依赖，不包含竞争库实现。

完整调用见 [PdfOperationsExample](../examples/src/main/java/com/mirakyux/export4j/examples/PdfOperationsExample.java)，运行 `:examples:pdfOperations` 及 `python scripts/verify_pdf_operations.py`。实现决策见 [ADR-0027](adr/0027-pdf-operations-adapter.md)，构建、42 项行为测试、独立选页像素与隔离坐标证据见[阶段二十二](verification/phase-22-text-and-adapters.md)。这些证据仍限于明确静态子集。
