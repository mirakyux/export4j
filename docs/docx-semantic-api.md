# DOCX 原生语义与编辑 API

`export4j-adapter-docx` 提供两层语义。`SectionedDocument` 用于跨格式正文、表格、页区、图像和字段；`DocxSemanticDocument` 在其外保存有类型的 Word 定位、评论、修订、控件、表单和目录。后者保存不可变项目模型，不保存原始 ZIP、XML 片段或第三方对象。

## 选择入口

| 目标 | 入口 | 行为 |
| --- | --- | --- |
| 读取通用模型 | `read(input, Policy)` | 专有结构按 `STRICT` 拒绝，或按 `ALLOW_LOSS` 返回实际诊断 |
| 原生语义读取 | `readSemantic(input, Policy)` | 返回 `SemanticReadResult`，包含原生模型与未支持结构诊断 |
| 原生语义新建／重建 | `writeSemantic(document, output)` | 从有类型模型生成新的标准 OOXML 包；验证全部成功后才写入调用方流 |
| 原生导入、编辑、输出 | `semanticConversion(bytes)` | 导入、顺序编辑、编码一次，`prepare()` 后提供预览与可重复发布的编码 |
| 进入通用转换 | `project(document, policy)` 或原生转换的 `project()` | 按实际损失策略投影，再接 `DocumentConversion` 的编辑与目标后端 |
| 普通文件互转 | `conversion(bytes)` | 已接入原生读取和显式投影，未编辑对象也必须报告损失 |
| 原包局部文字修改 | `editPackage(bytes)` | 保留未修改部分的包结构；其能力边界见[原包编辑](docx-editing-api.md) |

读取流与输出流由调用方关闭。输入字节在转换入口处快照。`Prepared.writeTo(Path)` 在目标目录创建临时文件，完成后以原子移动发布；失败或取消会清理临时文件。普通流的最终 I/O 失败仍可能留下已经写入的部分数据。

普通导出和原生语义重建均在 `word/settings.xml` 显式声明 `compatibilityMode=15`（Word 2013 及后续版本的现代排版模式），并写入对应部件关系与内容类型。缺少该声明时，Word 会按旧版排版规则打开 DOCX，标题显示“兼容性模式”，另存时可能提示升级格式；它与文件损坏提示不同。`editPackage` 局部编辑保留源包的兼容设置，不自动升级已有文档。该声明不保证不同 Word 版本逐页一致。

新文件在 Word 中实际打开、编辑、保存和重开的结果见[阶段一百零七](verification/phase-107-windows-reader-compatibility.md)。

## 有类型对象

| 对象 | 实际映射 |
| --- | --- |
| `Drawing` / `Picture` | 嵌入 PNG／JPEG、行内或 `wp:anchor` 浮动图片；页、页边距、栏、段落、字符等定位基准；对齐或 pt 偏移；绕排、多边形、层级、重叠与四边距离；裁剪、旋转、水平／垂直翻转 |
| `TextBox` | `wps:wsp` / `wps:txbx` 的真实段落与表格；尺寸、四边内距、垂直对齐、RGB 填充与边框、形状旋转与翻转 |
| `Comment` | 作者、首字母、日期、段落／表格评论正文，以及可跨段的实际评论范围 |
| `Revision` | 插入、删除、移出、移入、字符格式和段落格式；移动按标准命名范围配对；旧格式为 `InlineStyle`／`ParagraphFormat` |
| `Control` | 富文本、纯文本、复选框、下拉、组合框、日期、图片、分组、重复节与重复项；真实值在正文范围中，列表选项和原生属性有类型保存 |
| legacy 表单 | `FORMTEXT`、`FORMCHECKBOX`、`FORMDROPDOWN` 的真实复杂字段与 `ffData`；默认值、最大长度、文本类型／格式、帮助／状态文字、启用状态、退出计算、复选框尺寸和下拉默认项 |
| `Contents` | TOC 原生字段声明、标题级别、超链接／页码选项、缓存范围和更新标志；缓存中的派生页码字段按目录缓存文字读取 |

正文及文本框的段落 ID 共享命名空间。`Point` 使用原文 UTF-16 偏移；`Range` 是左闭右开的两端点，可跨同一 Word story 的段落。图片指向正文中的实际 `InlineImage` 标记；文本框是零宽锚点，其正文由自己的块模型保存。侧边正文的普通段落会规范化为显式默认样式运行；绘图列表按源段落和 UTF-16 锚点排序。

图片内容控件必须覆盖单个真实行内图片。Word 不接受图片控件包裹浮动图片的组合，模型会直接拒绝；需要浮动图与图片控件时，分别创建浮动对象和行内图片控件。评论范围可进入控件内部，输出保留一个完整控件及真实评论端点，不通过拆分控件复制身份。

分组控件使用 Word 要求的 `contentLocked`：组内普通文字不能直接改写，但可编辑内嵌的独立值控件。`replaceText` 也遵守这个边界，不能通过跨范围替换绕过分组锁。其他内容锁、控件锁和数据绑定仍拒绝。

四层及更深的 `p → tbl → p` 块格、合并表格、两节以上的默认／首页／偶数页页区可组合使用，仍遵守已有 16 层表格上限、完整网格和页区来源去重规则。`TableFormat.drawBorders(false)` 写入六边 `w:tblBorders` 的 `nil`，回读保留无边框值。基础契约见[支持矩阵](capabilities-api.md)、[页区](docx-page-regions-api.md)、[字段](docx-fields-api.md)和[样式导入](docx-style-import-api.md)。

## 顺序编辑

```java
var adapter = new DocxAdapter();
var prepared = adapter.semanticConversion(sourceBytes)
        .edit(edit -> edit
                .setControlText("customer-name", "Updated customer")
                .setChecked("approved", true)
                .replaceText("textbox-paragraph", 0, 3, "New")
                .acceptRevision("review-deletion")
                .rejectRevision("review-insertion"))
        .prepare();

var preview = prepared.preview();
prepared.writeTo(targetPath);
```

ID 必须来自原生模型或调用方创建时指定的 ID。`drawing`、`comment`、`revision`、`control`、`contents` 可新增或替换对应类型化记录；`removeComment` 删除评论；`updateContents(id, headingPages)` 根据标题模型重建缓存。页码来自调用方实际分页结果，不推测 Word 页码。需要 Word 的最终分页与超链接目录时，仍须在 Word 中更新目录／字段。

文字编辑会顺序更新正文和文本框中的 UTF-16 锚点。删除范围中的位置折叠到替换边界；后方位置按真实长度差移动。替换不能拆开代理项或原子图片／字段。覆盖绘图锚点需显式允许 `DRAWING_REMOVED` 的 `DISCARD`，不会默默删除图像或文本框。控件赋值验证下拉选项、启用状态和最大长度。

接纳删除会删除实际源文字；拒绝插入会删除实际插入文字；移动成对处理；拒绝格式修订会恢复有类型旧格式。输出包不存在用来藏回被删除内容的原始 XML、原包或恢复副本。跨段文字删除保留模型中的段落边界，段落标记／表格行列本身的结构修订不属于已映射语法。

`PARAGRAPH_FORMAT` 的旧字号必须与当前段落字号相同，旧段落属性按当前字号继承；字号修改属于 `RUN_FORMAT`。OOXML 的旧段落属性容器不接受运行属性，不能把字号差异写进无效的 `pPrChange/pPr/rPr`。

## 表单当前值与字段更新

legacy 文本表单的当前值保存在正文范围，默认值保存在 `ControlOptions.defaultText`，两者可以不同。legacy 下拉表单同时写出当前选择索引和默认选择索引；当前范围必须在单个段落内并匹配一个现有选项。不能把默认值改成当前值来掩盖表单重置。

原生输出不添加全局 `updateFields`，也不把普通 PAGE 等计算字段无条件标为 `dirty`。Word 打开文件时的自动宽泛更新可能把 legacy 输入重置成默认值；这与只更新指定 TOC／计算字段不同。`updateContents(id, headingPages)` 根据调用方提供的实际页码生成缓存后，将该 TOC 标为非脏；读取时保留真实更新标志。调用方显式设置的 `Contents.dirty=true` 仍写入标准字段，选择此行为时须考虑 Word 的重算副作用。

普通文字编辑、修订决策和范围重定位保留调用方已有的更新标志，不自动开启 Word 重算，也不计算 Word 最终页码。修改正文或标题后，需要调用方再次 `updateContents` 或在 Word 中显式刷新目录；保留旧缓存不代表目录已经更新。

需要应用最终页码时，在 Word 中定向更新目录和 PAGE 等计算字段，跳过 `FORMTEXT`、`FORMCHECKBOX` 和 `FORMDROPDOWN` 用户输入字段；不要用对全部字段的更新代替输入保留。既有普通 `write` 入口的字段更新默认契约保持原样。原生专项验收分别检查打开、字段更新、保存、重开后的实际当前值和独立默认值，不用字段锁阻止正常表单编辑。

## 互转损失策略

```java
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .feature("COMMENTS", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DISCARD)
        .build();

var prepared = adapter.semanticConversion(sourceBytes)
        .edit(edit -> edit.setControlText("customer-name", "Updated customer"))
        .project()
        .policy(policy)
        .prepare(target);
```

| 特性键 | 处理 | 实际影响 |
| --- | --- | --- |
| `REVISIONS` | `DEGRADE` | 接纳修订，保留最终内容，移除审阅行为与作者／日期 |
| `COMMENTS` | `DISCARD` | 移除评论正文、作者／日期和范围 |
| `CONTENT_CONTROLS` | `DEGRADE` | 保留当前值内容，移除交互行为与约束 |
| `TABLE_OF_CONTENTS` | `DEGRADE` | 保留目录缓存内容，移除 Word 更新声明 |
| `FLOATING_DRAWING` | `DEGRADE` | 浮动图片改成行内图片，丢失定位、绕排和层级 |
| `IMAGE_TRANSFORM` | `DEGRADE` | 实际执行裁剪、翻转和旋转后生成 PNG；保留可见变换，丢失原生变换的可编辑性 |
| `TEXTBOX` | `DEGRADE` | 将真实块内容插入锚点段落之后，丢失图框、定位和旋转 |
| `TAB_LAYOUT` | `DEGRADE` | 将制表符替换成单个空格，保留文字、样式、外链及 UTF-16 长度，丢失 Word 制表位对齐；覆盖正文、页区、文本框和表格格 |
| `INLINE_IMAGE_LINE_HEIGHT` | `DEGRADE` | 原固定行高容不下行内图片时，扩大到图片高度、基线位移绝对值和文字字号之和；仅修改实际过小的段落或格内行高，可能改变分页 |
| `OOXML_MARKUP` | `DEGRADE` | 导入时移除或简化没有模型映射的 OOXML |
| `STYLE_GRAPH` | `DEGRADE` | 将命名样式与继承图展开为具体格式，保留实际字体／颜色／段落属性，移除图和命名身份 |

`ERROR` 阻止准备；`WARN` 处理并显示警告；`IGNORE` 处理并保留可查询的实际损失记录。动作不改变处理方式，`IGNORE` 不等于无损。不存在经过验证的保留路径时不能指定 `PRESERVE`。目标后端仍会增加自己的实际损失报告。

## 明确边界

所有 DOCX 输入共用[完整 ZIP 校验](docx-editing-api.md#安全预算和输出)：完整压缩输入及展开量分别受限，解压前验证中央／局部记录及实际条目一致性。经典单磁盘 STORED／DEFLATED 包可读取；ZIP64、加密、多磁盘、截断、目录冲突或尾随数据失败，不受损失动作策略放宽。普通语义、原生语义、转换与原包编辑入口遵守相同包边界。

输入仍是有限的离线 OOXML 语法，不是任意 Word 功能的通用解释器。宏、ActiveX、OLE／嵌入包、外部资源、危险／未知字段指令、签名和保护文档始终拒绝，损失策略不能绕过。安全超链接仅保存声明，不执行，也不读取网络资源。

Word 保存的 `mc:AlternateContent` 在完整包安全检查后，只选择映射到已支持 `wps`／`w14`／`w15` 命名空间的 `Choice`，不把 Fallback 内容重复加入正文。没有支持分支仍拒绝；备用分支中的活动对象也不能绕过安全检查。合法八位十六进制 `wp14:anchorId`／`editId` 属于可再生成的编辑身份，读取时规范化；有类型绘图身份来自 `docPr`。

Word 保存时可能补充复选框字形／字体、日期的规范存储值／日历、命名样式图和其他展示属性。未映射属性在 `ALLOW_LOSS` 下逐项返回诊断，严格模式仍拒绝，不能将实际显示内容保留称为这些属性无损。Word 还可能移除 Export4j 的固定页区高度元数据；读取这类外部页区应通过 `DocxAdapter.Builder.regionGeometry(...)` 显式给出实际几何，不推测高度。

组合形状、任意形状路径与效果、主题／系统色、非零组局部坐标、文字旋转／多栏文本框、独立 VML 文本框、数据绑定或上述分组例外以外的锁定内容控件、活动表单回调、任意 TOC 开关、结构修订，以及没有有类型映射的扩展属性不会冒充支持。已知可降级的展示属性返回诊断；缺少安全或有效结构的输入直接失败。只有声明、原包字节未变或能打开文件，都不能代替语义编辑与阅读器验收。

DOCX native 语义不保证 Word 重排后的逐像素版式。Word 打开、更新字段、修改控件／文本框、保存、重开和导出 PDF 需要分别验证。两节四页组合已通过这些应用动作、Word 原生页面渲染、PDF 双引擎渲染及逐页复查，复现入口与实际证据见[专项验收](verification/phase-103-native-docx-semantic-editing.md)。Word 只用于独立兼容性验收；Export4j 的生成、导入、编辑及转换不依赖 Office、打印机驱动或打印服务。
