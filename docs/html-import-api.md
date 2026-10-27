# 离线 HTML／CSS 导入

`export4j-adapter-web` 的 `HtmlImporter` 将离线 HTML／CSS 子集转换为 `Document`，支持[语义编辑](semantic-editing-api.md)后重新导出。它不执行脚本、不访问文件／URL，也不复现浏览器分页。

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

支持 style 元素及内联 style。选择器限元素、`*`、`.class`、`#id`、同元素组合、后代、直接子及逗号列表；长度最多 256、最多 16 段。伪类、属性、兄弟选择器和媒体规则等不支持。级联先比较 important，再比较内联／特异性及源码顺序；内联普通声明不能覆盖样式表 important。

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
| display | 仅 none；须 `HIDDEN_CONTENT` 显式 DISCARD，保留实际删除报告 |

未支持属性／值／选择器／规则分别使用 `CSS_PROPERTY`／`CSS_VALUE`／`CSS_SELECTOR`／`CSS_RULE`，内联段落几何为 `CSS_CONTEXT`，未知家族为 `CSS_FONT_FAMILY`。降级／丢弃只移除不支持声明／规则；不能原样保留 CSS 源。嵌套规则不进入扁平语法，报告对应样式块并去除未处理后缀。输出采用调用方的默认页面／正文配置，没有浏览器的完整默认样式表。

## 策略与执行边界

复用 [ProcessingPolicy](semantic-editing-api.md)，格式键 `HTML`、操作键 `IMPORT`，优先级 operation → feature → format → default；默认 ERROR。WARN／IGNORE 均保留实际 `issues()`／`losses()`，后者只隐藏警告。HTML_REPAIR 只允许明确 DEGRADE；隐藏内容只允许 DISCARD；无原样保留通道时 PRESERVE 失败。

脚本、事件／srcdoc、主动 http-equiv、iframe／object／embed、SVG／MathML、外部样式、CSS url／import／font-face／expression／转义及危险链接，无论损失策略如何都拒绝。唯一允许的 http-equiv 是项目自包含输出的准确限制型 Content-Security-Policy 声明：`default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'`，只作为 head 元数据经 `HTML_METADATA` + DISCARD 明确移除，不执行或修改导入器的安全策略。refresh、报告端点、其他策略文本及非 meta 容器继续拒绝。完整源树先检查，隐藏／丢弃不绕过这些限制。

共用 `ImportLimits` 的输入、行长、树／深度、文字、表格槽、报告和工作预算。脚本不会执行，资源不会外联；这些限制仍不等于硬 RSS、第三方解析内部硬中断或恶意输入沙箱。

完整样例见 [HtmlImportExample](../examples/src/main/java/com/mirakyux/export4j/examples/HtmlImportExample.java)；运行 `:examples:htmlImport` 和 `scripts/verify_html_import.py`，证据见[阶段四十六](verification/phase-46-html-import.md)。项目导出的列表标记、表格列组及单段表头经明确损失策略重导入见[阶段八十七](verification/phase-87-complex-lossy-recovery.md)，与[有损输出模型恢复](model-recovery-api.md#有损转换后保存当前文件模型)共用真实导入器。不保证导出的任意 HTML 能在默认严格输入模式下无损重新导入。

[阶段八十八](verification/phase-88-section-recovery.md)补齐导出内表的滚动容器重导入。容器变化报告 `CELL_TABLE_WRAPPER_REMOVED` 和真实嵌套格路径；默认 ERROR，WARN／IGNORE 均保留实际损失，PRESERVE 无保留通道。全源安全检查、16 层表深、预算和取消边界继续执行。
