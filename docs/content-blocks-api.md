# 源内容块与继承配置

`ContentOptions` 为每个源内容块声明字体角色、颜色、段落排版和解析方式；未声明的属性逐项继承全局或外层作用域。`ContentImporter` 在编写／模板绑定阶段把明确的文本、Markdown 或 HTML 源转换为既有语义块，再交给布局和输出后端。

此功能属于当前开发版，不包含在已发布的 `0.1.0-rc.3` 中。本批实际验证状态见[验收记录](verification/content-blocks.md)。混合 Markdown／HTML 与一次编译 PDF／原生 Word 数学的入口见[混合内容与跨格式公式](rich-content-api.md)。

## 模块与入口

| 入口 | 职责 |
| --- | --- |
| `export4j-model`：`ContentOptions`、`DocumentTemplate.Source`、`SourceContentParser` | 保存编写声明、绑定整字段源值及同步解析契约；不依赖 Markdown、HTML 或公式实现 |
| `export4j-adapter-web`：`ContentImporter` | 显式选择源格式，使用已有离线导入子集，生成最终语义模型 |
| `export4j-adapter-template`：`XmlTemplate` | 编译 XML 根默认值、`scope` 和 `content`，在绑定时调用显式装配的解析器 |
| 可选 `export4j-math`：`TexMath.processor()` | 提供内置的受限公式处理器；使用端无需自行维护识别和轮廓渲染代码 |

使用与当前开发制品一致的 BOM 版本，并按入口引入模块。公式关闭时无需引入 `export4j-math`；开启公式时显式配置 `.formulaProcessor(TexMath.processor())`。输出仍需在 `DocumentEngine` 中提供物理字体及具名字体角色，不扫描系统字体，也不合成粗斜体。

## Java：全局默认与单块覆盖

```java
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.math.TexMath;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;

var theme = DocumentTheme.builder()
        .fontRole("body").rgb(0x203040)
        .bodyFormat(p -> p.fontSize(12).lineHeight(28))
        .build();
var importer = ContentImporter.builder()
        .defaults(ContentOptions.fromTheme(theme))
        .defaults(ContentOptions.builder()
                .format(ContentOptions.Format.MARKDOWN)
                .formulas(true).tables(true).build())
        .formulaProcessor(TexMath.processor())
        .build();

var rich = importer.read("summary", "Result: $x^2$.\n\n| Value |\n|---|\n| 42 |")
        .options(ContentOptions.builder().rgb(0x224466).spaceAfter(8).build())
        .execute();
var literal = importer.read("source", "**Literal** <b>text</b> $x^2$")
        .options(ContentOptions.builder().format(ContentOptions.Format.TEXT)
                .formulas(false).tables(false).fontRole("").rgb(0).build())
        .execute();
```

`rich.document()` 和 `literal.document()` 都是既有 `Document`；`read(String)` 始终接收源文本，不是文件路径。请求独立，局部声明不改写 importer 或兄弟块的默认值。`defaults(...)`、`source(...)` 和模板编译保存完整解析快照，后续主题或 Builder 修改不追溯既有内容。

字体角色和 RGB 分开继承。空角色表示使用导出时显式提供的默认字体组，`0` 表示显式黑色；零间距、`false`、零旋转同样是有效覆盖。不能按默认数值推断“未设置”。在局部 `ContentOptions.Builder` 上调用 `inheritRgb()`、`inheritFontRole()` 或其他 `inheritXxx()`，会清除对应声明，下一次 `resolve(parent)` 时恢复父值。

`base.overlay(overrides)` 先合并两组声明，尚不解析继承；只有 `overrides` 中实际声明的属性替换 `base`。这使统一入口能够组合全局和请求配置，同时保留主题标题、单元格及词法作用域的默认值。

## 可配置属性

| 属性 | Java 声明 | XML 属性 |
| --- | --- | --- |
| 字体角色、文字颜色 | `fontRole`、`rgb` | `role`、`color="#224466"` |
| 字号、固定行高 | `fontSize`、`lineHeight` | `size`、`line-height` |
| 对齐、首行／续行缩进 | `alignment`、`firstLineIndent`、`subsequentLineIndent` | `align`、`first-indent`、`subsequent-indent` |
| 段前／段后间距 | `spaceBefore`、`spaceAfter` | `space-before`、`space-after` |
| 整段、与下一块保持及块前换页 | `keepTogether`、`keepWithNext`、`pageBreakBefore` | `keep-together`、`keep-with-next`、`page-break-before` |
| 分页前后最少行数 | `minLinesBeforeBreak`、`minLinesAfterBreak` | `min-lines-before`、`min-lines-after` |
| 禁则、旋转 | `lineBreakPolicy`、`rotation` | `line-break`、`rotation` |
| 单元格完整文字格式 | `cellFormat` | `cell-size`、`cell-line-height`、`cell-align`、`cell-line-break` |
| 表格完整格式 | `tableFormat` | `table-padding`、`table-draw-borders`、`table-allow-row-split`、`table-space-before`、`table-space-after` |
| 源格式 | `format` | `content-format` |
| 公式、表格解析 | `formulas`、`tables` | `formulas`、`tables` |
| Markdown 中的原始 HTML | `rawHtml` | `raw-html` |

Java 几何单位为 pt，旋转为度。`ParagraphAlignment` 为 `LEFT`、`CENTER`、`RIGHT`、`JUSTIFY`；`LineBreakPolicy` 为 `STANDARD` 或受限中文禁则 `CJK_STRICT`。只声明字号不会改写继承的固定行高；实际字体／公式放不下时沿既有布局路径明确失败。

`paragraphFormat(...)` 声明全部段落属性，`pagination(...)` 声明全部分页属性；`inheritParagraphFormat()` 和 `inheritPagination()` 分别恢复该组继承。`cellFormat`、`tableFormat` 是完整值覆盖，而不是通用逐属性 CSS。背景、边框和布局容器继续使用原有[表格](table-api.md)及[组件 API](components-api.md)，没有给任意语义段落增加背景／边框。

## 格式与解析开关

默认值为 `TEXT`、公式关闭、表格开启、Markdown 原始 HTML 解释关闭。表格开关只在所选语法支持表格时起作用，不把普通文本中的竖线自动识别成表格。

| 配置 | 行为 |
| --- | --- |
| `Format.INHERIT / TEXT / MARKDOWN / HTML` | 继承或明确选择一种语法，不按字符串外观猜格式；最终 `Resolved` 不允许残留 `INHERIT` |
| `Toggle.INHERIT / ENABLED / DISABLED` | 公式、表格和原始 HTML 分别继承、启用或关闭；Java 也提供 boolean 重载 |
| 公式关闭 | 保留识别到的分隔符源片段；Markdown 不把其中的反斜线、下划线再当作普通 Markdown 转换 |
| 表格关闭 | 对 Markdown／HTML 的表格源块保留原始源片段，保留其转义和换行；不再渲染其中的公式 |
| 公式开启 | 需要显式 `FormulaProcessor`，即使本次源没有公式也检查装配；不静默改回普通文字 |
| `rawHtml(true)` | 仅在 MARKDOWN 中启用既有离线 HTML 子集，保留 HTML 表格跨度；未知语义、属性及活动内容仍严格失败 |

XML 的 `content-format` 使用上述大写枚举；开关接受 `true`／`false`、`ENABLED`／`DISABLED` 或 `INHERIT`。省略属性表示继承。

公式只识别明确的 `$...$` 和独占行的 `$$...$$` 分隔符，不做 OCR 或数学语义猜测。代码、链接、图片目标、引用定义及 HTML 属性不参与公式配对；未开启 `rawHtml` 时，原始 HTML 也受保护。显式开启后，HTML 正文及表格文字可以包含公式，先隔离公式再解析 HTML，公式中的 `<`／`>` 不会被误当标签。公式使用其所在位置最终解析出的字号及颜色，包含表格单元格的覆盖值；正文角色不改变处理器自身的数学字体。

代码块映射为保留解析后空白和换行的文字；行内代码遵循 CommonMark 对换行及边界空格的规则，不解析内部公式。HTML `pre`／`code` 保留相应空白；此入口使用继承的字体角色，不自动要求 `code` 字体。代码语言及语法高亮不进入模型。标题、有序／无序列表与段落、单元格均保留混合行内图片和矢量，不再把它们压成替代文字。普通 `td` 首行不推断为表头；仅含表头的表格保留原行及表头外观，关闭重复表头，不添加虚构空行。

`ContentImporter` 固定采用 `ProcessingPolicy.STRICT`。未知语义、需要有损修复的 HTML、缺少资源或非法输入不会悄悄跳过。需要显式损失处理的通用文件导入，仍使用[Markdown 导入](markdown-import-api.md)或 [HTML 导入](html-import-api.md)原入口；此统一源块入口不放宽它们的支持子集。

## Java 模板：整个字段作为源

```java
import com.mirakyux.export4j.model.DocumentTemplate;
import java.util.Map;

var sourceOptions = ContentOptions.builder()
        .format(ContentOptions.Format.MARKDOWN).formulas(true).build()
        .resolve(ContentOptions.fromTheme(theme));
var template = DocumentTemplate.builder()
        .node(DocumentTemplate.Source.value("detail", "detail", sourceOptions, importer))
        .node(importer.source("footer", "Literal ${notInterpolated}",
                ContentOptions.builder().format(ContentOptions.Format.TEXT).formulas(false).build()))
        .build();
var document = template.render(Map.of("detail", "Result: $x^2$."));
```

`Source.value` 只取点路径指向的一个完整字符串，缺失或类型不符明确失败；不拼接 `${...}` 与富文本语法。`Source.literal`、`importer.source(...)` 的静态字符串和已取出的字段值都不再次执行模板插值。最终语义块、链接以及公式的逻辑阅读文字也不再插值。

## XML：根、作用域与单块

```java
import com.mirakyux.export4j.adapter.template.XmlTemplate;

var xml = XmlTemplate.builder()
        .theme(theme)
        .contentOptions(o -> o.formulas(true))
        .contentParser(importer)
        .build();
var template = xml.compile("""
        <document version="1" content-format="MARKDOWN" role="body" color="#203040"
                  size="12" line-height="28">
          <content id="intro" source="intro"/>
          <scope content-format="HTML" formulas="false" color="#224466">
            <content id="html"><![CDATA[<p>Literal ${name} &amp; HTML text.</p>]]></content>
          </scope>
          <content id="plain" content-format="TEXT" formulas="false" tables="false"><![CDATA[**Literal** $x^2$]]></content>
          <p id="legacy">${name}</p>
        </document>
        """);
var document = template.render(Map.of("intro", "Result: $x^2$.", "name", "Reader"));
```

优先级为主题／编译器默认值 → 根 `document` → 外到内的 `scope` → 当前块的显式属性。`scope` 只改变其词法子树，离开后恢复；它不是最终布局容器。完整命名行内样式及显式局部属性仍遵循既有契约。`p`、`heading`、列表、`caption` 和 `cross-reference` 可以继承外观／段落默认值；解析语法开关仅用于 `content`，不把旧 `p`／`span` 的插值文字自动当作 Markdown、HTML 或公式。

`<content source="field.path"/>` 必须为空元素。静态源可使用文本或 CDATA，但不接受混入 `span` 等 XML 子元素；CDATA 中的 `${name}` 原样传入所选解析器。未装配 `contentParser` 时只提供普通文本源，不接受 Markdown、HTML 或公式开启配置。

`content`／`scope` 是正文编写节点，不作为页眉／页脚的直接子节点；页区继续使用既有的段落和表格声明。需要富文本页区时，先在 Java 中编译为受支持的语义块，再组装页区。

## 正文单元格中的源

下列片段放在 `document` 中，复用相同解析器、词法默认值与循环数据范围：

```xml
<rows id="details" items="items" as="item" role="body" cell-size="11">
  <columns><column weight="1"/></columns>
  <row>
    <cell color="#000000">
      <scope space-after="0">
        <content id="detail" source="item.text" content-format="MARKDOWN"/>
      </scope>
    </cell>
  </row>
</rows>
```

正文格可包含源节点、作用域、条件及循环，展开成已有块单元格；列／行跨度保留，循环命名空间区分每次产物。**重复表头中的块式源内容不支持**，同一动态源格也不能混用 `style-if` 规则。展开后的段落／内表仍受[块单元格](block-cells-api.md)已有深度、分页及容器限制约束，不提供任意递归布局。

`Caption` 和 `CrossReference` 新增显式 `TextAppearance`，其原构造器保留默认角色／黑色。解析和最终编号使用同一外观；它们不是新的字符串语法解析入口。

## 资源、预算与生命周期

图片／矢量必须用 `ContentImporter.Builder.image(...)`／`vector(...)` 按精确源键登记，图片字节和 pt 尺寸由调用方提供。HTML 字体族用 `fontFamily(family, regular, bold, italic, boldItalic)` 映射到显式角色；粗体、斜体和组合角色也必须在输出引擎登记。链接按既有被动外链规则验证，不据此获取网络或本地文件。

每次模板 render 对同一解析器身份仅打开一个同步 `SourceContentParser.Session`，跨源块、循环、分节和动态单元格累计输入、语法树／工作量、表格、公式及矢量预算。`ImportLimits`、`maxFormulas`、`maxFormulaUtf16` 和 `maxVectorSegments` 不因进入子块而重置；每次解析还受模板传入的 `Remaining` 限制。输入及工作量在第三方解析边界检查，AST 节点按实际遍历累计，不按文字长度虚构节点数；失败的遍历也不退还已消耗的额度。混合内容诊断映射回原 Markdown 的 UTF-16 范围。有限预算及合作式检查不是强制内存沙箱。

成功、失败和取消均关闭 session；已有主异常保留，清理失败不会替换主异常。最终 `Document` 不包含 `Source` 或可延迟解析的字符串节点。已构建 `Paragraph`／`Table`、完整行内样式和 `Document.Builder.add(Block)` 不会被重新解析或按默认数值覆写。设计决定见 [ADR-0115](adr/0115-source-content-options.md)。
