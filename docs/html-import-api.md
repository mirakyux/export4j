# 离线 HTML／CSS 导入

`export4j-adapter-web` 的 `HtmlImporter` 默认将离线 HTML／CSS 子集转换为可[语义编辑](semantic-editing-api.md)的 `Document`。显式 `.layoutMode(true)` 可将盒、Flex、Grid 和定位编译为运行时布局组件。两种模式都不执行脚本、不访问文件／URL，分页由 Export4j 引擎完成。

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;

var policy = ProcessingPolicy.builder()
        .feature("BLOCK_QUOTE", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var prepared = HtmlImporter.builder().policy(policy).build()
        .read("<h1>Report</h1><p id='status'>Old</p><blockquote><p>Quote</p></blockquote>")
        .prepare();
var predicted = prepared.preview();
var imported = prepared.execute();
var edited = DocumentEditor.edit(imported.document())
        .replaceText("status", "Old", "Ready").execute();
var actualLosses = imported.report().losses();
```

输入为不可变 String。Builder 构建时快照；Request 的 `.execution(ExecutionOptions)` 控制同步执行，`prepare()`／`execute()` 声明 `IOException`。PreparedImport 绑定确切输入、配置和候选，预检不代表文件写出成功。`${name}` 仅为普通文字。

## 元素与资源

| 输入 | 映射与限制 |
| --- | --- |
| p、h1～h6 | 段落／标题；保留可用块 ID、解码实体、折叠普通空白、保留 br 与不换行空格 |
| span、b／strong、i／em、u、s／del／strike | 完整行内样式及组合字体角色，不合成字体 |
| a | 被动 HTTP／HTTPS／mailto；相对链接需要显式 HTTP(S) base，内部锚点另报目标损失 |
| img | 按精确 src 从 `.image(src, InlineImage)` 获取；源 alt 保留，整数 width／height 按 CSS px 换算，单一尺寸按登记资源比例缩放 |
| ol／ul／li | 最多九层，正整数起始编号及混合嵌套；有序子级使用模型完整点分编号。复杂多块条目需明确降级；无法支持的结构失败 |
| Export4j 显式列表标记 | 首个行内元素为 `span.marker[data-export4j-marker="1"][data-export4j-level]` 时，验证纯文字标记与 1～9 层级。一级简单条目保留标记、编号重启及独立样式，标记不进入正文；复杂标记按 `LIST_MARKER` 显式降级为含原标记的普通段落，或 DISCARD 条目正文。普通 `class="marker"` 不触发识别；非法版本、空标记、隐藏标记或嵌套标记元素拒绝 |
| table／thead／tbody／tfoot／tr／td／th | 正整数 colspan／rowspan、真实锚点及完整矩形覆盖；首个 thead 或连续全 th 前缀成为重复表头；至少一行正文 |
| colgroup／col | 只接受行之前的有限列声明与正整数 span；`TABLE_COLUMNS` 显式降级／丢弃列组几何，实际列宽采用等份。CSS width／calc 等仍按 CSS 子集报损；声明超过实际格网、非法 span 或隐藏列结构失败 |
| 重复表头中的单个 p | `TABLE_HEADER_PARAGRAPH` 显式降级为行内内容，报告段落几何及标识丢失；显式 DISCARD 清除该内容。多块／内表表头仍拒绝 |
| 格内 p／table | 顺序块格，根表计第一层，最多 16 层；混入未包裹文字或其他结构失败，不扁平化隐藏内容 |
| 格内 div.table-scroll | 只接受准确 class、单个直接 table 子元素和空白直接文字；`TABLE_CELL_CONTAINER` + DEGRADE 去除容器几何，保持 p／table／p 顺序并继续累计表深。DISCARD 去除整张内表；格内无其他块时保留空行内格。隐藏、其他 class、混入文字或多子元素拒绝 |
| div 及文档分组容器 | 按源码顺序进入正文；容器锚点和无法映射的盒几何另报损失 |
| blockquote、pre、code、其他元素 | 默认拒绝未建模语义；明确降级可保留缩进／预格式文字或平铺子内容，明确丢弃可去对应内容 |

未知属性默认报损，不执行事件或获取资源。标准简单 HTML doctype 和 UTF-8 charset 可接受；其他 head 元数据不自动覆盖调用方提供的 DocumentMetadata。重复 ID、畸形表格、非法 Unicode／NUL 始终失败。

段落／标题／表格／li 的源 ID 作为块 ID；其他锚点不伪装成模型目标。自动 ID 使用 `html-N`，避开全部源 ID。源位置为原始输入半开 UTF-16；表格损失包含物理行列及嵌套格路径。诊断不回显正文或资源路径。

## CSS 子集

支持 style 元素及内联 style。选择器支持元素、`*`、`.class`、`#id`、同元素组合、后代、直接子、相邻／后续兄弟及逗号列表；属性支持存在、`=`、`~=`、`|=`、`^=`、`$=`、`*=`；静态伪类支持 first／last／only-child、root、empty，以及 nth-child／nth-last-child／nth-of-type／nth-last-of-type 的 odd／even／an+b。`:is()`／`:not()`／`:where()` 接受最多 16 个非嵌套同元素组合，包括属性；不接受关系选择器作为函数参数。单个选择器长度最多 256、最多 16 段。伪元素、动态状态、命名空间与 CSS 转义不属于此子集。

级联先比较 important，再比较内联／特异性及源码顺序；内联普通声明不能覆盖样式表 important。`:where()` 特异性为零，`:is()`／`:not()` 采用参数的最大特异性。匹配兄弟、祖先和 nth 扫描计入共同工作预算。

| 属性 | 接受值／行为 |
| --- | --- |
| font-size | 正 pt／px，`1 px = 0.75 pt`；不隐式获取字体 |
| font-family | 单一、显式登记家族，通过 `.fontFamily(family,regular,bold,italic,boldItalic)` 映射四种真实角色；未登记默认失败 |
| font-weight／font-style | normal／bold／400／700；normal／italic |
| color | `#rgb`／`#rrggbb`，black／white／red／green／blue／gray／grey |
| line-height | normal、正倍数或 pt／px 长度；倍数随继承后的字号计算，长度保持固定 |
| text-decoration | none、underline、line-through 及两种线的组合；祖先装饰不会被子级 none 静默消除 |
| text-align | left／right／center／justify，进入段落及单元格格式 |
| white-space | normal／pre-wrap；不承诺 pre 的禁止换行行为 |
| margin-top／margin-bottom | 非负 pt／px；非零值需 `CSS_SPACING` 的 DEGRADE，明确转换为累加段间距 |
| margin-left／text-indent | pt／px；最终缩进非负。内联几何与容器盒几何不能当作已支持段落几何 |
| display | none 须 `HIDDEN_CONTENT` 显式 DISCARD；保持元素原有 inline／block 类型的声明可接受，改变类型或表格格式上下文报 `CSS_CONTEXT`。Flex／Grid 见布局模式 |
| break-before／break-after | auto／page，进入真实块间分页；兼容 page-break-before／page-break-after 别名 |
| break-inside | auto／avoid／avoid-page，映射完整块约束；兼容 page-break-inside 别名 |
| orphans／widows | 1～999，映射段落完整行的分页约束；不是字号缩放或浏览器分页仿真 |
| td／th 的 border | none／0／0pt／0px；整张表统一去边框才映射 `TableFormat.drawBorders(false)`。混合覆盖报 `TABLE_BORDERS` 并明确降级为统一边框 |

未支持属性／值／选择器／规则分别使用 `CSS_PROPERTY`／`CSS_VALUE`／`CSS_SELECTOR`／`CSS_RULE`，不匹配的几何上下文为 `CSS_CONTEXT`，未知家族为 `CSS_FONT_FAMILY`。降级／丢弃移除对应声明／规则；不能原样保留 CSS 源。输出以调用方页面／正文配置为基础，没有浏览器的完整默认样式表。

### 媒体与页面

`.media(HtmlImporter.Media.PRINT)` 为默认；可显式选择 SCREEN。`.viewport(PageSize)` 的 pt 尺寸只供媒体查询；默认取调用方最初页面大小，`@page` 改变输出尺寸不会反过来触发响应式规则。

支持有界嵌套 `@media` 的 print／screen／all、not／only、逗号与 and，以及 min／max-width、min／max-height、orientation:portrait／landscape。阈值为 pt／px。查询最多 512 字符、8 个条件，条件嵌套最多 8 层。未命中条件中的语法和安全构造仍检查。

PRINT 模式接受无名称 `@page` 的 size、margin 简写／四边长写：A4、Letter、对应 portrait／landscape，或两个正 pt／px 尺寸。size:auto、命名页、页边距盒、页面选择器和其他纸张名需明确报损；SCREEN 验证规则但不应用页尺寸。页面声明同样执行 important／源码顺序级联。最终页区不足、非法页面尺寸或不可容纳内容继续失败。

## 显式 CSS 布局模式

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;

var document = HtmlImporter.builder()
        .layoutMode(true)
        .media(HtmlImporter.Media.SCREEN)
        .pageSettings(new PageSettings(new PageSize(420, 300), PageMargins.uniform(20)))
        .viewport(new PageSize(420, 300))
        .build().read("""
            <style>p{margin:0;font-size:12pt;line-height:18pt}</style>
            <div style="display:flex;width:360pt;height:70pt;gap:12pt">
              <p style="flex:1 1 60pt;background-color:#d85b36">First</p>
              <p style="flex:2 1 60pt;background-color:#3574bd">Second</p>
            </div>
            """).execute().document();
```

将此 `Document` 交给配置显式物理字体及真实变体的 `DocumentEngine`，再选择 PDF／SVG／PNG 后端。字号、度量、换行和分页仍通过共用引擎；CSS 原文不会交给后端。示例中的两格实际宽为 136 pt、212 pt，中间 12 pt。

| 范围 | 已实现的有限布局契约 |
| --- | --- |
| 盒 | width 为 pt／px／百分比／auto；height 为 pt／px／auto；对应 min／max，max 可为 none。padding 四边、margin 四边、content-box／border-box、有限 RGB 背景色；尺寸和内容均需放入父区 |
| Flex | 显式块容器；row／row-reverse／column／column-reverse，row 的 wrap／wrap-reverse；grow／加权 shrink／basis、order、行列 gap、justify-content、align-items／self、align-content。因子为零或至少 1；显式 min／max 冻结后有界再分配；列方向要求明确容器高度及 nowrap |
| Flex 的尺寸条件 | 每个流内条目须有显式 basis 或主轴尺寸；`flex:1` 等数字简写使用零 basis。内在宽度、自动最小内容宽度和复杂浏览器基线不属于此算法；不得把自动探测当作已支持 |
| Grid | 显式正 pt／px／百分比／fr 列轨，正 pt／px 或 auto 行轨；有限数字 repeat；grid-auto-rows、row／row dense；正行号和 span、order、gap、align／justify-items／self。显式格先占位，合并行在单行测高后分配；显式空行仍占高度。容器高度由行轨求和，轨道组保持起点对齐 |
| 定位 | static／relative／absolute；left／right／top／bottom 接受 pt／px／百分比。relative 偏移按父内容盒解析，百分比纵向偏移要求父盒明确高度；有偏移的顶层 relative 要求另加显式父盒。absolute 要求直接父盒显式定位、每轴至少一个明确偏移，宽度明确或同时约束左右边，从常规流移除；不跨静态中间层寻找浏览器包含块。定位后的直接子盒必须位于父盒内且互不重叠 |
| 文本与资源 | 段落、标题及语义表格作为叶子，保留原文 UTF-16、样式角色、已登记行内图片和被动外链。body 可作为布局根。视觉 order／反向／定位不改变逻辑来源顺序。图片 CSS width／height 为显式 pt／px，单边设置保持登记比例 |
| 分页 | 一个编译盒是原子块，可整体移至后页；外层 break-before／after 产生真实分页。盒内不做 Flex／Grid 跨页碎片化，嵌套 break 经 `CSS_PAGINATION_CONTEXT` + DEGRADE 实际移除；内容超过完整新页即失败 |

布局模式的顶层为 `ComponentBlock`，其内部语义放置只服务当前布局与最终链接解析。标题在运行时布局叶内降低为段落，不建立 PDF/UA 结构；这不是可供通用编辑器逐节点修改的 CSS DOM。内部无边框单格表是复用语义测量的实现桥，不会伪装成用户导入的表格。需要语义编辑或一般 HTML／Markdown／DOCX 互转时，优先使用默认模式。

运行时组件导出到不能表达它的语义格式时，默认拒绝。只有明确 DISCARD 才移除组件整棵子树并报告真实 `CUSTOM_BLOCK` 等目标诊断；WARN 与 IGNORE 都保留实际损失，不能声称保留了文字或盒几何。SVG／PNG 后端对交互外链仍执行自身严格边界，不由布局模式静默删除。

不支持的内在 Flex basis 或无明确高度的列 Flex 可通过 `CSS_LAYOUT` + DEGRADE 实际改为普通块流；两段文字会纵向排列。Grid 及列 Flex 的非 stretch 水平对齐要求显式子项宽度，否则默认拒绝，DEGRADE 实际恢复为 stretch。其他同一功能的降级逐项报告：小于 1 的正 Flex 因子改为 1；Grid 显式容器高度／高度限制及轨道组对齐去除；无父布局盒的根 relative 偏移、无明确父高度的百分比纵向 relative 偏移去除；absolute 缺少的静态位置轴改为零，缺少内在宽度时补齐左右边约束。以上均为默认拒绝，WARN／IGNORE 必须显式选 DEGRADE 后才执行，实际损失报告保留具体效果。

列表在默认语义模式完整保留既有支持；编译盒中的 ul／ol 没有运行时叶映射，按 `CSS_LAYOUT_LEAF` 只允许显式 DISCARD 整个列表子树。其他未建模元素仍执行各自的元素损失策略。

margin 不做浏览器折叠：流内 Flex／Grid 子项采用真实非折叠间距，其他非零纵向 margin 仍要求 `CSS_SPACING` + DEGRADE。百分比 height／min-height／max-height、固定／粘滞定位、负 margin、溢出裁切、minmax／auto-fit／命名网格线、重叠网格、滤镜及完整浏览器样式不在子集内。超出布局盒、页区、预算或不能完整显示的文字始终失败，不以裁切、缩小、忽略异常或隐式系统字体完成输出。

每盒最多 256 个直接子盒，布局深度最多 32，轨道最多 64、span 最多 256、网格最多 10,000 行；每次根盒测量／放置最多 100,000 次有界工作检查。共同导入预算、同步取消和原子旧目标保护继续适用。实现决定见 [ADR-0104](adr/0104-bounded-html-css-layout.md)，独立几何与文件证据见[阶段一百零四](verification/phase-104-html-css-layout.md)。

## 策略与执行边界

复用 [ProcessingPolicy](semantic-editing-api.md)，格式键 `HTML`、操作键 `IMPORT`，优先级 operation → feature → format → default；默认 ERROR。WARN／IGNORE 均保留实际 `issues()`／`losses()`，后者只隐藏警告。HTML_REPAIR 只允许明确 DEGRADE；隐藏内容只允许 DISCARD；无原样保留通道时 PRESERVE 失败。

脚本、事件／srcdoc、主动 http-equiv、iframe／object／embed、SVG／MathML、外部样式、CSS url／import／font-face／expression／转义及危险链接，无论损失策略如何都拒绝。唯一允许的 http-equiv 是项目自包含输出的准确限制型 Content-Security-Policy 声明：`default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'`，只作为 head 元数据经 `HTML_METADATA` + DISCARD 明确移除，不执行或修改导入器的安全策略。refresh、报告端点、其他策略文本及非 meta 容器继续拒绝。完整源树先检查，隐藏／丢弃不绕过这些限制。

共用 `ImportLimits` 的输入、行长、树／深度、文字、表格槽、报告和工作预算。脚本不会执行，资源不会外联；这些限制仍不等于硬 RSS、第三方解析内部硬中断或恶意输入沙箱。

完整样例见 [HtmlImportExample](../examples/src/main/java/com/mirakyux/export4j/examples/HtmlImportExample.java)；运行 `:examples:htmlImport` 和 `scripts/verify_html_import.py`，证据见[阶段四十六](verification/phase-46-html-import.md)。项目导出的列表标记、表格列组及单段表头经明确损失策略重导入见[阶段八十七](verification/phase-87-complex-lossy-recovery.md)，与[有损输出模型恢复](model-recovery-api.md#有损转换后保存当前文件模型)共用真实导入器。不保证导出的任意 HTML 能在默认严格输入模式下无损重新导入。

[阶段八十八](verification/phase-88-section-recovery.md)补齐导出内表的滚动容器重导入。容器变化报告 `CELL_TABLE_WRAPPER_REMOVED` 和真实嵌套格路径；默认 ERROR，WARN／IGNORE 均保留实际损失，PRESERVE 无保留通道。全源安全检查、16 层表深、预算和取消边界继续执行。
