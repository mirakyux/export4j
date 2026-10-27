# 声明式 XML 模板

模板源码可直接编辑；需要一起保存主源、片段、图片和字体时，使用普通 ZIP 的 [XmlTemplateBundle](xml-template-bundle-api.md)。重开后仍编译为同一 `DocumentTemplate`，不改变已有 XML 语法或绑定方式。

已有 Java 语义文档可用 [XmlTemplateWriter](xml-model-saving-api.md) 按支持子集保存为同一 XML／ZIP，再重开编辑；无法映射的内容使用共享动作与损失策略。

主源支持固定 header/footer、[首页／奇偶变体](page-variants-api.md)与独立主题，放在可选 page 后、正文前。使用 `DocumentTemplate.renderWithPageRegions` 和 `DocumentEngine.export(PageDocument)` 保留页区及最终页码；旧 render 遇到页区会明确拒绝。图片／语义表格和最终字段均保留；分节使用 renderSections，包含各节纸张／页码、页区继承／覆盖和清空。语法、限制及完整调用见[页区指南](xml-page-regions-api.md)、[分节指南](xml-sections-api.md)和[固定页面图层](page-overlays-api.md)。

正文／列表、六级标题和单元格可分别设置默认字体角色与颜色，局部 `role`／`color` 按存在性覆盖，链接与四类字段继承所在范围。使用 `.bodyText(...)`／`.headingText(...)`／`.cellText(...)`，完整规则见[文字默认值指南](xml-text-defaults-api.md)。字体资源仍由 Java 显式注册，模板不加载系统或网络字体。

主源首个可选 `<page>` 可声明 A4／LETTER、自定义宽高、横竖方向和四边边距；页面长度支持 pt／mm／cm／in，未声明项沿用 Java `pageSettings`。位置、覆盖顺序及 DOCX twip 边界见[纸张与边距指南](xml-page-settings-api.md)。

正文、六级标题、格／表格式及静态命名样式可通过 `XmlTemplate.Builder.theme(DocumentTheme)` 配置；`p`／`span` 的 `style` 规则及显式覆盖见 [XML 主题指南](xml-themes-api.md)，已完成[阶段三十一验收](verification/phase-31-xml-themes.md)。

重复报告区块可通过 `.fragment(name, source)` 显式登记，再用静态 `<include ref="panel" prefix="first"/>` 复用。支持最多 16 层非循环片段引用，父子前缀组合；名称空间、来源和预算见[离线片段指南](xml-includes-api.md)。

版本 1 模板把报告结构写在 XML 中，Java 负责数据、资源与输出。解析器属于 `export4j-adapter-template`，使用根包 `com.mirakyux.export4j.adapter.template`。它编译为现有 DocumentTemplate，不另行决定换行和分页。完整例子见[模板文件](../examples/src/main/resources/templates/report.xml)、[调用代码](../examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java)及[验收](verification/phase-21-xml-templates.md)。

## 最短调用

~~~java
var template = XmlTemplate.builder().build().compile("""
        <document version="1" title="${title}">
          <heading level="1">${title}</heading>
          <each items="items" as="item">
            <p>${item.name}：${item.result}</p>
          </each>
        </document>
        """);
var document = template.render(Map.of("title", "巡检报告", "items", List.of(
        Map.of("name", "东门仓", "result", "已复核"))));
engine.export(document).backend(new PdfBackend()).to(outputPath);
~~~

上例假设已显式配置 engine 的中文字体和 outputPath。加载文件时由调用方打开 Reader，再传给 `compile(reader)`；编译器不关闭借用 Reader，也没有文件路径或 URL 加载入口。日期、金额和本地化格式在传入数据前准备。

## 结构语法

所有元素均无命名空间，根为 `<document version="1">`，可选 `title`、`author`、`literal-text`。结构之间允许空白和注释；正文、span 和单元格内的空白原样保留，因此正文不要为美化 XML 而额外缩进换行。

| 元素 | 内容和属性 |
| --- | --- |
| `page` | 仅主根首个可选空节点；静态纸张／宽高／方向／边距，不生成正文块，详见页面指南 |
| `p` | 普通文字、span、image、link 和四类行内字段；普通段落可自然跨页 |
| `heading` | 必填 `level="1"`～`"6"`；仅文字、span |
| `bullet` | 1～9 级无序列表；可选 level（默认 1）、marker（默认 `•`）；仅文字、span |
| `numbered` | 1～9级十进制列表；必填静态 list-id，可选level（默认1）、restart；仅文字、span |
| `page-break` | 空元素；显式换页 |
| `if` | 必填 `test="flag"`；先且仅一个 then，随后可选一个 else；分支内为块 |
| `each` | 必填 `items="items"`、`as="item"`；内部为块，可嵌套 |
| `include` | 必填静态 ref／prefix；主模板及片段合法块列表选择已登记片段，拒绝循环、行内和格内位置 |
| `table` | columns、零至多个 header、一个以上 row，顺序固定 |
| `rows` | 同 table，但恰好一个模板 row；必填 items、as；展开为空集合时失败 |

`if` 的值须为布尔值；each／rows 的值须为 List。Map／List 仅作结构，不可直接插入文字。数据值仅接受非 null 字符串、布尔值、标准有限数值、Map 和 List。调用方自定义容器及 Reader 是可信代码，不受脚本沙箱保护。大数只接受精确 BigInteger／BigDecimal 类，拒绝子类。

`${customer.name}` 是唯一插值语法。路径使用 ASCII 标识符及点，最多 256 个 UTF-16 单元。条件／循环属性直接写路径，不加 `${}`。所有分支的语法都会编译检查，只有选中分支执行数据查找。数据中的 `<p>`、`${x}` 等内容作为普通文本插入，不再次解析为 XML 或表达式。

根显式 `literal-text="true"` 时，标题／作者、正文、替代文字和外链按原文处理，`${...}` 及未闭合 `${` 不插值；默认 false 保持现有行为。if／each／rows 的控制数据路径继续读取所传数据。静态字段目标仍须满足 ID 语法，资源名称／样式属性仍静态校验；该模式不关闭安全、预算或类型检查。保存已有模型时 writer 自动启用它，编写动态模板时通常使用默认模式。

p、heading、bullet、numbered、table、rows、page-break 可选 id。显式 ID 匹配 `[A-Za-z_][A-Za-z_0-9-]{0,127}`，不能插值或含 `@`，在两分支和循环原型之间也须唯一。省略时生成 `xml-N` 并避开全部显式 ID；循环渲染附加 `@0` 等索引。

## 样式、表格与图片

p、heading、bullet、numbered 支持 `size`、`line-height`、`align`、`keep-together`、`space-before`、`space-after`。长度均为 point；align 为 LEFT／CENTER／RIGHT／JUSTIFY；布尔值只接受 true／false。bodyFormat 配置段落／列表默认值，标题使用既有 Heading 默认格式。显式设置 size 且不设置 line-height 时采用字号的 1.5 倍；否则保留对应默认行距。

段落／标题／列表还支持 `first-indent`、`subsequent-indent`（非负 pt）、`line-break`（STANDARD／CJK_STRICT）、`keep-with-next`、`page-break-before`、`min-lines-before`／`min-lines-after`（正整数）。未声明项沿用当前完整格式；这些属性映射到既有 ParagraphFormat／PaginationPolicy，布局能力边界不变。

bullet／numbered 的 `marker-role`、`marker-size`、`marker-color`、`marker-shift`、`marker-underline`、`marker-strike` 可分别覆盖标记的完整样式，未声明项沿用所在块默认文字外观，正文 span 不影响标记。marker-shift 单位为 pt，正值下移；自定义 bullet 须非空白、无控制字符、最多 8 个 Unicode 码点。

`<span role="bold" color="#224488" size="12" underline="true" strike="false">文字</span>` 只容纳文字。属性均可省略；默认继承当前块字号，颜色为黑色、角色为空、不加装饰。role 只选择调用方字体组里的真实角色，不加载或合成字体。

span 和四类字段另支持 `baseline-shift`，单位为 pt，正值下移；image 也支持该属性，默认 0。固定行框必须容纳字号／图片及位移，不自动缩小。

~~~xml
<rows items="records" as="record" row-split="true">
  <columns><column width="72"/><column weight="1"/></columns>
  <header><cell>编号</cell><cell>说明</cell></header>
  <row><cell>${record.code}</cell><cell>${record.notes}</cell></row>
</rows>
~~~

column 恰好指定 width（正且有限的固定 pt）、weight（正且有限的比例），或成对 min-width／max-width（有界 Auto 的 pt 下／上限）；三种形式互斥，边界调用既有 ColumnWidth.Auto 校验。Auto 不放宽含块格表格的布局限制。每个 header／row 经 column-span／row-span 解析后必须完整覆盖网格，真实 cell 只在锚点行声明。cell 支持 size、line-height、align、line-break、column-span、row-span，以及文字、span、image、link 和四类行内字段。table／rows 可显式指定 padding-top／right／bottom／left、space-before／after（非负 pt），未声明项沿用主题值。普通行内 table／rows 可选 `row-split="true"`，默认 false；它调用既有语义表格分页，表头逐页重复，显式拆行按完整文字行续页，纵向／递归边界见对应指南。

图片先由调用方有界解码为 ImageResource，再通过 `.image("logo", resource)` 注册。模板只写 `<image ref="logo" width="12" height="12" alt="标记"/>`。ref 不可插值，名字使用 ASCII 标识符。width、height 为正 point；alt 可插值。图片仅用于 p／cell，固定行框须容纳图像，否则由布局明确失败。编译器不解码图片、不扫描字体、不访问文件和网络。

## 多段单元格与原子内表

`cell` 支持直接有序 `p`／静态 `table`／`p`，生成既有 `TableCell.blocks`，初始切面见[阶段二十八验收](verification/phase-28-xml-block-cells.md)，后续已扩展到含根表最多 16 层的[递归表格](block-cells-api.md)。完整可读示例、默认格式和复制来源见[XML 块格指南](xml-block-cells-api.md)。

~~~xml
<table id="report">
  <columns><column weight="1"/></columns>
  <row>
    <cell size="10" line-height="16">
      <p id="summary">${summary}</p>
      <table id="details">
        <columns><column weight="1"/></columns>
        <row><cell>${detail}</cell></row>
      </table>
      <p id="conclusion" size="11" line-height="18">明细见第 <page-reference target="details" width="24"/> 页。</p>
    </cell>
  </row>
</table>
~~~

上例须放在 `document` 或合法外层控制节点内。直接格内 `p` 从所在格继承字号、行距、对齐和断行策略，段间距默认为 0；`p` 显式属性优先。内表单元格重新使用编译器 `cellFormat`，不继承外格局部文字设置。

直接出现任一 `p`／`table` 即进入块模式，只允许这两类节点及结构空白。行内文字、span、图片、字段和 link 须放进 `p`，不能与块平级混用。格内不接受 if／each／rows、标题、列表或换页；外层已有 each／rows 可以深复制完整子树，并重定位同一词法复制范围内的静态字段目标。

默认块格及父连通行组整体移页／移区；显式 `row-split="true"` 支持递归段落／内表及纵向 owner 续排，各层开关独立。重复父表头中的块格仍拒绝，普通行内父表头保持。所有条件分支均验证几何、深度和内容分支。语义表深最多 16 层，见[块格 API](block-cells-api.md)。

## 最终字段、编号与外链

四种字段都是空元素，放在 p／cell 中；每个字段占一个 U+FFFC 原文位置，并须显式预留正且有限的 width（point，不超过 Float.MAX_VALUE）。字段也支持 span 的 role、color、size、underline、strike 属性，默认采用所在块字号；不支持值、任意指令、自动缩小、注释标记或缓存文字。

| 元素 | 最终值与额外属性 |
| --- | --- |
| `page-number` | 本次呈现的显示页码；不接受 target |
| `page-count` | 最终物理总页数，含目录前置页；不接受 target |
| `page-reference` | 必填 target，解析目标块所在的显示页码；支持数据表达式 |
| `number-reference` | 必填 target，解析目标 NumberedItem／Caption 的编号标签；支持数据表达式 |

静态 target 使用与显式 ID 相同的语法；`${record.target}` 等动态 target 在编译时检查表达式，绑定后视为最终完整 ID，不自动添加 include 前缀或 each 后缀。编译检查语法、宽度和样式，包括未选中的 if 分支；目标是否存在、是否有编号在最终文档布局时检查。循环内静态字段引用同一词法复制作用域声明的目标时，重定位到当前副本，例如 `loop` 对应 `loop@0`；内层循环可引用当前外层副本，循环外的静态目标保持原 ID。条件分支也参与声明收集，但未选中的目标仍不存在，最终布局明确失败；循环外引用循环原型 ID 不会猜测某次迭代。值放不进预留框时由既有布局诊断明确失败。

`numbered` 映射到现有 NumberedItem。list-id 必填，采用静态 ID 语法；同 list-id 的项目在全文档顺序中继续编号。level 为静态整数1～9，省略为1。restart 默认为0（继续），正值重设当前级，更新后清空全部更深级；只接受非负int。实际展开后的编号必须有完整父级，缺级和递增溢出在既有语义布局／DOCX导出阶段失败，编译或render不提前推测动态父计数。完整点分引用、循环／片段列表身份及九级边界见[多级编号指南](xml-numbering-api.md)。字段、图片及 link 包装不用于 numbered／heading／bullet；它们的 span 仍保留真实文字样式。

~~~xml
<numbered id="check" list-id="checks" restart="3">复核记录</numbered>
<p>本页 <page-number width="24"/>／<page-count width="24"/>，参见第 <page-reference target="check" width="24"/> 页、第 <number-reference target="check" width="24"/> 项。</p>
<p><link href="https://example.com/report?a=1&amp;b=2">${label}<span role="bold" underline="true">详情</span><image ref="logo" width="12" height="12" alt="详情入口"/></link></p>
~~~

`link` 仅用于 p／cell，必填 href，内容为普通文字、span 或已注册 image。链接内图片仍通过资源白名单选取；href 从不成为图片来源。href 可为静态地址或 `${record.url}` 等纯数据表达式，在当前词法范围绑定，每个副本独立解析。最终地址复用 ExternalDestination：只接受 HTTP、HTTPS、mailto，检查长度、控制字符、主机及用户信息。非法动态地址以脱敏 `BindingException.Code.INVALID_VALUE` 失败，不携带含原地址的 cause；缺值、类型、预算和取消仍按各自错误处理。没有隐式 URL 编码或外联。XML 中的 `&` 写为 `&amp;`。不支持嵌套链接、链接内字段、空 link 或点击脚本。

编译与数据绑定只保存被动链接，不访问目的地址。字体、UTF-16 原文位置、最终链接矩形和字段值仍由既有模型及布局处理。字段替换不会改变行宽、换行或分页；表头逐次呈现的页码仍遵守语义表格契约。DOCX 重复表头 PAGE 不能保留逐次呈现语义，仍须明确拒绝；静态 SVG／PNG 后端仍拒绝非空交互数据，不能无提示丢弃链接。

## 预算与错误

`XmlTemplate.Limits(maxInputCharacters, maxElements, maxDepth, maxTextCodePoints)` 默认分别为 1,000,000、20,000、32、1,000,000。输入为主源加全部登记源的 UTF-16 总量；解析累计元素／文字与逐次展开累计元素／文字分别使用同一额度，重复引用不能免除展开成本。元素包含根和 include 工作，text 包括结构空白，不计属性；属性由输入总长度约束。根深度为 1，最终深度最大可配置 64，输入树另有 65 层硬上限。Reader 最多读取扣除全部登记源后的余额加 1 个单元；未登记片段时保持原长度契约。

`.bindingLimits(DocumentTemplate.Limits)` 独立控制快照／展开访问次数、块、行、码点和深度。文字累计计入 Map 键、数据字符串及全部生成文字，包括静态链接地址、图片 alt 和字段 target；模板 literal 和替换值在追加前计费。每次展开的字段／文字／图片均计入行内访问工作，链接包装不形成第二套绑定器；单段图片与字段还分别受既有模型的 1,024 个上限约束。循环、单元格、路径与文字扫描都有执行检查点，可放在 `ExecutionContext.execute(options, ...)` 中使用取消／超时。编译和渲染本身不写文件。

| 阶段 | 诊断 |
| --- | --- |
| XML 编译 | TemplateException.code／line／column 及可选 fragmentName；位置一基，message 不含登记名、源文本、值或资源路径 |
| 数据绑定 | DocumentTemplate.BindingException.code／path；路径可能为安全截断定位，不提供 XML 行列映射 |
| 布局与导出 | 既有布局、字体、资源、后端及执行诊断 |

未知元素／属性、错序结构、重复 ID、非法尺寸、缺失资源和不合法表达式立即失败。DTD、自定义或外部实体、命名空间及声明、处理指令和脚本无条件拒绝；允许 XML 预定义／数值实体。include 不加载路径或 URL，只接受显式登记的字符串。没有静默降级或返回部分结果的模式。

展开后的内容仍须通过语义模型校验。例如 image 的 alt 插值后超过 4,096 个 UTF-16 单元或含控制字符时，由既有 InlineImage 抛出 IllegalArgumentException；它不属于表达式错误，也没有 XML 行列映射。

## 支持边界

cell 可用固定正整数 column-span／row-span 声明语义矩形合并；静态表格按二维网格验证，空 header／row 仅在被上行完全覆盖时合法。动态单行原型支持列合并，首批拒绝纵向跨度，避免循环实例跨数据行占用。见[列合并指南](column-spans-api.md)与[跨行指南](row-spans-api.md)。这是一种自主定义的声明式子集。Markdown 使用独立入口，HTML/CSS、注释及组件网格不属于 XML 子集。不承诺任意 XML／Word 输入无损、PDF 与 Word 同分页、流式布局或可访问性标准合规。DOCX／SVG 输出依旧遵守各自支持矩阵。

运行 `:examples:xmlTemplates` 生成原有 PDF、DOCX 和 SVG；独立检查使用 `scripts/verify_xml_templates.py`。新字段／外链按上述后端边界另外验收，不能由旧样例推断通过。本模块与自研样例采用 Apache-2.0，仅使用 JDK XML 和既有模型，不新增第三方运行依赖。设计依据见 [ADR-0025](adr/0025-safe-declarative-templates.md)及 [ADR-0029](adr/0029-xml-fields-and-links.md)。

字段与图文外链的完整例子见 [references.xml](../examples/src/main/resources/templates/references.xml)、[原生 Word 字段模板](../examples/src/main/resources/templates/references-word.xml)及 [XmlReferencesExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlReferencesExample.java)。运行 `:examples:xmlReferences` 与 `python scripts/verify_xml_references.py`，检查目录偏移、四次实际表头值、静态编号目标、被动链接矩形及原生 OOXML；证据见[阶段二十二](verification/phase-22-text-and-adapters.md)。

页区还支持同类静态共享来源 `source-id`／`source-ref`，用于保留跨节、变体和多位置复用关系；详见[共享来源](xml-page-regions-api.md#共享来源)及[模型保存](xml-model-saving-api.md)。
