# PDF 语义恢复与转换

`PdfAdapter.importDocument(input)` 将受支持的静态 PDF 恢复为可编辑的 `SectionedDocument`。随后可用统一转换入口编辑并输出 DOCX、HTML、Markdown 或重新布局的 PDF。它恢复内容和明确子集的结构，不承诺保留原分页、绝对位置或任意 PDF 无损往返；不执行 OCR。

## 调用

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Path;
import java.util.List;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("source.pdf"), "");
var policy = ProcessingPolicy.builder()
        .feature("PDF_LAYOUT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_VECTOR_GRAPHICS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var candidate = adapter.importDocument(input)
        // 零基页；调用方已知的表格外框及所有分格边界，单位 pt。
        .table(0, List.of(20d, 140d, 260d), List.of(80d, 120d, 150d), 1)
        .conversion().policy(policy)
        .edit(editor -> editor.replaceText("ALPHA", "EDITED-CELL"))
        .prepare(new DocxAdapter().conversionTarget());
var preview = candidate.preview();
var actual = candidate.to(Path.of("recovered.docx"));
assert preview.issues().equals(actual.report().issues());
```

若源没有表格，省略 `.table(...)`。表格格线不是自动识别结果；未提供网格的表格文字按普通文字行处理，必须接受布局推断损失。`HtmlAdapter`／`MarkdownAdapter` 的 `conversionTarget()` 可替换示例目标；重新生成 PDF 使用 `DocumentEngine` 和显式字体，完整调用见 [PdfSemanticImportExample](../examples/src/main/java/com/mirakyux/export4j/examples/PdfSemanticImportExample.java)。

单独导入时调用 `.policy(policy).prepare()`，通过 `document()`、`sources()`、`preview()` 检查候选，`execute()` 返回 ACTUAL 结果。没有打开的输入资源逃逸；候选及集合不可变。请求限当前任务线程使用，`conversion()` 快照网格、字体配置和执行选项，并采用转换请求的共享策略。

## 恢复规则

| 内容 | 当前行为 |
| --- | --- |
| 页面 | 每个有恢复内容的源页一个 section；保留纸张尺寸，输出采用流布局和明确默认边距，空白源页不生成空 section |
| 阅读顺序 | 字形按基线／X 坐标组成行，块按 Y／X 排序；不推断多栏、复杂重叠或语义阅读顺序 |
| 段落与标题 | 每个文字行一个段落，不合并多行原段落；字号达到该页字形字号中位数的 1.3 倍推断二级标题，1.6 倍推断一级标题 |
| 样式 | 保留可见文字、字号和 RGB 颜色；精确 BaseFont 名称可用 `.fontRole(pdfName, role)` 映射到模型角色，目标布局时另提供对应字体；不推断原字体族、粗斜体或段落样式 |
| 表格 | 显式递增 X／Y 网格及表头行数；原生 Table／TableCell，格内可有多行文字；不恢复合并、嵌套或图片格；边界切字、网格重叠／越界或与图片相交时失败 |
| 图片 | 实际执行 Do 的正向无剪切图片，解码为 PNG，保留像素和显示尺寸，作为独立流图片；不保持绝对浮动位置 |
| 来源 | 每块包含源页和未旋转页面左上角的物理 pt 框；文字框是字体度量近似框，不能当成精确字形轮廓或脱敏选区 |

只接受原点为零、CropBox 等于 MediaBox、零旋转、UserUnit 为 1 的页面。全部字体资源，包括未使用资源，须为[有效嵌入的静态 TrueType 或 CFF 子集](pdf-font-compatibility-api.md)；不会扫描系统字体。因而含未嵌入标准字体资源的 PDF 即使可显示，也不属于当前恢复子集。

Form、ExtGState、裁剪、不支持的文字绘制模式、复杂字体等不能直接恢复。可见字形必须具有合法 Unicode、几何及非重叠水平文字。不能恢复的整页只在相应 SKIP_BLOCK 策略下跳过；全部页面无可恢复内容时失败，不返回虚假的空文档成功结果。

## 为什么不能直接还原原文档

PDF 保存的是页面绘制结果，通常只有字形、路径、图片和坐标。表格边框可能与普通装饰线相同，文字对齐也可能来自分栏而非单元格；当前恢复器没有自动表格识别。调用方须用 `.table(page, xEdges, yEdges, headerRows)` 提供已知网格，才会生成可编辑的 Table／TableCell。省略网格时，原表格文字按普通文字行恢复，行列结构不保留；`PDF_LAYOUT` 明确报告几何推断和重排。

未嵌入字体的 PDF 常由阅读器使用本机字体替代，因此能在某台电脑打开，不代表能确定性地恢复 Unicode、字宽和位置。当前恢复预检检查全部字体资源，包括未使用或隐藏文字引用的资源，接受嵌入静态 TrueType，以及 Type1C、CIDFontType0C 和静态 OTF／CFF 的明确子集；不会通过系统字体回退放行。外部 PDF 的不支持字体仍按 `PDF_STRUCTURE` 的 SKIP_BLOCK 规则处理；损坏程序或实际缺字不能由此跳过。

Export4j 的行内图片和轮廓文字使用不可见文字锚点承载 ActualText 与选区。普通 PDF 输出现复用 PDFBox 依赖内已固定的 Liberation Sans，仅将锚点实际使用的字形子集嵌入；它不作为可见正文的缺字回退，也不扫描系统字体。完整输出和逐页输出使用同一规则，字体原始字节计入 `PdfLimits.maxFontBytes`，同字节字体只计一次；归档输出继续使用调用方的显式锚点字体。因此，这些不可见锚点不会再因使用未嵌入 Helvetica 而使整页被拒绝。

字体可接受与文字可恢复是两项检查。文字若已绘成路径轮廓，当前恢复器不会把路径或未经验证的 ActualText 当成可编辑正文；文字与图片混排的轮廓行也遵守此规则，损失分别记录为 `PDF_VECTOR_GRAPHICS`／`PDF_LOGICAL_TEXT`，隐藏锚点记录为 `PDF_HIDDEN_TEXT`。需要保留原始可编辑文字时，优先从语义模型或 DOCX／HTML／Markdown 源进行转换。

WARN／IGNORE 只能采用已有的明确降级方式。它们不能补出缺失的表格结构、执行 OCR 或绕过字体检查；若按策略跳过全部页面后没有可恢复内容，转换仍失败并保留旧目标文件。对于支持的输入，IGNORE 仍保留实际损失，只隐藏警告展示。

## 策略和损失

默认 ERROR，按操作 `RECOVER_PDF`、功能、目标格式 `MODEL`、默认值覆盖。WARN 和 IGNORE 的处理相同；IGNORE 仍返回 `issues()`／`losses()`。只支持下表对应的处理方式，没有原结构保留通道时不能配置 PRESERVE。

| 功能键 | 所需处理及影响 |
| --- | --- |
| PDF_LAYOUT | DEGRADE：阅读顺序、逐行段落、标题和重新排版属于几何推断，始终报告 |
| PDF_FONT_MAPPING | DEGRADE：没有显式映射的源字体使用模型默认角色 |
| PDF_VECTOR_GRAPHICS | DISCARD：路径绘制不恢复为语义块；表格外观由新模型重新生成 |
| PDF_LOGICAL_TEXT | DISCARD：ActualText／Alt／E 不替代已核对的字形文字 |
| PDF_HIDDEN_TEXT | DISCARD：隐藏或页面外文字不恢复 |
| PDF_PAGE_VIEW／PDF_STRUCTURE | SKIP_BLOCK：不支持的页视图、字体或结构使整页不恢复 |
| PDF_IMAGE_TRANSFORM | SKIP_MATCH：不支持的蒙版图片、旋转／剪切图片不恢复 |
| PDF_IMAGE_COLOR／PDF_IMAGE_PRECISION | DEGRADE：颜色转 RGB，超过 8 bit 的精度降为 8 bit |
| FORMS／ANNOTATIONS／BOOKMARKS／ATTACHMENTS／DOCUMENT_METADATA | DISCARD：相应源对象不映射到恢复模型 |
| ENCRYPTION_REMOVED | DEGRADE：模型不携带源加密；提取权限仍须满足 |

诊断包含阶段、功能、处理及源页位置，不默认回显正文或路径。签名、安全结构、权限、非法 Unicode、预算、取消及 I/O 失败不能忽略。真实字形、操作符、网格查找、模型节点、像素和编码数据均有额度；这不等于恶意输入沙箱或硬 RSS 限额。

导入后到其他格式，还需接受目标自己的实际损失，例如 Web 页几何或 Markdown 样式简化。统一转换 `prepare()` 已导入、编辑并编码确切候选；路径写出为同目录临时文件加原子替换，借用流不关闭。见[转换契约](document-conversion-api.md)、[ADR-0064](adr/0064-bounded-pdf-semantic-recovery.md)及[阶段五十六](verification/phase-56-pdf-semantic-import.md)。
