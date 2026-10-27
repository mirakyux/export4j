# 将语义文档保存为 XML 模板

`XmlTemplateWriter` 把支持的 `Document`／`PageDocument`／`SectionedDocument` 写成现有版本 1 XML，并以普通 ZIP 保存图片和调用方显式提供的字体／许可。重开后仍使用 `XmlTemplate → DocumentTemplate`，可以编辑源码，也可以先恢复模型再调用语义编辑与统一转换。

这是模型到既有模板语法的映射。动态模板的变量、条件、循环及片段应继续使用 [XmlTemplateBundle](xml-template-bundle-api.md) 保存原源码；渲染后的模型没有这些编写意图。

## 保存、重开和编辑

以下代码假定 `document` 是已构造的 `Document`，目标父目录存在，字体和许可为调用方有权分发的显式资源。

```java
var prepared = XmlTemplateWriter.from(document)
        .font("Regular.ttf", fontBytes)
        .license("Font.txt", licenseText)
        .prepare();
var preview = prepared.preview();
var actual = prepared.to(Path.of("report.zip"));

var bundle = XmlTemplateBundle.read(Files.readAllBytes(Path.of("report.zip")));
var restored = bundle.compile().render(Map.of());
var edited = DocumentEditor.edit(restored)
        .replaceText("summary", "草稿", "已复核").execute();
var request = XmlTemplateWriter.from(edited.document());
bundle.fonts().forEach(request::font);
bundle.licenses().forEach(request::license);
request.prepare().to(Path.of("edited.zip"));
```

单节带页区文档使用 `from(PageDocument)` 保存，重开使用 `renderWithPageRegions(Map.of())`。

类型分别位于 `com.mirakyux.export4j.adapter.template` 和 `com.mirakyux.export4j.model.edit`。分节文档使用 `from(SectionedDocument)` 保存，重开使用 `renderSections(Map.of())`；普通 `render` 不会扁平化分节，而是明确拒绝。

`font` 在加入时复制字节；`prepare` 固定所有配置、策略、报告和确切 ZIP 编码，先编译产生的 XML，再返回不可变结果。多次 `to` 不会再次运行编辑／编码。PREVIEW 与成功写出的 ACTUAL 保留相同 issues；I/O 失败不返回成功报告。

## 可保留内容

| 范围 | 当前映射 |
| --- | --- |
| 文档／分节 | 标题、作者、节 ID／顺序、每节纸张／四边边距、显示页码起点 |
| 页区 | 默认／FIRST／EVEN 页眉页脚、显式空对及正高度空区；背景／前景的固定位置、尺寸及顺序；共享图文／表格源、PAGE／PAGES 与完整样式 |
| 正文 | 段落、六级标题、无序／十进制列表、显式换页、原文与 ID |
| 段落格式 | 字号、行高、对齐、前后间距、两种缩进、断行策略、整段保持、跟随下一块、段前分页、分页前后最少行数 |
| 富文本／列表标记 | 完整字体角色、字号、颜色、基线位移、下划线／删除线；列表标记的样式独立于正文，保留级别、自定义 bullet 及编号重启 |
| 表格 | 固定／比例／有界 Auto 列、重复表头、行列跨度、递归块格、拆行开关、四边 padding／前后间距、格格式与物理锚点 |
| 行内对象 | 段落／行内格的原 PNG／JPEG、尺寸／位移／替代文字／外链；PAGE、PAGES、页码引用、编号引用及其宽度／样式 |
| 显式资源 | 原图片字节去重；字体原字节及许可文本，文件名由调用方指定 |

所有几何单位为 pt。原文 UTF-16 顺序、字段／图片的 U+FFFC 位置保留。保存的 XML 使用 `literal-text="true"`，`${...}`、未闭合 `${`、CR／LF／TAB 和补充平面字符均按正文保存；XML 特殊字符安全转义，换行／制表使用数字实体避免属性归一化。非法 XML 1.0 Unicode 无条件失败。

已有纯文本短构造器可能在重开后表现为显式默认运行；空文本及对象运行也可被既有绑定器规范化。这不改变文字、完整格式或行内对象，但不承诺 Java record 的内部表示、主题名称／继承意图、原始 XML 排版或 ZIP 压缩字节相同。默认运行控制与富模型完整值分别验收。

XML 能表示的 Auto／递归结构不等于所有布局组合可用；含块格表格的 Auto 等既有布局限制继续适用。字体角色不会自动关联 ZIP 内的某个文件，重导出仍须显式加载并配置引擎角色。

## 页区共享来源

页区使用既有 header／footer／box，加可选 `source-id` 和 `source-ref` 表达同类来源共享。首次写完整来源，后续只写引用；每个 box 仍保存自己的 x／y／width，height 来自来源。默认／FIRST／EVEN 声明和整层清空保持，未声明 FIRST 与显式空对不混同。完整语法和静态引用边界见[页区指南](xml-page-regions-api.md#共享来源)。

相等来源在同类页区内只保存和绑定一次；所有源块 ID 与正文共同避让，实际放置与绘制仍分别计费。完整值包含已经解析的格式，不会在引用处重新应用主题。旧编译器不能读取新增属性，重开方需要支持该语法的版本。三轮模型／源码控制、共享编辑和实际图层输出见[阶段七十九](verification/phase-79-xml-region-saving.md)及 [ADR-0085](adr/0085-shared-xml-page-region-sources.md)。

## 不支持内容与动作策略

默认 `ProcessingPolicy.STRICT` 遇到不支持内容即失败。规则使用目标格式 `XML_TEMPLATE`、操作 `EXPORT`，通过 `.policy(...)` 配置共享 ERROR／WARN／IGNORE。必须选择当前功能真实支持的 treatment；要求 PRESERVE 而没有保留通道时仍失败。

| feature | 允许的 treatment | 实际损失 |
| --- | --- | --- |
| `NOTES` | `DISCARD` | `.notes(...)` 传入的脚注／尾注内容被移除 |
| `NOTE_MARKER` | `DISCARD` | 对应行内注释标记被移除 |
| `UNSUPPORTED_BLOCK` | `DISCARD` | 不在映射表内的块整体移除，包括未映射的 Caption／自定义运行时组件 |
| `TEXT_BLOCK_LINK` | `DISCARD` | 标题／列表正文的外链移除，文字与样式保留；现有 XML 在这些位置只接受文字／span |
| `IDENTIFIER` | `DEGRADE` | XML 无法表达的块／节／列表／目标 ID 稳定改为 `saved-N`，已有名称避让、引用同步改名 |
| `FONT_ROLE` | `DEGRADE` | XML 无法表达的字体角色清空，其他样式保持；重导出将使用默认字体组 |

例如，允许移除注释但保持其他功能严格：

```java
var policy = ProcessingPolicy.builder()
        .feature("NOTES",
                ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("NOTE_MARKER",
                ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
var prepared = XmlTemplateWriter.from(sectionedDocument).notes(notes).policy(policy).prepare();
```

WARN 返回 warnings 和实际 losses；IGNORE 只隐藏警告，losses 仍保留。诊断定位节、区域（如 HEADER_FIRST／FOREGROUND:0）、块、物理格路径和行内半开 UTF-16 范围。结构性 ID 损失定位到持有者；重复使用同一改名 ID 不重复产生重命名记录。注释在语义模型之外，调用方须明确传入，不会由正文推测。

## 统一转换与资源所有权

`XmlTemplateWriter.conversionTarget()` 是已有 `DocumentConversion` 的目标；`bundle.conversion()` 是无绑定数据的静态模板源：

```java
bundle.conversion()
        .edit(editor -> editor.replaceText("summary", "草稿", "已复核"))
        .prepare(XmlTemplateWriter.conversionTarget())
        .to(Path.of("edited-model.zip"));
```

转换目标保存模型和图片，不从源适配器／引擎搜集字体及许可。需要再次分发字体时，按首个例子显式传入。需要数据的模板必须先调用 `compile().renderSections(data)`，再使用 `DocumentConversion.from(document, notes)`；不得用空数据掩盖缺值。转换到 PDF／DOCX 等目标仍按对应适配器检查全部损失。

## 预算、取消和输出

Writer 默认最多 10,000 个语义块（正文及按同类去重的页区源）、100,000 个真实格、10,000 条 issue；`.limits(...)` 可配置。XML 源累计长度和图片／字体仍受 `.bundleLimits(...)` 控制，编译／绑定另受现有 [XML 预算](xml-template-api.md)限制。达到预算、取消、非法输入或损坏资源直接失败，WARN／IGNORE 不放宽它们。

`.execution(...)` 接受同步取消／超时／字节预算。准备和出版都有检查点；借用输出流不关闭或刷新，物理写失败可能已写出部分字节。路径出版用同目录临时文件和原子替换，无非原子回退；发布前失败保留旧目标并清理临时文件。父目录由调用方创建。

## 兼容性与验收

`DocumentTemplate` 新增 `literalText` record component，旧 4／5／6／7 参数构造器保留且默认插值行为不变；record component 数量、反射形状、equals／hashCode／toString 契约增加这一项，不能据旧构造器保留声称所有反射或序列化消费者完全兼容。现有 XML 默认仍插值，只有根显式声明才关闭文本插值；if／each／rows 的控制数据路径继续有效。

设计见 [ADR-0084](adr/0084-semantic-model-to-existing-xml.md)，可运行样例见 [XmlModelSavingExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlModelSavingExample.java)，独立结构、三轮控制与实际编辑输出见[阶段七十八验收](verification/phase-78-xml-model-saving.md)。标准输出内嵌恢复、任意复杂模型恢复和更广往返仍见 [GAP-24](capability-gaps.md#自生成文档的恢复与往返编辑)。
