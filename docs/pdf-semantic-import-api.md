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

只接受原点为零、CropBox 等于 MediaBox、零旋转、UserUnit 为 1 的页面。全部字体资源，包括未使用资源，须为嵌入的静态 TrueType；不会扫描系统字体。因而含未嵌入标准字体资源的 PDF 即使可显示，也不属于当前恢复子集。

Form、ExtGState、裁剪、不支持的文字绘制模式、复杂字体等不能直接恢复。可见字形必须具有合法 Unicode、几何及非重叠水平文字。不能恢复的整页只在相应 SKIP_BLOCK 策略下跳过；全部页面无可恢复内容时失败，不返回虚假的空文档成功结果。

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
