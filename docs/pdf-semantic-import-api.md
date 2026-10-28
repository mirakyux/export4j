# PDF 语义恢复与转换

`PdfAdapter.importDocument(input)` 将受支持的静态 PDF 恢复为 `SectionedDocument`。可恢复的字形成为可编辑文字；模型不能表达但能够确定性渲染的页面，可按显式策略成为整页流图片。随后可用统一转换入口编辑并输出 DOCX、HTML、Markdown 或重新布局的 PDF。它恢复内容和明确子集的结构，不承诺保留原分页、绝对位置或任意 PDF 无损往返；不执行 OCR。

## 调用

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfSemanticImporter;
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
        .analysis(PdfSemanticImporter.Analysis.AUTO)
        // 可选：零基页，已知网格覆盖该区域的自动表格推断，单位 pt。
        .table(0, List.of(20d, 140d, 260d), List.of(80d, 120d, 150d), 1)
        .conversion().policy(policy)
        .edit(editor -> editor.replaceText("ALPHA", "EDITED-CELL"))
        .prepare(new DocxAdapter().conversionTarget());
var preview = candidate.preview();
var actual = candidate.to(Path.of("recovered.docx"));
assert preview.issues().equals(actual.report().issues());
```

若无需覆盖自动识别，省略 `.table(...)`。`analysis(AUTO)` 显式开启有界几何分析；默认 `LINES` 保持逐行恢复和显式网格行为，适合调用方自行处理行与来源。两种模式均报告布局推断，不承诺复原源作者的语义。`HtmlAdapter`／`MarkdownAdapter` 的 `conversionTarget()` 可替换示例目标；重新生成 PDF 使用 `DocumentEngine` 和显式字体，完整调用见 [PdfSemanticImportExample](../examples/src/main/java/com/mirakyux/export4j/examples/PdfSemanticImportExample.java)。

单独导入时调用 `.policy(policy).prepare()`，通过 `document()`、`sources()`、`preview()` 检查候选，`execute()` 返回 ACTUAL 结果。没有打开的输入资源逃逸；候选及集合不可变。请求限当前任务线程使用，`conversion()` 快照分析模式、网格、字体角色、栅格分辨率和执行选项，并采用转换请求的共享策略。

## 恢复规则

| 内容 | 当前行为 |
| --- | --- |
| 页面 | 每个有恢复内容的源页一个 section；保留纸张尺寸，输出采用流布局和明确默认边距，空白源页不生成空 section |
| 阅读顺序 | `LINES` 按基线／X 组成行，再按 Y／X 排序；`AUTO` 先用孤立标题分隔纵向区域，再按明确留白沟分栏，栏内从上到下、栏间从左到右；不使用内容流操作符顺序替代几何顺序 |
| 段落与标题 | `LINES` 每个文字行一个段落；`AUTO` 在同栏、字号接近和行距相容时合并连续行，用空格连接原换行；句末终止符、标题或新列表标记分段。字号达到页内中位数的 1.3／1.6 倍分别推断二／一级标题 |
| 列表与页眉页脚 | `AUTO` 将明确的项目符号及十进制序号恢复为可编辑列表，缩进推断层级；跨页重复且接近上／下边缘的短文字移入 PageRegions，不重复留在正文。页码仍为原文字，不推断动态字段 |
| 样式 | 保留可见文字、字号和 RGB 颜色；精确 BaseFont 名称可用 `.fontRole(pdfName, role)` 映射到模型角色，目标布局时另提供对应字体；不推断原字体族、粗斜体或段落样式 |
| 显式表格 | 两种模式均支持调用方提供的递增 X／Y 网格和表头行数；已知网格覆盖同区域自动推断。保留原生 Table／TableCell 及多行文字；非法网格或边界切字仍失败 |
| 自动表格 | `AUTO` 用相连的水平／垂直线段推断行列，缺少内部边界且可组成矩形时恢复纵／横合并；独立内网格生成嵌套 Table。稳定对齐的至少三行、二至八列，另有数字或短矩阵证据时推断无线表格；文字实际跨越既定列界时支持无线合并表头；不把空白本身当成合并证据 |
| 跨页表格 | `AUTO` 在相邻源页底部／顶部的表格几何和列宽相容时合并；相同单行文字表头只保留一份语义内容并设置重复表头，保留各页原来源框。不能安全组成可重复表头时保留完整数据行 |
| 图片 | 实际执行 Do 的正向无剪切图片，解码为 PNG，保留像素和显示尺寸，作为独立流图片；不保持绝对浮动位置 |
| Form XObject | 普通不透明 Form 按实际 Do 恢复，支持嵌套／重复、局部或继承资源、正向等比缩放／平移；可见文字自身也要求等比变换，隐式 BBox 检查可见性 |
| 复杂静态绘制 | 显式 `PDF_STRUCTURE / DEGRADE` 可把受支持渲染的整页恢复为一个流图片；文字、矢量、Type3 CharProc 和复杂裁切的外观成为像素，不保留其独立编辑能力 |
| 图层与静态标签 | 先在适配器显式开启 `allowPassiveStructures(true)`；含图层的文档按默认 VIEW 逐页成为流图片；只有标签的受支持文字仍可恢复，标签树及可访问性结构不进入模型 |
| 来源 | `Source` 为源页及左上角物理 pt 框；`AUTO` 的叶段落保留每条物理行框，表格另有区域框；一个块可有多行或多页来源。内联表头通过 `cellPath` 的零基物理行／列对定位，空路径表示块本身；嵌套路径逐对下钻。它不是字符偏移或精确字形轮廓，不能当成脱敏选区 |

只接受原点为零、CropBox 等于 MediaBox、零旋转、UserUnit 为 1 的页面。其他页视图仍只能按 `PDF_PAGE_VIEW / SKIP_BLOCK` 拒绝或跳过，不能通过整页图片降级扩大几何范围。全部字体资源，包括未使用资源，须通过[真实字体程序检查](pdf-font-compatibility-api.md)；可编辑字形路径使用有效嵌入的静态 TrueType、CFF 及传统 Type1 子集，受控 Type3 只能走整页图片路径。此入口不扫描系统字体，也不提供渲染／脱敏的显式源字体映射；含未嵌入标准字体资源的 PDF 即使可显示，也不能因此恢复成功。

普通不透明 ExtGState 可按明确白名单恢复：支持普通线条参数，`CA`／`ca` 只能为 1，`BM` 只能为 Normal／Compatible，`SMask` 只能为 None。字典类型、数值范围、`gs` 操作数及资源存在性均检查；状态通过实际内容引擎执行，颜色、文字位置与 q／Q 恢复保持。线条及其他路径仍按 `PDF_VECTOR_GRAPHICS / DISCARD` 报损，不会转成语义图形。

ExtGState 字体、透明度、其他混合模式、软蒙版、转移函数及其他不属于语义白名单的状态，不恢复为可编辑块。可通过 `PDF_STRUCTURE / SKIP_BLOCK` 拒绝或跳过整页；若整页通过渲染资源和执行检查，也可显式选择 `PDF_STRUCTURE / DEGRADE` 保留其可见像素。畸形状态、缺失资源及错误操作数无条件失败，不能由 WARN／IGNORE 放行。语义状态白名单见[初始决策](adr/0094-explicit-pdf-source-fonts-and-passive-graphics-state.md)。

普通 Form 的 Matrix、资源作用域及退出后的状态恢复由实际内容引擎执行；同一对象多次调用按实例恢复并累计预算，不按资源去重丢掉正文。局部 Resources 完整替换调用环境，只有缺省时才继承；不会混合两个字典。未调用的 Form 不再仅因存在而跳过整页，但全部资源的嵌入字体检查保持。

Form 隐式 BBox 以实际变换后的字形轮廓及图片矩形检查：完全隐藏文字按 `PDF_HIDDEN_TEXT / DISCARD` 丢弃，完全隐藏图片按 `PDF_IMAGE_TRANSFORM / SKIP_MATCH` 跳过；部分裁切使整页进入 `PDF_STRUCTURE` 策略，选择跳页或受支持的整页图片降级，已收集的同页正文不会作为部分成功返回。显式 W／W*、透明组及其他不能表达的绘制同样遵守整页边界。循环、非法 BBox／Matrix、缺失资源、预算或取消不能由策略放行，普通 Form 恢复规则见 [ADR-0095](adr/0095-bounded-form-xobject-semantic-recovery.md)。[原 Form 文字编辑](pdf-text-editing-api.md)另按命中调用链复制后修改，不依赖语义重排；带结构所有者的 Form 不通过复制修改破坏标签引用。

可编辑文字必须具有合法 Unicode、几何及非重叠水平字形。不能恢复的整页按实际支持的 `SKIP_BLOCK` 或 `DEGRADE` 处理；全部页面跳过而无可恢复内容时失败，不返回虚假的空文档成功结果。

## 整页可见图片降级

```java
var adapter = PdfAdapter.builder().allowPassiveStructures(true).build();
var input = adapter.input(Path.of("source.pdf"), "");
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_TAGS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var candidate = adapter.importDocument(input)
        .rasterDpi(144)
        .policy(policy).prepare();
var recovered = candidate.document();
var report = candidate.execute().report();
```

示例接受 `PDF_STRUCTURE / DEGRADE`、`PDF_LAYOUT / DEGRADE` 和实际存在的 `PDF_LAYERS / DEGRADE`；源还含表单、批注、书签或附件时，须按下表另接受对应 DISCARD。没有图层／标签的普通输入无需开启被动结构。图层和标签本身须先通过完整专用验证，开关和策略均不能放行坏引用、活动内容或循环。

`.rasterDpi(...)` 默认 144，范围 `(0,1200]`，只影响显式选择的整页图片降级。每页按源默认 VIEW 渲染为白底 RGB PNG，关闭图层及裁切区外内容不进入图片；不会把源文字另外存入模型作为隐藏正文。标签树及可访问性信息按 `PDF_TAGS / DISCARD` 丢弃，不承诺 PDF/UA。原图层／标签结构的保留条件见[被动结构指南](pdf-passive-structures-api.md)。

模型使用源纸张尺寸和零边距，每页放入一个流图片段落。PNG 保留该分辨率的完整页面像素；图片显示宽高按源页等比缩至 95%，为段落行框预留空间。`sources()` 的框仍对应完整源页；导出会重新布局，图片的绝对位置、最终分页和原版尺寸不构成往返保证。图片中的正文、表格、路径与原对象无法再逐项编辑，选择该路径意味着接受这些损失。

降级仅用于有效且具备受支持渲染路径的页面。缺少真实字体程序、不支持的绘制资源或外观、实际缺字和损坏程序不能转成图片成功；`SKIP_BLOCK` 仍可按明确允许的资源损失跳过整页。非法结构、权限、签名、预算或取消保持硬失败。

## 为什么不能直接还原原文档

PDF 保存的是页面绘制结果，通常只有字形、路径、图片和坐标。表格边框可能与普通装饰线相同，文字对齐也可能来自分栏而非单元格。`AUTO` 从已验证的几何证据推断结构，实际诊断记录多栏顺序、段落连接、标题／列表、重复页眉页脚、有线／无线表格、合并格、内表及跨页连接；这些推断仍可能与作者原意不同。调用方拥有权威网格时，应使用 `.table(page, xEdges, yEdges, headerRows)` 覆盖自动判断。

自动分析采用明确阈值：同一行的大空隙分割文字片段；有线网格按轴向线段聚类，短断口不会创建新行，内部边界覆盖不足 70% 时合并相邻格，非矩形合并拒绝推断；无线表格需要重复对齐和内容证据。只有文字列对齐而缺少表格证据时保留普通正文；网格冲突、边界切字或无法形成矩形时保留原文字，并记录包含 `UNCERTAIN` 的 `PDF_LAYOUT` 效果。拒绝策略可阻止候选产生，WARN／IGNORE 接受的是这份明确报损的候选。

无线表格的空格不能证明纵向合并，任意不规则排版、斜边、装饰框、旋转文字、脚注引用、浮动图文的作者意图也不能从几何唯一还原。当前自动子集不执行 OCR、自然语言语义分析或对不可信 ActualText 的替代提取。原图片可以作为独立流图片或被完整容纳于自动单元格，原绝对位置和精确段落样式不构成恢复保证。输出 Markdown 时合并格及嵌套表结构仍受目标表达能力限制，相关目标损失另行报告。

未嵌入字体的 PDF 常由阅读器使用本机字体替代，因此能在某台电脑打开，不代表能确定性地恢复 Unicode、字宽和位置。当前恢复预检检查全部字体资源，包括未使用或隐藏文字引用的资源，接受嵌入静态 TrueType、Type1C、CIDFontType0C、静态 OTF／CFF 和传统 Type1 的明确子集；受控 Type3 只能恢复整页像素。不会通过系统字体回退放行。不支持的字体可按 `PDF_STRUCTURE / SKIP_BLOCK` 跳页；配置 DEGRADE 仍不能制造缺失的字体程序。损坏程序或实际缺字不能由策略跳过。

Export4j 的行内图片和轮廓文字使用不可见文字锚点承载 ActualText 与选区。普通 PDF 输出现复用 PDFBox 依赖内已固定的 Liberation Sans，仅将锚点实际使用的字形子集嵌入；它不作为可见正文的缺字回退，也不扫描系统字体。完整输出和逐页输出使用同一规则，字体原始字节计入 `PdfLimits.maxFontBytes`，同字节字体只计一次；归档输出继续使用调用方的显式锚点字体。因此，这些不可见锚点不会再因使用未嵌入 Helvetica 而使整页被拒绝。

字体可接受与文字可恢复是两项检查。文字若已绘成路径轮廓，当前恢复器不会把路径或未经验证的 ActualText 当成可编辑正文；文字与图片混排的轮廓行也遵守此规则，损失分别记录为 `PDF_VECTOR_GRAPHICS`／`PDF_LOGICAL_TEXT`，隐藏锚点记录为 `PDF_HIDDEN_TEXT`。需要保留原始可编辑文字时，优先从语义模型或 DOCX／HTML／Markdown 源进行转换。

WARN／IGNORE 只能采用已有的明确降级方式。它们不能把不确定结构变成权威语义、执行 OCR 或绕过字体检查；整页图片中的表格外观不等于可编辑单元格。若按策略跳过全部页面后没有可恢复内容，转换仍失败并保留旧目标文件。对于支持的输入，IGNORE 仍保留实际损失，只隐藏警告展示。

## 策略和损失

默认 ERROR，按操作 `RECOVER_PDF`、功能、目标格式 `MODEL`、默认值覆盖。WARN 和 IGNORE 的处理相同；IGNORE 仍返回 `issues()`／`losses()`。只支持下表对应的处理方式，没有原结构保留通道时不能配置 PRESERVE。

| 功能键 | 所需处理及影响 |
| --- | --- |
| PDF_LAYOUT | DEGRADE：始终报告几何恢复和重新排版；`AUTO` 另逐项报告分栏、段落连接、标题／列表、页眉页脚、表格／合并／嵌套／跨页及不确定结构的实际影响 |
| PDF_FONT_MAPPING | DEGRADE：没有显式映射的源字体使用模型默认角色 |
| PDF_VECTOR_GRAPHICS | DISCARD：路径绘制不恢复为语义块；表格外观由新模型重新生成 |
| PDF_LOGICAL_TEXT | DISCARD：ActualText／Alt／E 不替代已核对的字形文字 |
| PDF_HIDDEN_TEXT | DISCARD：不可见绘制模式、页面外或完全被 Form BBox 隐藏的文字不恢复 |
| PDF_PAGE_VIEW | SKIP_BLOCK：不支持的原点、CropBox、旋转或 UserUnit 使整页不恢复 |
| PDF_STRUCTURE | SKIP_BLOCK：不能表达的字体或结构使整页不恢复；或显式 DEGRADE：有受支持渲染路径的整页成为流图片，文字／路径／对象不再独立编辑 |
| PDF_LAYERS | DEGRADE：含图层的源按默认 VIEW 恢复为逐页流图片，不保留图层切换 |
| PDF_TAGS | DISCARD：标签树、可访问性结构及标签阅读顺序不恢复 |
| PDF_IMAGE_TRANSFORM | SKIP_MATCH：不支持的蒙版图片、旋转／剪切图片或完全被 Form BBox 隐藏的图片不恢复 |
| PDF_IMAGE_COLOR／PDF_IMAGE_PRECISION | DEGRADE：颜色转 RGB，超过 8 bit 的精度降为 8 bit |
| FORMS／ANNOTATIONS／BOOKMARKS／ATTACHMENTS／DOCUMENT_METADATA | DISCARD：相应源对象不映射到恢复模型 |
| ENCRYPTION_REMOVED | DEGRADE：模型不携带源加密；提取权限仍须满足 |

诊断包含阶段、功能、处理及源页位置，不默认回显正文或路径。签名、安全结构、权限、非法 Unicode、预算、取消及 I/O 失败不能忽略。真实字形、操作符、网格查找、模型节点、图片像素和编码数据均有累计额度；同页语义检查后的整页渲染继续使用剩余额度，不按页面或绘制实例重置预算。这不等于恶意输入沙箱或硬 RSS 限额。

自动分析的线段相交、候选匹配、字形归属和排序同样累计消耗 `maxContentOperators` 工作额度，新增模型 ID 使用 `maxObjects`，递归有深度限制并同步响应取消；预算耗尽不能通过 IGNORE 继续或返回部分结构。

导入后到其他格式，还需接受目标自己的实际损失，例如 Web 页几何或 Markdown 样式简化。统一转换 `prepare()` 已导入、编辑并编码确切候选；路径写出为同目录临时文件加原子替换，借用流不关闭。见[转换契约](document-conversion-api.md)、[ADR-0064](adr/0064-bounded-pdf-semantic-recovery.md)、[初始验收](verification/phase-56-pdf-semantic-import.md)和[自动结构恢复验收](verification/phase-95-pdf-automatic-recovery.md)。
