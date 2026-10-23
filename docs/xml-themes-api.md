# XML 主题与静态命名样式 API

`XmlTemplate.Builder.theme(DocumentTheme)` 为离线 XML 模板配置正文、六级标题、语义表格及两类命名样式。XML 保留报告结构与数据表达式，Java 提供完整主题和显式资源。编译时解析样式名称，绑定继续使用既有 `DocumentTemplate`；布局阶段不查主题、不执行 CSS。

主题值与名称规则见[主题 API](themes-api.md)，现有语法、预算和资源白名单见[XML 模板 API](xml-template-api.md)。设计范围与拒绝依据见[XML 主题设计](adr/0038-xml-theme-styles.md)。

全局字体角色与颜色、分域覆盖及 XML TextDefaults 优先级见[主题 API](themes-api.md#字体角色与颜色级联)。

## 同一结构，两套主题

~~~java
var theme = DocumentTheme.builder()
        .bodyFormat(value -> value.fontSize(11).lineHeight(20).spaceAfter(4))
        .headingFormat(1, value -> value.fontSize(18).lineHeight(28))
        .cellFormat(value -> value.fontSize(10).lineHeight(18))
        .tableFormat(value -> value.padding(4).spaceAfter(6))
        .paragraphStyle("report-note", value -> value.fontSize(9).lineHeight(18)
                .firstLineIndent(0).subsequentLineIndent(0).spaceAfter(0))
        .inlineStyle("emphasis", value -> value.fontRole("bold").rgb(0x224488).underline(true))
        .build();

var compiler = XmlTemplate.builder().theme(theme).pageSettings(pageSettings).build();
var template = compiler.compile(xmlSource);
var document = template.render(Map.of("title", "季度复核", "result", "通过"));
~~~

~~~xml
<document version="1">
  <heading level="1">${title}</heading>
  <p style="report-note">数据来自合成样例。</p>
  <p>结论：<span style="emphasis">${result}</span></p>
  <table row-split="false">
    <columns><column weight="1"/></columns>
    <row><cell><p style="report-note">已完成复核。</p></cell></row>
  </table>
</document>
~~~

换用另一份 `DocumentTheme` 重新编译相同 XML，即可使用另一套排版值。`bold` 角色须在 `DocumentEngine` 显式注册真实粗体字体；编译器不持有字体，不隐式加载资源。

## 配置顺序与快照

`.theme(T)` 一次替换正文、格、六级标题、表格式和两类名称映射。后续 XML 配置保持各自独立的范围：

| 配置 | 实际影响 |
| --- | --- |
| `.theme(T).bodyFormat(A)` | 仅正文和列表改为 A；主题格、标题、表格式及名称保持 |
| `.theme(T).cellFormat(B)` | 仅格默认值改为 B；正文和其余主题值保持 |
| `.bodyFormat(A).cellFormat(B).theme(T)` | 整组替换为 T |
| `.theme(T1).theme(T2)` | 整组替换为 T2，不合并名称映射 |

这与 Java 编写入口的便利格派生范围有意不同。即使主题格最初由主题正文派生，传给 XML 时也已是完整快照；XML 的后续正文配置不重新派生格。

`build()` 固定编译器配置，`compile()` 固定完整模型原型。后续更改外层 Builder 或使用另一套主题不改已编译原型；`render()` 只绑定数据，不重新查找名称。

## p 与 span 的覆盖规则

`style` 仅在 `p` 和 `span` 上合法，包括现有合法块格与链接内的位置。名称区分大小写，匹配 `[A-Za-z_][A-Za-z_0-9-]{0,63}`；段落和行内映射独立，不跨类型查找。未知、空白或非法名称报 `INVALID_VALUE`，含 `${...}` 的动态名称报 `INVALID_EXPRESSION`。编译诊断使用一基 XML 行列并隐藏名称值与正文。

`p` 从上下文默认值开始；有名称时选择完整 `ParagraphFormat`，然后覆盖显式 XML 属性。因此 `<p style="report-note" size="12">` 没有 `line-height` 时仍按既有规则采用 18 pt 行高。没有 `size` 时保留命名样式的固定行高。零间距、对齐和 `keep-together="false"` 等显式值也会覆盖基底。

块格内无名称的 `p` 继续使用实际格的字号、行高、对齐和断行规则，并使用零段间距。有名称的 `p` 保留完整命名缩进、间距及分页策略，格位置不会自动清零它们。格内段落仍不支持 `pageBreakBefore` 或 `keepWithNext`；选择包含这些策略的命名格式会明确失败。应定义合法的格内样式，或在既有可覆盖属性范围内显式表达意图，不把整页分页策略放进格。

`span` 从完整命名 `InlineStyle` 开始，现有 `role`、`color`、`size`、`underline` 和 `strike` 属性最后逐项覆盖。显式 `role=""`、`color="#000000"`、`underline="false"` 和 `strike="false"` 均有明确含义。没有对应 XML 属性的 `baselineShift` 完整保留。普通同级文字保持所在段／格的默认字号、黑色和默认角色，不受前一个 span 影响。

例如，11 pt 的命名强调放进 19 pt 标题仍为 11 pt，需要变大时显式写 `size`。固定行框须同时容纳这些运行，空间不足不会自动放大行高或缩小文字。未知角色、缺字策略和原文 UTF-16 诊断仍由既有布局路径处理。

所有名称在编译阶段检查，包括未选择的 `if` 分支、运行期零次 `each`、动态 `rows` 的表头／行原型及原子内表。不能用数据分支隐藏非法名称。`heading`、`bullet`、`numbered`、`cell`、`table`、`rows`、字段、`link` 和根元素仍不接受 `style`，嵌套 span 仍拒绝。

## 标题、表格与原子内表

六级标题取主题的对应完整格式，再应用现有 XML 属性；未配置的级别沿用默认标题。标题级别、与后文相邻等策略保留。

静态 `table` 与动态 `rows` 都从主题 `TableFormat` 开始，包括内边距、前后间距和拆行开关。显式 `row-split` 只覆盖当前表。内表重新采用编译器的主题格／表默认值，不继承外格的局部字号或外表的拆行覆盖。

两层表格、原子块格及其他既有限制保持。如果主题打开拆行，含块格的父表及任何内表须分别显式写 `row-split="false"`；缺少覆盖仍在编译时失败。此能力不增加嵌套续排或合并拆行规则。

## 输出与独立复核

[XmlThemeExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlThemeExample.java)将同一个可读 XML 报告分别编译为蓝色、紧凑两套主题。每套只生成一页，覆盖六级标题、正文缩进、命名段落、显式 12 pt／18 pt 覆盖、具名运行的真实粗体／颜色／下划线／基线偏移、空角色与黑色覆盖、块格命名间距、原子内表和动态数据行。静态报告输出 PDF、SVG 和标准 DOCX，并严格比较 DOCX 往返后的完整模型值。原生读取会省略标题、编号项和行内格中单个无链接默认运行；样例只归一化这项等价表示，仍逐值核对完整格式、ID、富文本和层级。

另一页交互小样保留命名 span 内的文字链接、离线图片链接及最终页码／总页数／目标页码／目标编号。PDF 保留链接矩形和目标，DOCX 使用原生字段、关系与嵌入媒体。SVG 在写入前拒绝交互数据；不会为了输出静态图而静默删除链接。

~~~powershell
.\gradlew.bat :examples:xmlThemes
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_themes.py
~~~

独立脚本直接读取最终 PDF 实际字号、颜色、行距、缩进、真实粗体字体、装饰及根／内表坐标，核对 SVG 原文／颜色／轮廓边界，以及标准 OOXML 标题级别、完整段落和运行属性、嵌套表、动态行、字段与链接关系。期望值在脚本中独立声明，不从 Java 样式或几何清单导入。它还检查隔离 Maven 调用方；默认读取成功目录指针，可用 `--consumer-build-directory` 指定本次目录。全部四页由 Poppler 与 PDFium 独立渲染，并须逐页目视复查，不更新视觉基线。

HTML／CSS、XML 内主题定义、动态名称、完整 CSS 级联、字段主题、表头专用主题、远程资源和一般 Word 文档无损互转仍不在本能力范围。
