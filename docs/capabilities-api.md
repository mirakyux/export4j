# 模板、复杂文本与受控导出

[XML 模板源码编辑与资源 ZIP](xml-template-bundle-api.md)复用现有模板适配器：保留可编辑主源、片段、图片、显式字体及许可文本，普通 ZIP 保存／重开后继续使用 `XmlTemplate → DocumentTemplate`。不是任意已渲染文档的模型恢复入口。

[语义模型保存为 XML](xml-model-saving-api.md)支持正文／分节、富文本／独立列表标记、表格／递归块格、四类字段和图片，保留现有格式与原文字面值，并接入统一转换。[页区保存](verification/phase-79-xml-region-saving.md)保留默认／首页／偶数页眉页脚、共享来源及有序图层位置。[阶段八十](verification/phase-80-xml-semantic-saving.md)进一步保留显式注释及标记、Caption 和标题／列表文字外链，`renderComplete(data)` 同时返回文档与注释，三轮控制、编辑、10 页 PDF 双渲染及 Web 损失对账通过。[阶段八十一](verification/phase-81-xml-cross-reference-saving.md)补齐独立 CrossReference 的完整 XML 保存／绑定，覆盖跨节 PAGE／NUMBER、多行引用、片段／循环目标与实际改写。未映射块默认拒绝，显式策略才报损继续；字体／许可由调用方明确提供。XML 保留范围不扩大 DOCX／Web 适配器能力。

XML 静态页面配置已由[阶段三十六](verification/phase-36-xml-page-settings.md)验收：主根首个可选 `page` 支持纸张／成对宽高／方向／四边边距，页面长度支持 pt／mm／cm／in。未声明项保留 Java 默认，详见[页面指南](xml-page-settings-api.md)。[阶段四十一](verification/phase-41-xml-page-regions.md)补齐[固定页眉页脚](xml-page-regions-api.md)、独立主题及最终页码；[阶段四十二](verification/phase-42-sections-and-docx-regions.md)补齐页区图片／表格、XML 分节与 DOCX 默认页区。全局字体角色／颜色已由[阶段六十](verification/phase-60-theme-appearance.md)接入；首页／奇偶页区已由[阶段六十一](verification/phase-61-page-variants.md)验收；[XML 背景／前景](page-overlays-api.md)已由[阶段六十二](verification/phase-62-page-overlays.md)验收。HTML/CSS 的静态级联与显式文档布局模式见[导入指南](html-import-api.md)，不将其等同于完整浏览器。

[固定页区字体角色](page-region-fonts-api.md)保留引擎注册角色、缺字策略及最终页上下文。标准组件现在可用 [FontRoleScope／RichTextComponent](component-text-api.md) 直接选择字体组和编写固定富文本，无需自定义委托；延迟 SemanticTable 仍不能进入固定页区。XML 页区通过 PageDocument 和 PageRegionTemplates 接入相同预留／最终组合路径，主题不向正文级联。

[列表标记](list-markers-api.md)独立保存完整 InlineStyle，标准 DOCX 使用所选 level 的运行属性；[XML 文字默认值](xml-text-defaults-api.md)按正文／标题／单元格独立配置角色和颜色，显式局部覆盖、命名完整样式及嵌套格范围保持。验收分别见[阶段三十八](verification/phase-38-component-text.md)、[三十九](verification/phase-39-list-markers.md)及[四十](verification/phase-40-xml-text-defaults.md)。整体试用边界见[就绪评估](readiness-assessment.md)。

当前沿用 `com.mirakyux.export4j` 和现有 Maven 坐标，共 18 个模块。可选 `export4j-opentype` 提供原生文字能力，核心不强制引入 JNI。最初能力扩展的样例见 [CapabilitiesExample](../examples/src/main/java/com/mirakyux/export4j/examples/CapabilitiesExample.java)；当前范围按各节 API 及验收判断。

## 已有 PDF 处理与语义恢复

[标准文件恢复](model-recovery-api.md)可选择普通文件、准确字节绑定的 XML／资源 ZIP 伴随包，或显式单文件内嵌。七种输出三轮、49 组 A→B→A 和实际编辑见[阶段八十二](verification/phase-82-model-recovery.md)；[八十三](verification/phase-83-embedded-html-recovery.md)与[八十四](verification/phase-84-embedded-comment-recovery.md)提供 HTML／Markdown／SVG 注释封装，[八十五](verification/phase-85-embedded-png-recovery.md)提供单页 PNG 辅助块，[八十六](verification/phase-86-standard-container-recovery.md)提供 PDF 附件、DOCX 与 PNG 页 ZIP 成员，并记录标准读取器、外部改写和体积实测。文件修改后旧模型拒绝或显式受限导入；有损导出不隐藏源副本。无恢复信息的任意文件语义识别和第三方应用无损重存仍未提供。

[阶段八十七](verification/phase-87-complex-lossy-recovery.md)将表格／图片／列表的往返扩大到七种输出及 49 组复杂 A→B→A。`reimportOnLoss` 允许在显式 DEGRADE 下从本次准确输出字节重建恢复模型，保存实际导入损失；不会夹带较丰富源候选。HTML 列组和单段表头支持明确降级，显式列表标记与正文分离以避免多轮前缀叠加；原生编号引用按 PDF／DOCX 保留及 Web 静态降级分别验收，静态后端拒绝交互链接。更广语义组合及应用另存仍未据此完成。

[阶段八十八](verification/phase-88-section-recovery.md)补充两节四页、共享页区／变体／重叠图层与嵌套表／纵向合并格的六格式三轮及 36 组 A→B→A。HTML 格内导出滚动容器可按 `TABLE_CELL_CONTAINER` 明确降级／丢弃；DOCX 的图层丢弃、Web 的分节与选择规则损失按当前实际文件记录。单 PNG 仍拒绝多页输入，注释／引用的更广组合和真实应用另存继续独立验收。

[阶段八十九](verification/phase-89-navigation-recovery.md)补充注释／题注／引用的四格式三轮及 16 组矩阵。[阶段九十](verification/phase-90-recovery-closure.md)进一步完成 GAP-24 的整体验收：[DocumentNavigation](portable-navigation-api.md)保存自动标题筛选、手工书签、目录文字／排版／颜色，恢复后自动解析当前标题与最终页码；分节、页区变体、图层、嵌套合并表、图片、注释和引用的组合完成三轮及 16 组 A→B→A。PDF 保留本组合完整模型，DOCX／Web 按明确损失从实际文件重新导入，已丢声明不会回来。真实 Word 编辑／Save／关闭／重开后，默认拒绝缺失／过期信息，显式页区几何及损失策略才读取当前内容。任意应用兼容和外部结构识别仍按独立缺口推进。

[PdfAdapter](pdf-adapter-api.md)提供各自独立的静态复制、页级编辑、被动对象编辑／组合、渲染／提取、优化、文字替换、区域脱敏和语义恢复入口；公共签名不暴露第三方类型。所有入口保留权限、签名、安全解析、预算、取消和原子路径输出边界，不能因另一入口支持某对象而自动放宽。

| 能力 | 实际子集及证据 |
| --- | --- |
| [页级编辑](pdf-page-editing-api.md) | 旋转／裁剪／等比缩放、显式图文叠加；裁剪隐藏未删除；[阶段四十九](verification/phase-49-pdf-page-editing.md) |
| [对象编辑](pdf-object-editing-api.md)／[组合](pdf-object-composition-api.md) | 基础字段及多行／comb／富文本／多选／自适应字号、外观／扁平化、六种被动标记批注和既有对象；[FDF／XFDF](pdf-form-data-api.md) 数据交换；选页及跨来源关系重映射仍按独立组合子集 |
| [渲染与图片](pdf-raster-api.md) | CropBox／旋转／UserUnit、明确 DPI／背景、PNG／JPEG 及去重资源提取；验证嵌入静态字体，或使用显式源字体映射并报告替代损失；未知外观可明确跳页；[五十二](verification/phase-52-pdf-raster.md)／[九十三](verification/phase-93-pdf-compatibility-closure.md) |
| [优化](pdf-optimization-api.md) | 无损流压缩、相同简单图片／字体程序流去重；显式图片降采样／JPEG，返回确切大小与像素统计，不保证更小；[五十三](verification/phase-53-pdf-optimization.md) |
| [文字替换](pdf-text-editing-api.md) | 同一行跨受支持 Tj／TJ、字体／颜色／定位匹配；样式／字距降级须显式接受，未命中字形位置保持；普通 Form 按命中调用复制，被动标记内部及结构所有者仍拒绝或跳过；不是脱敏 |
| [图片实例替换](pdf-image-editing-api.md) | 实际深度优先绘制次序、共享 Form 隔离、原 cm／BBox／clip 和图层成员保留；蒙版替换明确报损 |
| [区域脱敏](pdf-redaction-api.md) | 原光栅入口清除像素后新建纯图片 PDF；选择性入口在明确不透明静态子集中重建保留字形、图像及真实剩余路径，删除源流／替代文本／附件／历史；不支持结构整体拒绝 |
| [XFA](pdf-xfa-api.md) | 独立静态定位／受支持流式重复的读取和扁平化子集，不执行脚本或外联；普通对象入口继续拒绝 |
| [语义恢复](pdf-semantic-import-api.md) | 默认逐行；显式 AUTO 检测多栏／段落／标题列表／重复页区、有线无线及合并嵌套续表，显式网格优先；保留叶框／物理格路径和推断损失，再转换 DOCX／HTML／Markdown／PDF；不保留原绝对版面 |

不支持任意 PDF 无损编辑或版面恢复，OCR 不在范围内。脱敏区域由调用方完整指定；不是自动发现敏感信息。各坐标系和字体／页面子集以对应 API 为准。

外部 PDF 的[嵌入静态 CFF 字体](pdf-font-compatibility-api.md)已贯通渲染、文字替换、光栅脱敏及语义恢复；新建 PDF 支持静态 CFF 正文原生嵌入。未嵌入字体没有隐式系统替代，复杂内容流和交互结构的入口边界保持独立。

[显式源字体映射](pdf-font-compatibility-api.md#渲染与脱敏的显式源字体映射)允许渲染／光栅脱敏使用调用方静态 TrueType 替代无程序的简单字体，保留源编码及字宽，并报告外观替代损失；不扩大文字编辑／语义恢复的字体范围。语义恢复另接受明确白名单的普通不透明 ExtGState，透明度、软蒙版、未知键、Form 与裁剪仍按各自边界处理。两项实现与独立控制见[阶段九十一](verification/phase-91-pdf-source-fonts-and-graphics-state.md)。

[阶段九十二](verification/phase-92-pdf-form-recovery.md)进一步支持普通不透明 Form 的实际调用恢复：嵌套／重复实例、局部或继承资源、正向等比缩放和平移，以及变换后的文字颜色／字号、图片和物理来源。隐式 BBox 检查真实字形轮廓与图片矩形，完全隐藏内容报损丢弃。该阶段尚未包含的原 Form 编辑、显式裁切及透明合成由后续阶段扩展。

[阶段九十三](verification/phase-93-pdf-compatibility-closure.md)完成 GAP-10.6 原剩余清单：字典编码／水平 CID 源字体映射、传统 Type1／静态 Type3 绘制、共享 Form 调用隔离编辑、更多图形状态、显式裁切／透明合成，以及被动图层／标签的读取与原文档保留。图层／标签须显式开启 `allowPassiveStructures(true)`；读取使用默认 VIEW，关闭层文字不泄露，跨文档复制／组合仍严格拒绝。各入口的保留、编辑和恢复范围见[兼容矩阵](pdf-passive-structures-api.md)。

可渲染而不能表达为当前语义模型的页面，可显式选择 `PDF_STRUCTURE / DEGRADE` 整页恢复为流图片，再输出 PDF／DOCX／HTML／Markdown；正文、矢量和结构不再可编辑，损失逐项报告。`SKIP_BLOCK` 仍可整页跳过；默认 ERROR，WARN／IGNORE 处理一致，非法输入、缺字、预算和取消仍失败。源字体映射仅服务渲染／光栅脱敏，不隐式进入语义恢复。

## HTML 与 Markdown 输出

[Web 适配器](web-export-api.md)直接映射语义文档，支持 HTML5 和 CommonMark/GFM 内容；可全局及逐功能指定 ERROR／WARN／IGNORE。HTML 保留合并／嵌套表格、样式和嵌入图片；Markdown 对格式损失显式降级并保留锚点内容。默认严格，非法输入／超限／取消不能忽略，证据见[阶段四十三](verification/phase-43-web-exports.md)。HTML/CSS 输入使用下节的独立入口；语义转换不承诺 PDF 布局往返。

## 离线 HTML／CSS 输入

[HtmlImporter](html-import-api.md)支持明确的元素／属性与静态 CSS 子集，包括优先级／继承、显式图片、列表和双跨度／有界递归块格。源位置及实际损失可查询；隐藏内容、margin 折叠和未建模结构不被静默当作无损。脚本／隐式外联拒绝，证据见[阶段四十六](verification/phase-46-html-import.md)；不承诺完整浏览器布局。

## 通用 Markdown 输入

[MarkdownImporter](markdown-import-api.md)支持 CommonMark／GFM 子集，含强调、链接、嵌套列表、表格、删除线、硬换行及显式图片。代码／引用等按共享策略明确报损，预检绑定候选，IGNORE 保留实际损失；不执行变量绑定或隐式外联。见[阶段四十五](verification/phase-45-markdown-import.md)，不等于完整 GFM 或任意 Markdown 无损往返。

## 语义编辑

[DocumentEditor](semantic-editing-api.md)支持 Document／PageDocument／SectionedDocument 的块与物理格编辑、图文和样式修改、跨运行字面查找替换及输入快照保持。共享策略区分 ERROR／WARN／IGNORE 与跳过／降级／保留，预检绑定确切候选；IGNORE 仍返回实际损失。完整性、Unicode、预算和取消不可忽略。证据见[阶段四十四](verification/phase-44-semantic-editing.md)，不代表一般源 DOCX 或已有 PDF 的无损编辑。

## 公共文本和字体

DOCX [样式级联](docx-style-import-api.md)支持默认值、basedOn、字符样式和直接格式，并通过显式字体族映射选择真实角色；图展开损失明确报告。[原包编辑](docx-editing-api.md)可修改普通文字并保留未编辑结构。[类型化语义模型](docx-semantic-api.md)另行表达浮动图片／真实文本框、图片变换、批注范围、插入／删除／移动／格式修订、内容控件／旧式表单及可更新目录声明，编辑时重定位锚点，再生成标准 OOXML。投影到通用模型时逐项报告损失，旧的普通语义入口也检查未编辑的专有结构；不承诺任意 Word 无损往返或与 PDF 同分页。

[HTML/CSS 导入](html-import-api.md)保持默认语义模式，并提供显式 CSS 布局模式。离线选择器／媒体级联、页面／分页规则、Flex／Grid、尺寸和相对／绝对定位映射为确定的组件树，显式字体和资源由调用方提供。布局叶保留真实段落及最终外链／字段；不能表达组件布局的语义输出按 ERROR／WARN／IGNORE 与明确处理返回损失，不执行脚本或隐式外联。

默认 `ParagraphLayouter` 对组合字符、阿拉伯语、希伯来语等需要塑形的文本，以及静态 CFF 字体，使用显式物理字体的 JDK OpenType 布局。语义段落、富文本单元格、`FlowText`、页眉页脚、脚注和目录文本因此共享该能力。简单文字仍保持原有度量；需要对纯拉丁文字显式启用 kerning／连字时继续使用 `OpenTypeText`。

塑形结果保存在 `TextLine.outlines()`，简单路径保存在 `runs()`，两者互斥。分页或自定义布局调整基线时使用 `atBaseline()`，不能只复制旧的六个字段。`minimumLineHeight(group, size)` 可查询兼容两种路径的行高下限。固定行距不足明确失败，不自动缩小字号。字形簇跨越不同富文本样式边界目前拒绝，调用方应让同一组合字符位于同一行内节点。

字体组仍默认缺字报错；占位模式保留原字符范围并返回警告。一个字形簇必须由同一物理字体完整覆盖，不扫描系统字体。

`JUSTIFY` 在布局阶段分配软换行内的空隙；`CJK_STRICT` 可选启用原文标点和不换行组合检查。标题与列表支持富文本和外链，模板与 DOCX 保留运行；常用 Builder、自动 ID、等宽文本表格和原子路径输出见[编写指南](authoring-api.md)。

~~~java
var member = FontCollectionLoader.load(collectionBytes, 1); // TTC/OTC，明确选择第二个成员
var cff = TrueTypeFontLoader.load(otfBytes);                 // 静态 OTF/CFF 或 TTF
~~~

集合和提取成员分别受字节预算控制。[VerticalText](vertical-text-api.md)保留显式字格入口；可选[原生 OpenType 模块](native-opentype-api.md)提供 HarfBuzz 方向／特性塑形、可变 glyf／CFF2、彩色字形、纵中横及分页竖排。字体和平台 native classifier 都由应用显式提供。静态 CFF 正文支持原生 PDF 字体嵌入，原生模块的可变／彩色实例则生成确定的轮廓／图片和 ActualText。两种路径分别验证提取、查询、选中与视觉，不把逻辑锚点矩形当作精确字形轮廓。PDF 可编辑字段仍需要显式 TrueType 字体。

`TableCell.rich(inlines, cellFormat)` 保留真实字体角色、颜色、上下标、下划线和删除线。`InlineText(text, style, uri)` 增加被动外链，允许 HTTP、HTTPS、mailto，禁止脚本、文件链接和带用户信息的 HTTP URL。外链按最终行片段解析，包括跨页段落与表格；不获取链接内容。SVG／PNG 仍拒绝交互对象，调用方须显式提供无交互版本。

## 业务模板

外部文本入口提供[安全 XML 模板子集](xml-template-api.md)和[严格 Markdown](markdown-template-api.md)，由独立适配器编译到下列既有节点。XML 可声明固定宽字段、1～9 级十进制编号及被动图文外链；Markdown 面向标题／段落／一级条目和强调。未知内容明确拒绝，资源仅由调用方显式注册。它们不支持任意 HTML/CSS。

[XML 多级编号](xml-numbering-api.md)复用既有父计数、重启与完整编号引用，each／include 保留列表身份及当前副本目标；全部分支检查静态层级，实际父完整性在布局／导出验证。[阶段三十五](verification/phase-35-xml-numbering.md)完成九级边界、原生 DOCX 规则及最终 PDF 检查；后续[阶段八十](verification/phase-80-xml-semantic-saving.md)支持编号正文文字外链，字段／图片仍拒绝。

XML 的[多段格／内表](xml-block-cells-api.md)直接映射有界递归块模型，Each／Rows 保留后代格式、来源及同副本引用；所有分支在编译阶段检查几何、深度和内容互斥，见[阶段二十八](verification/phase-28-xml-block-cells.md)。

XML 可用[主题与静态名称](xml-themes-api.md)复用完整正文、六级标题及语义表默认值；`p`／`span` 显式属性最后覆盖，所有分支编译校验。主题不改变 XML body／cell 的独立配置范围，不提供 CSS，验收见[阶段三十一](verification/phase-31-xml-themes.md)。

`DocumentTemplate(metadata, pageSettings, nodes, limits).render(data)` 返回完整语义文档，失败时不返回部分结果：

| 节点 | 行为 |
| --- | --- |
| `ValueBlock(prototype)` | 替换段落、富文本、标题、列表和语义表格中的 `${customer.name}` 等变量 |
| `When(path, yes, no)` | 路径必须是布尔值，只展开选中分支 |
| `Each(path, variable, children)` | 遍历列表，变量具有嵌套作用域，块 ID 增加确定性循环后缀 |
| `Rows(id, path, variable, columns, headers, row, format)` | 固定表头加集合驱动的正文行；空集合明确失败，可由 `When` 控制是否显示表格 |

数据只接受非空值的字符串键 Map、List、字符串、布尔值及标准有限数值；不反射任意对象。BigInteger／BigDecimal 须为精确标准类。快照与展开均有深度、访问次数和文字预算，展开另有块及表格行预算。文字预算包括 Map 键、数据字符串与生成字符串；访问次数也计循环、单元格与行内工作项。追加和大数转换前检查预算，未引用的超限大数也拒绝。缺路径、类型错误、非法表达式、未知块和预算超限具有稳定 `BindingException.Code` 与安全路径；超长或非法表达式定位为 `expression`。

模板不提供代码执行或隐式格式化。日期、金额、国际化格式由调用方先准备。XML 的静态引用目标按当前词法复制作用域重定位，外部静态目标保持；循环外不推测某次迭代。动态 target 由数据提供完整最终 ID，不自动添加前缀或后缀。动态 href 在每个副本中绑定并校验最终地址，非法协议／凭据脱敏失败。[离线 include](xml-includes-api.md)只选择显式登记字符串，支持最多 16 层无循环引用、父子组合命名与父到子静态引用，见[阶段五十八](verification/phase-58-recursive-includes.md)。根级静态注释使用完整绑定入口，组件及动态注释声明仍拒绝。

## 布局扩展

PDF 四类可编辑字段可通过[FormStyle](form-styles-api.md)设置文字／标记色、底色、固定／自适应边框、字号和对齐。显式属性保持设置意图，旧默认逐类兼容，全部 AP 状态同步；字号与对齐仅适用于文字／选择框。验收见[阶段三十二](verification/phase-32-editable-form-styles.md)，不保证过长字段文字完整显示或任意阅读器重建外观一致。

`ColumnWidth.Auto(minPoints, maxPoints)` 按实际单元格文本估算首选宽度，受显式上下限约束；页面不足时向最小值收缩，比例列分享余量。宽度包括内边距，无法留下正文字宽时失败。最终换行与警告仍由正常单元格布局产生。

普通文本合并格可覆盖 Auto 列，横向约束按稳定顺序分配剩余容量，纵向 owner 参与一次首选宽度探测；[行内内表 Auto](nested-automatic-tables-api.md)已接入父格实际宽度；含块格表 Auto 和单向源自动列仍独立。见[自动列合并 API](automatic-merged-columns-api.md)及[阶段六十六](verification/phase-66-merged-automatic-columns.md)。

`MultiColumn(child, columns, gap, true)` 在不增加原有页数的前提下，以最多 24 次测量搜索较短栏高，末片段只保留实际高度，使后续正文可以继续。自定义子组件必须确定、可重复测量。旧三参数构造器保持原有顺序分栏及整页高度。

`ContinuousColumns(regionFlow, columns, gap)` 从当前页剩余高度开始，续页使用完整栏高；支持 `FlowText`／`OpenTypeText`、`FlowColumn` 混合流与 `GridTable` 表格。等宽文字每次试排只换行一次，变宽最低多行策略使用有界重测；图片整块移动，表头逐占用栏重复，默认合并行组保持完整；末片段按最高栏实际高度接续正文。可用 `.balanced(true)` 进行最多 24 次试排，缩短公共栏高并保持页数及跳过的首页；显式合并格续排见[续页指南](grid-continuation-api.md)，基础几何见[连续分栏 API](continuous-columns-api.md)和[混合分栏 API](mixed-columns-api.md)。

`MultiFloatText(text, floats)` 已补齐首片段内多个左右矩形同时避让、错位／堆叠、封闭行带跳过与整组移页；浮动组件仅输出一次，正文续页恢复全宽。最多 64 个实际不相交的矩形，完整契约见[多浮动组件绕排](multi-floats-api.md)。

[ContourFloatText](contour-floats-api.md)进一步支持任意闭合直线／三次曲线轮廓、原文 UTF-16 锚点与未来区域锚点；逐整行带按实际轮廓避让，图形跨页遮罩分片，语义子组件通过区域流真正续排。文字原子组件不能靠重复裁切跨页，全部工作受有限预算控制。

## 取消、超时与文件写入

~~~java
var cancel = new AtomicBoolean();
var options = new ExecutionOptions(Duration.ofSeconds(30), cancel::get,
        progress -> showProgress(progress.stage(), progress.completed(), progress.total()),
        50L * 1024 * 1024);
engine.writeAtomically(document, backend, targetPath, options);
~~~

一个同步范围覆盖布局和写出；总输出预算单位为字节。`Progress` 是阶段内计数，不能当作整体百分比。`total=-1` 显式表示未知总量，先判断 `hasKnownTotal()` 再显示比例。`ExecutionException` 提供 `CANCELLED`／`TIMEOUT`／`OUTPUT_LIMIT` 及最后阶段、块、页上下文；线程中断作为取消处理并保留中断标记。库不创建后台执行线程，自定义组件、回调、原生调用须在返回或主动检查后才能观察取消。

输出流入口 `engine.write(document, backend, stream, options)` 保留流所有权，失败可能已写入部分字节。`writeAtomically` 要求目标父目录已存在及文件系统支持原子移动：临时文件写入成功后才替换目标，失败删除临时文件并保留原文件。PDF 使用 `PdfOptions.storage(TEMP_FILE)` 可把后端流缓冲放到临时文件；Layout IR、Display List 和资源仍驻留内存，不能称为完整流式大文档引擎。

固定列普通／纯富文本长表可采用独立的 [单向源 API](streaming-table-api.md)，同步逐页测量和输出，返回扁平计数。[阶段二十四](verification/phase-24-streaming-tables.md)已验收 7 组独立 JVM：布局丢弃页和真实 PDF 各 1k／10k／100k 行，另有 64 MiB 堆的 100k 行布局控制；退出临时文件数／字节均为 0。[阶段二十九](verification/phase-29-streaming-rich-text.md)进一步验收纯富文本／真实字体角色和空运行额度，9 组普通／富文本独立 JVM 包含 64 MiB 布局控制；[指南](streaming-rich-text-api.md)说明明确拒绝的语义。单向路径不保留完整 IR／Display List，但 PDFBox 页树仍随页数增长；不支持未来引用或可编辑交互，也不代表一般文档恒内存。

单向固定列横向合并已由[阶段六十四](verification/phase-64-streaming-column-spans.md)验收：表头／正文完整覆盖，按真实物理列测量与呈现，锚点和覆盖槽分别计数，单行预读及原子输出保护保持；图片和被动链接已由[阶段六十五](verification/phase-65-streaming-media.md)验收；完整行续排见[阶段六十七](verification/phase-67-streaming-row-split.md)；实际页码及准确总数见[单向字段 API](streaming-fields-api.md)；[显式有界纵向合并](streaming-row-spans-api.md)现已接入，默认单行路径保持；它与完整行拆分不能组合。

直接输出 Display List 可使用 `backend.write(display, stream, options)`。直接布局自定义流程可由 `ExecutionContext.execute(options, operation)` 包围，内部检查点共享该范围。

## PDF 场景能力

| 能力 | 调用与约束 |
| --- | --- |
| AES-256 | `PdfOptions.withEncryption(new PdfEncryption(owner, user, permissions))`；读者密码和所有者密码分离；权限依赖阅读器遵守，不是 DRM |
| PDF/A-1b／2b／3b／4f | `PdfArchival.Profile` 显式选择，旧构造默认 2b；静态 RGB／嵌入字体／XMP／OutputIntent，3b／4f 有限显式关联附件，4f 要求附件；已有文件转换为有损 RGB 页面重建，见[标准指南](pdf-standards-api.md) |
| PDF/UA-1 | `AccessibilityTree` 绑定逻辑顺序及最终命令，或 `DocumentPainter.paintAccessible` 绑定受支持模型；PDF 后端显式启用标签；已有 UA-1 标签读取／title／language 原子修改单独处理 |
| detached CMS 签名 | `PdfSigner.sign(sourcePath, borrowedOutput, options, signer)`；增量追加，源文件不变；回调消费准确 ByteRange，返回完整 DER CMS；失败输出须丢弃 |
| DocMDP 认证 | `PdfSigner.certify` 首签支持三档权限声明；普通批准签名遵守已识别的认证限制；路径签名原子替换，见[认证 API](pdf-certification-api.md) |

`PdfPades` 提供 B／T／LT／LTA、RFC 3161、DSS 证据更新及续期；`PdfTrust` 使用显式 DER 证书／信任锚、时刻和 OCSP／CRL。字节完整性、信任／撤销、时间戳和 DocMDP 修改符合性分别判断，不隐式联网或信任系统证书。测试证书只证明受控验证场景，不提供真实业务信任背书。标准标识不能替代最终文件独立验证，详见[标准 API](pdf-standards-api.md)及[阶段九十六](verification/phase-96-pdf-standards.md)。

## DOCX 与剩余范围

既有 PDF 使用上文各独立入口。原 `read`／`copy`／`merge` 继续拒绝交互，不承担对象重映射或语义恢复；被动对象组合和模型导入须分别显式选择。图层／静态标签只在显式开启的入口按兼容矩阵接受，`copy`／`merge`／`composeObjects` 仍拒绝其新页树重映射；签名及未知活动结构继续拒绝，ActualText、owner 身份和损失边界不能绕过。

DOCX 严格往返现支持富文本／行内图片单元格及外部超链接关系；外链只保存语义，不访问目标。PNG／JPEG 媒体、替代文字与标准 DrawingML 的范围见[行内图像 API](inline-images-api.md)。自动列宽以项目扩展属性往返保存，目标 Word 视觉列宽不等于本引擎 PDF 结果。

语义 Table 支持[列合并](column-spans-api.md)、[跨行合并](row-spans-api.md)及[有界递归块格／内表](block-cells-api.md)，DOCX 读写标准 gridSpan／vMerge 和 p/tbl/p，Java 深绑定保留后代 ID 与层级来源。默认纵向连通组整体分页；显式拆行支持递归格及纵向 owner 续排，覆盖行没有重复内容。合并跨度、完整父链、覆盖及续接区间非法时拒绝，允许损失也不猜测几何或丢弃非空续格；空续格仍校验样式及隐藏语义。多段格保持独立 Paragraph，真实空尾段保留；自有结构尾段／书签 owner 必须验证。其他不支持结构在 STRICT 下失败，在 ALLOW_LOSS 下返回诊断。不等于完整 OOXML Schema 校验；递归续排与纵向拆组见[阶段五十七](verification/phase-57-recursive-tables.md)。[阶段一百零三](verification/phase-103-native-docx-semantic-editing.md)已验收四层组合表、页区与浮动对象的 Word 编辑／保存回读、原生页面和 PDF；不由该子集推断任意 Word 文件兼容。

原生 PAGE／NUMPAGES／PAGEREF／编号 REF 已接入正文及普通单元格，支持显式宽度和扁平复杂字段导入；本地 Word 16.0 已实测更新并导出样例 PDF。重复表头 PAGE、非块起点页码书签、注释标记和题注编号仍有限制，见[DOCX 字段 API](docx-fields-api.md)与[阶段十二验收](verification/phase-12-docx-fields.md)。

相关能力与独立边界如下；四项补齐不代表任意格式、平台及阅读器兼容：

| 范围 | 后续验收目标 |
| --- | --- |
| 行内语义 | 正文与语义单元格固定宽度动态字段、行内交叉引用和正文脚注锚点已实现，见[字段 API](inline-fields-api.md)；仍缺单元格注释标记、任意组件内字段、自动字段宽度重排及未支持节点的无障碍映射 |
| 高级布局 | 文字／富文本及混合流的变宽栏已由[阶段五十九](verification/phase-59-variable-columns.md)接入，最低多行策略见[阶段六十三](verification/phase-63-minimum-lines.md)；[原子语义行／合并行组跨变宽区域](variable-tables-api.md)已接入；[行内格完整行跨变宽栏续排](variable-table-continuation-api.md)也已支持。[变宽递归子树与组件网格续排](variable-table-continuation-api.md)、[任意轮廓与跨页浮动锚点](contour-floats-api.md)及[可选原生方向／可变字体](native-opentype-api.md)已接入；标准合并格完整行及语义续排见[语义格 API](grid-semantic-flow-api.md) |
| DOCX | 浮动／裁剪等复杂图像、目录／评论／表单完整模型映射；首页／奇偶语义页区已验收；[有限样式级联](docx-style-import-api.md)及[原包文字编辑](docx-editing-api.md)已验收，Word 保存兼容单独登记 |
| PDF | 现有编辑、恢复、PDF/UA-1、选定 PDF/A 及 PAdES 的子集见对应 API；任意外部结构、任意标准等级与通用阅读器兼容不由有限样例保证 |
| 兼容与容量 | [阶段十三](verification/phase-13-authoring.md)有有界混合报告采样；[阶段二十四](verification/phase-24-streaming-tables.md)完成单向布局／PDF 各 1k／10k／100k 行及 64 MiB 堆布局控制共 7 组独立 JVM，分录观察堆、Windows 工作集／私有字节／句柄、吞吐、临时盘和退出文件清理。100k PDF 1,786 页、325.281 秒；PDFBox 累积对象仍在。后续[磁盘索引 PDF 与重放容量](streaming-replay-api.md)另有独立验证；一般 Word／LibreOffice 外观、其他系统／JDK及跨阅读器矩阵仍按对应缺口推进。有限容量证据不证明长期无泄漏 |

[编写时主题](themes-api.md)已接入 Java Document／Documents，集中管理正文、六级标题、便利语义表格和有界命名完整样式。加入时快照、显式完整值优先、组件六项继承及默认输出兼容见[阶段三十验收](verification/phase-30-authoring-themes.md)。XML 静态名称与四类可编辑字段显式外观分别见[XML 主题](xml-themes-api.md)和[FormStyle](form-styles-api.md)。页眉页脚独立默认值见[页区主题](xml-page-regions-api.md)；全局颜色／字体角色与分域覆盖已由[阶段六十](verification/phase-60-theme-appearance.md)验收；背景／前景的独立主题与固定框已由[阶段六十二](verification/phase-62-page-overlays.md)验收。

[目录独立样式](contents-styles-api.md)支持标题／页码完整排版及三处颜色，保留显式意图、旧默认和固定 48 pt 标签列。较大页码行框先参与目录分页，最终物理偏移与分节字段统一解析；真实标签放不下一行则失败。[阶段三十四](verification/phase-34-contents-styles.md)完成 873 项测试、4 页双渲染／逐页复查、新调用方及历史回归。

## 语义页区与 XML 分节

[图片／表格页区](xml-page-regions-api.md)复用现有 Paragraph／Table 与最终字段路径；[XML 分节](xml-sections-api.md)支持独立页面设置、显示页码和根页区继承／覆盖／清空；[DOCX 页区](docx-page-regions-api.md)使用标准 part、各自媒体关系及 PAGE／NUMPAGES。阶段四十二统一验收，模型、XML 与 DOCX 之间不经由显示列表传递语义。普通 Word 区域缺少固定几何时须显式配置；不提供一般 Word 无损互转或相同分页保证。

## 统一转换与实际损失

[统一入口](document-conversion-api.md)支持已验收 Markdown／HTML／DOCX 输入→语义编辑→Web／DOCX／显式布局后端。准备时完成一次真实编码，预检绑定确切结果；共享策略覆盖操作／功能／格式／默认值，IGNORE 仍保留损失，未编辑内容也检查。原包保留和 PDF 语义恢复不由策略自动获得，验证见[阶段四十七](verification/phase-47-document-conversion.md)。

单向 PDF 后端支持[加密及静态归档](streaming-protection-api.md)，复用完整文档的 AES-256 权限及 PDF/A-2b 限制；独立合规与密码／链接／图片验收见[阶段七十](verification/phase-70-streaming-protection.md)。有限并发失败恢复见[阶段六十九](verification/phase-69-streaming-stability.md)，不证明长期无泄漏或恒内存。

## 高级图形与 SVG 本地资源

[高级图形](advanced-graphics-api.md)支持 CMYK／ICC／专色、16 种混合模式、隔离透明组、线性／径向渐变、图案及复合裁剪。PDF 保留原生颜色空间和矢量资源；SVG／PNG 对印刷颜色按明确 ERROR／WARN／IGNORE 策略处理并保留实际损失。SVG 离线输入支持本地 defs／use、渐变及图案继承、marker、变换与裁剪组合。滤镜、动画、脚本、浏览器 CSS 和外部加载不属于此矢量子集。透明度和 PDF/A 限制不能由颜色降级绕过。

## 语义流、显式重放与磁盘索引 PDF

[语义长表及重放](streaming-replay-api.md)提供单次源递归块格、嵌套和纵向 owner 续排。显式可重开的快照计划增加有限前缀自动列宽、最终总页数、未来引用、目录／书签及组脚注；各遍完整语义摘要不一致则失败。`PdfSpoolBackend` 持有单页 PDF 对象，xref／页树索引写临时盘；普通 `PdfBackend` 继续按其配置处理完整文档，不能将新后端的对象释放契约归给它。容量证据分别记录堆、进程内存、临时磁盘及退出清理，有限压力样本不证明长期无泄漏。
